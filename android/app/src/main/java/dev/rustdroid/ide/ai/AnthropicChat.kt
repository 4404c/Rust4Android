package dev.rustdroid.ide.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * R4A fase 4: Claude (Anthropic) con el SDK oficial para Java/Kotlin.
 *
 * El resto del chat habla en el formato de [AiClient] (mensajes con
 * tool_calls al estilo OpenAI); acá se traduce a la API de Mensajes:
 * - los resultados de herramientas seguidos van en UN solo mensaje "user";
 * - cada respuesta de Claude se guarda entera ([AiClient.Message.raw]) para
 *   devolverla tal cual en el próximo pedido: los modelos nuevos piensan
 *   siempre y la API exige que esos bloques vuelvan sin cambios.
 */
class AnthropicChat {

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    private fun client(apiKey: String): AnthropicClient = synchronized(this) {
        cachedClient?.takeIf { cachedKey == apiKey }
            ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build().also {
                cachedClient = it
                cachedKey = apiKey
            }
    }

    /** Los modelos que puede usar la clave (GET /v1/models). */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        guard {
            client(apiKey).models().list().autoPager().map { it.id() }.toList()
        }
    }

    suspend fun chatStream(
        apiKey: String,
        model: String,
        messages: List<AiClient.Message>,
        tools: JsonArray?,
        onDelta: (String) -> Unit,
    ): AiClient.Reply = withContext(Dispatchers.IO) {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(if (model.startsWith("claude-3")) 8192L else 64000L)
            .messages(toParams(messages.filter { it.role != "system" }))
            // el prompt de sistema y las herramientas no cambian: se cachean
            .cacheControl(CacheControlEphemeral.builder().build())
        if (system.isNotBlank()) builder.system(system)
        tools?.forEach { builder.addTool(toTool(it.jsonObject)) }
        // si el modelo se niega por un filtro de seguridad, la API reintenta con
        // el modelo que Anthropic recomienda para ese caso (solo en estos modelos)
        if (FALLBACK_MODELS.any { model == it }) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val job = currentCoroutineContext()[Job]
        guard {
            val acc = MessageAccumulator.create()
            client(apiKey).messages().createStreaming(builder.build()).use { stream ->
                val it = stream.stream().iterator()
                while (it.hasNext()) {
                    if (job?.isActive == false) break // Detener: se cierra el stream
                    val event = acc.accumulate(it.next())
                    event.contentBlockDelta().flatMap { d -> d.delta().text() }
                        .ifPresent { t -> if (t.text().isNotEmpty()) onDelta(t.text()) }
                }
            }
            if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()

            val message = acc.message()
            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> throw AiClient.AiException(
                    "Claude no quiso responder este pedido (filtro de seguridad). Probá con otras palabras.",
                )
                StopReason.MAX_TOKENS -> throw AiClient.AiException(
                    "La respuesta se cortó por largo. Pedile que siga en partes más chicas.",
                )
                else -> {}
            }
            val text = message.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
            val calls = message.content().mapNotNull { b -> b.toolUse().orElse(null) }.map { tu ->
                AiClient.ToolCall(
                    id = tu.id(),
                    name = tu.name(),
                    arguments = jsonMapper().writeValueAsString(tu._input()),
                )
            }
            // la respuesta entera (con los bloques de pensamiento) para devolverla igual
            val raw = runCatching { jsonMapper().writeValueAsString(message.toParam()) }.getOrNull()
            AiClient.Reply(text, calls, raw)
        }
    }

    // ---------------- conversión ----------------

    private fun toParams(history: List<AiClient.Message>): List<MessageParam> {
        val out = ArrayList<MessageParam>()
        val pendingUser = ArrayList<ContentBlockParam>()
        fun flushUser() {
            if (pendingUser.isNotEmpty()) {
                out += MessageParam.ofUserOfContentBlockParams(pendingUser.toList())
                pendingUser.clear()
            }
        }
        for (m in history) {
            when (m.role) {
                "user" -> pendingUser += ContentBlockParam.ofText(
                    TextBlockParam.builder().text(m.content.orEmpty().ifEmpty { "…" }).build(),
                )
                "tool" -> pendingUser += ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(m.toolCallId.orEmpty())
                        .content(m.content.orEmpty().ifEmpty { "(vacío)" })
                        .build(),
                )
                "assistant" -> {
                    // mientras se espera una respuesta del modelo, nada más que agregar
                    val param = m.raw?.let { raw ->
                        runCatching { jsonMapper().readValue(raw, MessageParam::class.java) }.getOrNull()
                    } ?: assistantFromParts(m) ?: continue
                    flushUser()
                    out += param
                }
            }
        }
        flushUser()
        return out
    }

    /** Respuesta de otro proveedor (sin [AiClient.Message.raw]): texto + pedidos de herramientas. */
    private fun assistantFromParts(m: AiClient.Message): MessageParam? {
        val blocks = ArrayList<ContentBlockParam>()
        m.content?.takeIf { it.isNotBlank() }?.let {
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(it).build())
        }
        for (c in m.toolCalls) {
            val input = runCatching {
                @Suppress("UNCHECKED_CAST")
                jsonMapper().readValue(c.arguments, Map::class.java) as Map<String, Any?>
            }.getOrDefault(emptyMap())
            val inputBuilder = ToolUseBlockParam.Input.builder()
            input.forEach { (k, v) -> inputBuilder.putAdditionalProperty(k, JsonValue.from(v)) }
            blocks += ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder().id(c.id).name(c.name).input(inputBuilder.build()).build(),
            )
        }
        return if (blocks.isEmpty()) null else MessageParam.ofAssistantOfContentBlockParams(blocks)
    }

    /** Definición de herramienta estilo OpenAI → Tool de Anthropic. */
    private fun toTool(def: JsonObject): Tool {
        val fn = def["function"]!!.jsonObject
        val params = fn["parameters"]?.jsonObject
        val props = Tool.InputSchema.Properties.builder()
        params?.get("properties")?.jsonObject?.forEach { (name, schema) ->
            val s = schema.jsonObject
            props.putAdditionalProperty(
                name,
                JsonValue.from(
                    mapOf(
                        "type" to (s["type"]?.jsonPrimitive?.contentOrNull ?: "string"),
                        "description" to s["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ),
                ),
            )
        }
        val required = params?.get("required")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return Tool.builder()
            .name(fn["name"]!!.jsonPrimitive.content)
            .description(fn["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
            .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
            // el contenido de un archivo llega a medida que se escribe (sin esperar
            // a que el servidor junte todo); si vino cortado, la herramienta lo rechaza
            .putAdditionalProperty("eager_input_streaming", JsonValue.from(true))
            .build()
    }

    /** Los errores del SDK pasan a mensajes cortos para el chat. */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: AnthropicServiceException) {
        val hint = when (e.statusCode()) {
            401, 403 -> "La API key de Anthropic no es válida o no tiene permiso"
            404 -> "No se encontró (¿modelo mal escrito?)"
            429 -> "Llegaste al límite de pedidos de Anthropic; esperá un rato"
            529 -> "Anthropic está sobrecargado; probá en un rato"
            else -> "Error ${e.statusCode()} de Anthropic"
        }
        throw AiClient.AiException("$hint: ${e.message}")
    } catch (e: com.anthropic.errors.AnthropicIoException) {
        throw AiClient.AiException("Sin conexión o Anthropic no respondió (${e.message})")
    }

    private companion object {
        /** Modelos que aceptan `fallbacks: "default"` (Haiku no: se queda con la negativa). */
        val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
