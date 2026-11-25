package dev.rustdroid.ide

import dev.rustdroid.ide.ai.ToolErrors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4A (auditoría B17): un tropiezo de herramienta no se confunde con "el modelo no acepta herramientas". */
class ToolErrorsTest {

    @Test
    fun `groq tool_use_failed is a tool error but not unsupported`() {
        val msg = "Error 400: Failed to call a function. Please adjust your prompt. See 'failed_generation' for more details. (tool_use_failed)"
        assertTrue(ToolErrors.isToolError(msg))
        assertFalse(ToolErrors.meansUnsupported(msg))
    }

    @Test
    fun `models without tools are recognized`() {
        for (msg in listOf(
            "This model does not support tools",
            "tool use is not supported for this model",
            "Function calling is not enabled for this model",
            "Unsupported parameter: tools",
        )) {
            assertTrue(msg, ToolErrors.isToolError(msg))
            assertTrue(msg, ToolErrors.meansUnsupported(msg))
        }
    }

    @Test
    fun `other errors are left alone`() {
        assertFalse(ToolErrors.isToolError("Rate limit reached, try again in 10s"))
        assertFalse(ToolErrors.isToolError(null))
    }
}
