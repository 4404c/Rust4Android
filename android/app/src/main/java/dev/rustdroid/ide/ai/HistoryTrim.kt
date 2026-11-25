package dev.rustdroid.ide.ai

/**
 * R4A (auditoría M8): antes, cada paso le reenviaba al modelo TODO el
 * historial, incluidos los resultados completos de herramientas de pedidos
 * viejos (archivos leídos, salida de cargo: hasta 24 000 caracteres cada uno).
 * En una charla larga eso gasta tokens y cuota cada vez más rápido y termina
 * en "contexto muy largo".
 *
 * Acá se arma la copia que viaja en el pedido: los resultados de herramientas
 * de turnos ANTERIORES al último mensaje del usuario se acortan a
 * [KEEP_CHARS] caracteres + una nota. Los del turno actual van enteros (la IA
 * los está usando). El historial guardado y lo que se ve en el chat no cambian.
 *
 * Es determinista (solo depende de la posición del último mensaje del
 * usuario): dentro de un mismo turno el comienzo del pedido no cambia, así
 * que la caché de prefijos de los proveedores sigue sirviendo.
 *
 * No se usa con Claude: ahí el historial tiene que volver sin editar (los
 * bloques de razonamiento se validan) y la caché automática ya lo abarata.
 */
object HistoryTrim {

    const val KEEP_CHARS = 300

    fun forRequest(history: List<AiClient.Message>): List<AiClient.Message> {
        // el último pedido real del usuario (las notas "[R4A: …]" no cuentan)
        val lastUser = history.indexOfLast { it.role == "user" && !it.content.orEmpty().startsWith("[R4A:") }
        if (lastUser <= 0) return history
        return history.mapIndexed { i, m ->
            val content = m.content.orEmpty()
            if (i < lastUser && m.role == "tool" && content.length > KEEP_CHARS + NOTE_SLACK) {
                m.copy(
                    content = content.take(KEEP_CHARS) +
                        "\n… [R4A: resultado viejo recortado (${content.length} caracteres). " +
                        "Si lo necesitás de nuevo, volvé a usar la herramienta.]",
                )
            } else m
        }
    }

    /** No vale la pena recortar si se ahorra menos que esto. */
    private const val NOTE_SLACK = 200
}
