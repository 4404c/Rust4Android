package dev.rustdroid.ide.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * R4A: cliente mínimo para APIs "compatibles con OpenAI"
 * (Groq, OpenRouter, Gemini, OpenAI, Ollama…).
 */
class AiClient(baseHttp: OkHttpClient) {

    data class Message(val role: String, val content: String)

    class AiException(message: String) : Exception(message)

    // El streaming puede durar más que el callTimeout de 60 s del cliente
    // compartido: sin límite total, pero cortamos si deja de llegar texto.
    private val http = baseHttp.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** GET /models → ids de modelos, ordenados. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        withCall(req) { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException(errorText(resp.code, body))
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                ?.map { it.removePrefix("models/") } // Gemini antepone "models/"
                ?.sorted()
                ?: throw AiException("La respuesta no trae una lista de modelos")
        }
    }

    /**
     * POST /chat/completions con stream=true. Llama [onDelta] con cada
     * pedacito de texto a medida que llega (en el hilo de IO).
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        onDelta: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            // OpenRouter lo usa para identificar la app (opcional)
            .header("X-Title", "R4A")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        withCall(req) { resp ->
            if (!resp.isSuccessful) {
                throw AiException(errorText(resp.code, resp.body?.string().orEmpty()))
            }
            val source = resp.body?.source() ?: throw AiException("Respuesta vacía")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw AiException(errorMessage(it.toString()) ?: it.toString()) }
                val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                if (!delta.isNullOrEmpty()) onDelta(delta)
            }
        }
    }

    /**
     * Ejecuta la llamada y corre [block] con la respuesta. Si se cancela la
     * corrutina (botón Detener) se cancela la llamada HTTP, también mientras
     * se lee el stream.
     */
    private suspend fun <T> withCall(req: Request, block: (Response) -> T): T {
        val call: Call = http.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use(block)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw AiException("Sin conexión o el servidor no respondió (${e.message})")
        } finally {
            handle?.dispose()
        }
    }

    private fun errorText(code: Int, body: String): String {
        val msg = errorMessage(body)
        val hint = when (code) {
            401, 403 -> "La API key no es válida o no tiene permiso"
            404 -> "No se encontró (¿URL o modelo mal escrito?)"
            429 -> "Llegaste al límite de pedidos; esperá un rato"
            else -> "Error $code"
        }
        return if (msg != null) "$hint: $msg" else hint
    }

    /** Saca el texto de {"error":{"message":…}} o {"error":"…"}. */
    private fun errorMessage(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body)
        val err = (root as? JsonObject)?.get("error") ?: root
        (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: err.jsonPrimitive.contentOrNull
    }.getOrNull()
}
