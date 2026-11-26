package dev.rustdroid.ide.ai

/**
 * R4A: arregla charlas guardadas con pedidos de herramienta rotos. Una
 * versión vieja pegaba dos pedidos de Gemini en uno («read_filecargo_check»)
 * y lo guardaba así; desde entonces el proveedor rechazaba TODA la charla
 * (error 400), aunque se tocara Reintentar.
 *
 * Saca los pedidos cuyo nombre no es una herramienta conocida y las
 * respuestas `tool` que les correspondían (mismo tool_call_id). Si un
 * mensaje del asistente queda sin pedidos y sin texto, se saca entero.
 * No toca los mensajes de Claude que traen `raw` (van tal cual al SDK), ni
 * nada más: los `extra` (thought_signature de Gemini) de los pedidos buenos
 * siguen igual.
 */
object HistoryRepair {

    /** El historial arreglado, o el mismo objeto si no había nada roto. */
    fun dropUnknownTools(history: List<AiClient.Message>, known: Set<String>): List<AiClient.Message> {
        val bad = history.filter { it.raw == null }
            .flatMap { it.toolCalls }
            .filter { it.name !in known }
            .map { it.id }
            .toSet()
        if (bad.isEmpty()) return history
        return history.mapNotNull { m ->
            when {
                m.raw != null -> m
                m.role == "tool" && m.toolCallId in bad -> null
                m.toolCalls.any { it.id in bad } -> {
                    val left = m.toolCalls.filter { it.id !in bad }
                    if (left.isEmpty() && m.content.isNullOrBlank()) null else m.copy(toolCalls = left)
                }
                else -> m
            }
        }
    }
}
