package dev.rustdroid.ide.ai

/**
 * R4A (auditoría B17): cómo leer un error del proveedor cuando se mandaron
 * herramientas. Antes, cualquier error que nombrara "tool" o "function"
 * apagaba las herramientas de la charla PARA SIEMPRE; pero Groq a veces
 * devuelve `tool_use_failed` ("Failed to call a function…") en una sola
 * respuesta, y desde ahí la IA ya no podía usar el proyecto.
 */
object ToolErrors {

    /** El error tiene que ver con herramientas. */
    fun isToolError(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        return "tool" in m || "function" in m
    }

    /**
     * El modelo directamente NO acepta herramientas (no fue un tropiezo de
     * una respuesta): ahí sí conviene seguir la charla sin ellas.
     */
    fun meansUnsupported(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        return UNSUPPORTED.any { it in m }
    }

    private val UNSUPPORTED = listOf(
        "not support", "unsupported", "no soporta", "not enabled", "not available for this model",
        "does not accept tools", "tools are not allowed",
    )
}
