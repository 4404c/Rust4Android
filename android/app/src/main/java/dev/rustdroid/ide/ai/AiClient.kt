package dev.rustdroid.ide.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.putJsonObject
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

    /** Un mensaje del historial. [toolCalls] solo en "assistant"; [toolCallId] solo en "tool". */
    data class Message(
        val role: String,
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
        /** Respuesta de Claude tal cual (JSON), para devolverla igual. Solo la usa [AnthropicChat]. */
        val raw: String? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content ?: "")
            if (toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    toolCalls.forEach { c ->
                        addJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", c.name)
                                put("arguments", c.arguments)
                            }
                        }
                    }
                }
            }
            toolCallId?.let { put("tool_call_id", it) }
        }
    }

    /** El modelo pide usar una herramienta; [arguments] es JSON. */
    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class Reply(val content: String, val toolCalls: List<ToolCall>, val raw: String? = null)

    /** [retryAfterSec]: límite de pedidos por minuto; vale la pena esperar y reintentar. */
    open class AiException(message: String, val retryAfterSec: Double? = null) : Exception(message)

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
            if (!resp.isSuccessful) throw AiException(errorText(resp.code, body) + urlHint(baseUrl))
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                ?.map { it.removePrefix("models/") } // Gemini antepone "models/"
                ?.sorted()
                ?: throw AiException("La respuesta no trae una lista de modelos")
        }
    }

    /**
     * POST /chat/completions con stream=true. Llama [onDelta] con cada
     * pedacito de texto a medida que llega (en el hilo de IO). Si se pasan
     * [tools], el modelo puede pedir usarlas: vuelven en [Reply.toolCalls]
     * (los pedazos de cada llamada se juntan acá).
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        tools: JsonArray? = null,
        /** "" = no se manda. OpenRouter lo recibe como reasoning.effort. */
        effort: String = "",
        onDelta: (String) -> Unit,
    ): Reply = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m -> add(m.toJson()) }
            }
            if (tools != null && tools.isNotEmpty()) put("tools", tools)
            if (effort.isNotEmpty()) {
                if ("openrouter.ai" in baseUrl) putJsonObject("reasoning") { put("effort", effort) }
                else put("reasoning_effort", effort)
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
                val body = resp.body?.string().orEmpty()
                throw AiException(errorText(resp.code, body) + urlHint(baseUrl), retryAfter(resp, body))
            }
            val source = resp.body?.source() ?: throw AiException("Respuesta vacía")
            val text = StringBuilder()
            // index → (id, nombre, argumentos) que van llegando en pedazos
            val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw AiException(errorMessage(it.toString()) ?: it.toString()) }
                val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) { text.append(it); onDelta(it) }
                }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, el ->
                    val tc = el.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: pos
                    val acc = calls.getOrPut(index) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.first.append(it) }
                    val fn = tc["function"]?.jsonObject
                    fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { acc.second.append(it) }
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.third.append(it) }
                }
            }
            Reply(
                content = text.toString(),
                toolCalls = calls.map { (i, acc) ->
                    ToolCall(
                        id = acc.first.toString().ifEmpty { "call_$i" },
                        name = acc.second.toString(),
                        arguments = acc.third.toString().ifBlank { "{}" },
                    )
                },
            )
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

    /**
     * Segundos a esperar en un 429: el header Retry-After o el texto del
     * error ("try again in 20.6s", así lo dice Groq). null = no es un 429.
     */
    private fun retryAfter(resp: Response, body: String): Double? {
        if (resp.code != 429) return null
        // límite POR DÍA (OpenRouter gratis, etc.): esperar segundos no sirve
        val low = body.lowercase()
        if ("per-day" in low || "per day" in low || "daily" in low || "rpd" in low) return null
        resp.header("retry-after")?.toDoubleOrNull()?.let { return it }
        Regex("""try again in (?:(\d+)m)?([\d.]+)s""").find(body)?.let { m ->
            val min = m.groupValues[1].toDoubleOrNull() ?: 0.0
            return min * 60 + (m.groupValues[2].toDoubleOrNull() ?: 0.0)
        }
        return 10.0
    }

    /** Pista para un error común: la URL de Gemini sin el "/openai" del final. */
    private fun urlHint(baseUrl: String): String =
        if ("generativelanguage.googleapis.com" in baseUrl && !baseUrl.trimEnd('/').endsWith("/openai"))
            "\n→ Para Gemini elegí el proveedor «Google Gemini» (no hace falta URL), o usá la URL https://generativelanguage.googleapis.com/v1beta/openai"
        else ""

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
        // Gemini devuelve los errores dentro de una lista: [{"error":{...}}]
        val parsed = json.parseToJsonElement(body)
        val root = (parsed as? JsonArray)?.firstOrNull() ?: parsed
        val err = (root as? JsonObject)?.get("error") ?: root
        (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: err.jsonPrimitive.contentOrNull
    }.getOrNull()
}
