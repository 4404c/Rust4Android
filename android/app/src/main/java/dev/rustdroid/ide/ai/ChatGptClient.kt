package dev.rustdroid.ide.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * R4A: el chat con el plan de ChatGPT (ver [ChatGptAuth]). Habla la API de
 * Responses (`POST /v1/responses`), que es la que OpenAI documenta para
 * este modo, con sus reglas: `store: false`, `stream: true`, sin mensajes
 * "system" (va en `instructions`) y sin temperature/max_output_tokens.
 */
class ChatGptClient(baseHttp: OkHttpClient) {

    private val http = baseHttp.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Modelos de la cuenta: los que tienen visibility "list"; se usa el slug. */
    suspend fun listModels(token: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/models").header("Authorization", "Bearer $token").get().build()
        execute(req) { body, code ->
            if (code !in 200..299) throw AiClient.AiException(errorText(code, body))
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
            data.mapNotNull { el ->
                val o = el.jsonObject
                val visibility = o["visibility"]?.jsonPrimitive?.contentOrNull
                if (visibility != null && visibility != "list") return@mapNotNull null
                (o["slug"] ?: o["id"])?.jsonPrimitive?.contentOrNull
            }.distinct()
        }
    }

    suspend fun chatStream(
        token: String,
        model: String,
        messages: List<AiClient.Message>,
        tools: JsonArray?,
        onDelta: (String) -> Unit,
    ): AiClient.Reply = withContext(Dispatchers.IO) {
        val instructions = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
        val payload = buildJsonObject {
            put("model", model)
            put("store", false)
            put("stream", true)
            if (instructions.isNotBlank()) put("instructions", instructions)
            putJsonArray("input") {
                for (m in messages) {
                    when (m.role) {
                        "user" -> addJsonObject {
                            put("role", "user")
                            put("content", m.content.orEmpty())
                        }
                        "assistant" -> {
                            m.content?.takeIf { it.isNotBlank() }?.let { text ->
                                addJsonObject {
                                    put("role", "assistant")
                                    put("content", text)
                                }
                            }
                            m.toolCalls.forEach { c ->
                                addJsonObject {
                                    put("type", "function_call")
                                    put("call_id", c.id)
                                    put("name", c.name)
                                    put("arguments", c.arguments)
                                }
                            }
                        }
                        "tool" -> addJsonObject {
                            put("type", "function_call_output")
                            put("call_id", m.toolCallId.orEmpty())
                            put("output", m.content.orEmpty())
                        }
                    }
                }
            }
            // herramientas en el formato de Responses: planas, sin el "function" anidado
            if (tools != null && tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        val fn = t.jsonObject["function"]?.jsonObject ?: return@forEach
                        addJsonObject {
                            put("type", "function")
                            fn["name"]?.let { put("name", it) }
                            fn["description"]?.let { put("description", it) }
                            fn["parameters"]?.let { put("parameters", it) }
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$BASE/responses")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw AiClient.AiException(errorText(resp.code, resp.body?.string().orEmpty()))
                val source = resp.body?.source() ?: throw AiClient.AiException("Respuesta vacía de ChatGPT")
                val text = StringBuilder()
                val calls = ArrayList<AiClient.ToolCall>()
                var completed = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                        "response.output_text.delta" -> ev["delta"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it.isNotEmpty()) { text.append(it); onDelta(it) }
                        }
                        // cada pedido de herramienta llega completo al cerrarse su item
                        "response.output_item.done" -> {
                            val item = ev["item"]?.jsonObject
                            if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                calls += AiClient.ToolCall(
                                    id = item["call_id"]?.jsonPrimitive?.contentOrNull
                                        ?: item["id"]?.jsonPrimitive?.contentOrNull ?: "call_${calls.size}",
                                    name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    arguments = item["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
                                )
                            }
                        }
                        "response.completed" -> { completed = true; break }
                        "response.incomplete" -> throw AiClient.AiException(
                            "ChatGPT cortó la respuesta antes de terminar. Pedile que siga.",
                        )
                        "response.failed" -> throw AiClient.AiException(
                            failText(ev["response"]?.jsonObject?.get("error")?.jsonObject),
                        )
                        "error" -> throw AiClient.AiException(failText(ev["error"]?.jsonObject ?: ev))
                    }
                }
                if (!completed) {
                    currentCoroutineContext().ensureActive()
                    throw AiClient.AiException("Se cortó la conexión con ChatGPT a mitad de la respuesta.")
                }
                AiClient.Reply(text.toString(), calls)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw AiClient.AiException("Sin conexión o ChatGPT no respondió (${e.message})")
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun <T> execute(req: Request, block: (String, Int) -> T): T {
        val call = http.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { block(it.body?.string().orEmpty(), it.code) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw AiClient.AiException("Sin conexión o ChatGPT no respondió (${e.message})")
        } finally {
            handle?.dispose()
        }
    }

    /** Errores antes de abrir el stream: {"detail":…} o {"error":{"code":…}}. */
    private fun errorText(code: Int, body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        root?.get("error")?.let { e -> (e as? JsonObject)?.let { return failText(it) } }
        val detail = root?.get("detail")?.jsonPrimitive?.contentOrNull
        val hint = when (code) {
            401 -> "ChatGPT no aceptó la sesión: borrá la conexión y volvé a iniciar sesión"
            403 -> "Tu cuenta o tu región no permiten usar el plan de ChatGPT en esta app"
            429 -> "Llegaste al límite de uso de tu plan de ChatGPT"
            503 -> "El uso del plan de ChatGPT no está disponible ahora; probá en un rato"
            else -> "Error $code de ChatGPT"
        }
        return if (detail != null) "$hint: $detail" else hint
    }

    /** Los códigos que documenta OpenAI para este modo, en criollo. */
    private fun failText(error: JsonObject?): String {
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        val msg = error?.get("message")?.jsonPrimitive?.contentOrNull
        val text = when (code) {
            "subscription_sharing_usage_limit_exceeded" ->
                "Llegaste al límite de uso de tu plan de ChatGPT. Podés verlo en ${ChatGptAuth.USAGE_URL}"
            "subscription_sharing_user_not_eligible" ->
                "Tu cuenta de ChatGPT no puede usar el plan en esta app."
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                "El uso del plan de ChatGPT no está disponible ahora; probá en un rato."
            "subscription_sharing_unsupported_capability" ->
                "ChatGPT no acepta algo de este pedido en este modo${error?.get("param")?.jsonPrimitive?.contentOrNull?.let { " ($it)" }.orEmpty()}."
            "subscription_sharing_route_not_supported" ->
                "Este modo de ChatGPT no permite este tipo de pedido."
            "subscription_sharing_invalid_user" ->
                "Se cerró la sesión de ChatGPT. Borrá esta conexión y volvé a iniciar sesión."
            else -> null
        }
        return text ?: listOfNotNull("Error de ChatGPT", code, msg).joinToString(": ")
    }

    private companion object {
        const val BASE = "https://api.openai.com/v1"
    }
}
