package dev.rustdroid.ide

import dev.rustdroid.ide.ai.AiClient.Message
import dev.rustdroid.ide.ai.AiClient.ToolCall
import dev.rustdroid.ide.ai.HistoryTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4A (auditoría M8): solo se recortan resultados de herramientas de turnos viejos. */
class HistoryTrimTest {

    private val big = "x".repeat(5_000)

    private fun turn(prompt: String, result: String) = listOf(
        Message("user", prompt),
        Message("assistant", "", toolCalls = listOf(ToolCall("c-$prompt", "read_file", "{\"path\":\"src/main.rs\"}"))),
        Message("tool", result, toolCallId = "c-$prompt"),
        Message("assistant", "listo"),
    )

    @Test
    fun `old tool results are trimmed and the current turn stays whole`() {
        val history = turn("uno", big) + turn("dos", big)
        val out = HistoryTrim.forRequest(history)
        assertEquals(history.size, out.size)
        val oldTool = out[2].content!!
        assertTrue(oldTool.length < 600)
        assertTrue(oldTool.startsWith("x".repeat(HistoryTrim.KEEP_CHARS)))
        assertTrue(oldTool.contains("5000 caracteres"))
        assertEquals(big, out[6].content) // turno actual entero
        // lo que no es resultado de herramienta no se toca
        for (i in out.indices) if (history[i].role != "tool") assertSame(history[i], out[i])
        assertEquals("c-uno", out[2].toolCallId)
    }

    @Test
    fun `short results and single-turn chats are left alone`() {
        val one = turn("uno", big)
        assertSame(one, HistoryTrim.forRequest(one))
        val small = turn("uno", "corto") + turn("dos", "corto")
        assertEquals(small, HistoryTrim.forRequest(small))
    }

    @Test
    fun `R4A notes do not count as a new user turn`() {
        val history = turn("uno", big) + Message("user", "[R4A: el usuario deshizo este cambio: x]")
        assertEquals(big, HistoryTrim.forRequest(history)[2].content)
    }
}
