package dev.rustdroid.ide

import dev.rustdroid.ide.ai.AiClient.Message
import dev.rustdroid.ide.ai.AiClient.ToolCall
import dev.rustdroid.ide.ai.HistoryRepair
import dev.rustdroid.ide.ui.chat.isBadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4A: charlas guardadas con un pedido roto («read_filecargo_check») se limpian. */
class HistoryRepairTest {

    private val known = setOf("read_file", "cargo_check")

    @Test fun dropsBrokenCallAndItsResult() {
        val good = ToolCall("a", "read_file", "{\"path\":\"src/main.rs\"}", extra = "{\"sig\":1}")
        val broken = ToolCall("b", "read_filecargo_check", "{}", extra = "{\"sig\":2}")
        val history = listOf(
            Message("user", "arreglá el código"),
            Message("assistant", "", toolCalls = listOf(broken)),
            Message("tool", "Error: no existe", toolCallId = "b"),
            Message("assistant", "Leo el archivo", toolCalls = listOf(good, broken.copy(id = "c"))),
            Message("tool", "fn main() {}", toolCallId = "a"),
            Message("tool", "Error: no existe", toolCallId = "c"),
            Message("assistant", "Listo"),
        )
        val fixed = HistoryRepair.dropUnknownTools(history, known)
        assertEquals(
            listOf(
                Message("user", "arreglá el código"),
                Message("assistant", "Leo el archivo", toolCalls = listOf(good)),
                Message("tool", "fn main() {}", toolCallId = "a"),
                Message("assistant", "Listo"),
            ),
            fixed,
        )
        // la thought_signature del pedido bueno sigue
        assertEquals("{\"sig\":1}", fixed[1].toolCalls.single().extra)
    }

    @Test fun leavesClaudeRawAndCleanHistoryAlone() {
        val history = listOf(
            Message("user", "hola"),
            Message("assistant", "", toolCalls = listOf(ToolCall("x", "rara", "{}")), raw = "[{\"type\":\"tool_use\"}]"),
            Message("tool", "ok", toolCallId = "x"),
        )
        assertSame(history, HistoryRepair.dropUnknownTools(history, known))
    }

    @Test fun badRequestHintOnlyFor400() {
        assertTrue(isBadRequest("Error 400: Request contains an invalid argument."))
        assertTrue(isBadRequest("Error 400 de Anthropic: invalid_request_error"))
        assertFalse(isBadRequest("Llegaste al límite de pedidos; esperá un rato"))
        assertFalse(isBadRequest("Error 503: The model is overloaded"))
        assertFalse(isBadRequest("Sin conexión o el servidor no respondió (timeout)"))
    }
}
