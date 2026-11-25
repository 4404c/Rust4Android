package dev.rustdroid.ide.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * R4A (auditoría M8): antes, cada paso le reenviaba al modelo TODO el
 * historial, incluidos los resultados completos de herramientas (archivos
 * leídos, salida de cargo: hasta 24 000 caracteres cada uno). En una charla
 * larga, o en una sola respuesta con muchas herramientas, eso gasta tokens y
 * cuota cada vez más rápido y termina en "contexto muy largo".
 *
 * Acá se arma la copia que viaja en el pedido: solo van enteros los
 * resultados de los últimos [KEEP_STEPS] pasos con herramientas del turno
 * actual (la IA los está usando). Los anteriores, de este turno o de turnos
 * viejos, se cambian por un resumen corto ("[R4A: leyó src/main.rs, 300
 * líneas …]"); la IA puede volver a leerlos. El historial guardado y lo que
 * se ve en el chat no cambian, y cada resultado conserva su tool_call_id.
 *
 * Es determinista (solo depende de dónde están los pasos): el comienzo del
 * pedido cambia recién donde un resultado pasa a resumen, así que la caché de
 * prefijos de los proveedores sigue sirviendo para lo anterior.
 *
 * No se usa con Claude: ahí el historial tiene que volver sin editar (los
 * bloques de razonamiento se validan contra lo que vino antes) y la caché
 * automática ya lo abarata.
 */
object HistoryTrim {

    /** Pasos (respuestas del modelo con herramientas) cuyos resultados van enteros. */
    const val KEEP_STEPS = 2

    /** Lo que se deja del principio de un resultado largo que no es un archivo. */
    const val KEEP_CHARS = 300

    /** No vale la pena recortar si se ahorra menos que esto. */
    private const val NOTE_SLACK = 200

    fun forRequest(history: List<AiClient.Message>): List<AiClient.Message> {
        // el último pedido real del usuario (las notas "[R4A: …]" no cuentan)
        val lastUser = history.indexOfLast { it.role == "user" && !it.content.orEmpty().startsWith("[R4A:") }
        // desde acá van enteros: el (KEEP_STEPS)-ésimo paso con herramientas, contando desde el final
        val steps = history.indices.filter { i ->
            i > lastUser && history[i].role == "assistant" && history[i].toolCalls.isNotEmpty()
        }
        val keepFrom = if (steps.size >= KEEP_STEPS) steps[steps.size - KEEP_STEPS] else maxOf(lastUser, 0)
        if (keepFrom <= 0) return history

        // id de cada pedido de herramienta → nombre y argumentos (para el resumen)
        val calls = HashMap<String, AiClient.ToolCall>()
        history.forEach { m -> m.toolCalls.forEach { calls[it.id] = it } }

        return history.mapIndexed { i, m ->
            val content = m.content.orEmpty()
            if (i < keepFrom && m.role == "tool" && content.length > KEEP_CHARS + NOTE_SLACK) {
                m.copy(content = summary(calls[m.toolCallId], content))
            } else m
        }
    }

    private fun summary(call: AiClient.ToolCall?, content: String): String {
        val lines = content.lines().size
        return when (call?.name) {
            "read_file" ->
                "[R4A: leyó ${arg(call, "path") ?: "un archivo"}, $lines líneas. " +
                    "Ya no va entero para ahorrar; si lo necesitás, volvé a leerlo con read_file.]"
            "read_chat" ->
                "[R4A: leyó la charla ${arg(call, "id") ?: ""}, $lines líneas. " +
                    "Si la necesitás, volvé a leerla con read_chat.]"
            else -> content.take(KEEP_CHARS) +
                "\n… [R4A: resultado viejo${call?.name?.let { " de $it" }.orEmpty()} recortado ($lines líneas). " +
                "Si lo necesitás de nuevo, volvé a usar la herramienta.]"
        }
    }

    private fun arg(call: AiClient.ToolCall, name: String): String? = runCatching {
        Json.parseToJsonElement(call.arguments).jsonObject[name]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
