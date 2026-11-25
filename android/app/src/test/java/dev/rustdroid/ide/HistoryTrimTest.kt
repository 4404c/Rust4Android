package dev.rustdroid.ide

import dev.rustdroid.ide.ai.AiClient.Message
import dev.rustdroid.ide.ai.AiClient.ToolCall
import dev.rustdroid.ide.ai.HistoryTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4A (auditoría M8): van enteros solo los resultados de los últimos pasos con
 * herramientas; los anteriores (de este turno o de otros) pasan a resumen.
 */
class HistoryTrimTest {

    /** 300 líneas, como un archivo de verdad. */
    private val big = (1..300).joinToString("\n") { "let x$it = $it; // relleno relleno" }

    private fun step(id: String, path: String, result: String) = listOf(
        Message("assistant", "", toolCalls = listOf(ToolCall(id, "read_file", "{\"path\":\"$path\"}", extra = "{\"sig\":1}"))),
        Message("tool", result, toolCallId = id),
    )

    private fun turn(prompt: String, result: String) =
        listOf(Message("user", prompt)) + step("c-$prompt", "src/main.rs", result) + Message("assistant", "listo")

    @Test
    fun `old turns become a short summary and the current turn stays whole`() {
        val history = turn("uno", big) + turn("dos", big)
        val out = HistoryTrim.forRequest(history)
        assertEquals(history.size, out.size)
        val oldTool = out[2].content!!
        assertTrue(oldTool, oldTool.startsWith("[R4A: leyó src/main.rs, 300 líneas."))
        assertTrue(oldTool.length < 200)
        assertEquals(big, out[6].content) // turno actual entero
        // lo que no es resultado de herramienta no se toca (raw, tool_calls, extra)
        for (i in out.indices) if (history[i].role != "tool") assertSame(history[i], out[i])
        assertEquals("c-uno", out[2].toolCallId)
    }

    @Test
    fun `inside one long answer only the last steps go whole`() {
        val history = listOf(Message("user", "arreglá todo")) +
            step("a", "src/a.rs", big) + step("b", "src/b.rs", big) +
            step("c", "src/c.rs", big) + step("d", "src/d.rs", big)
        val out = HistoryTrim.forRequest(history)
        assertTrue(out[2].content!!.startsWith("[R4A: leyó src/a.rs, 300 líneas."))
        assertTrue(out[4].content!!.startsWith("[R4A: leyó src/b.rs, 300 líneas."))
        assertEquals(big, out[6].content) // los últimos KEEP_STEPS pasos, enteros
        assertEquals(big, out[8].content)
        assertEquals(listOf("a", "b", "c", "d"), out.mapNotNull { it.toolCallId })
    }

    @Test
    fun `other long outputs keep their beginning`() {
        val history = listOf(Message("user", "compilá")) +
            listOf(
                Message("assistant", "", toolCalls = listOf(ToolCall("k", "cargo_check", "{}"))),
                Message("tool", big, toolCallId = "k"),
            ) + step("b", "src/b.rs", "corto") + step("c", "src/c.rs", "corto")
        val out = HistoryTrim.forRequest(history)[2].content!!
        assertTrue(out.startsWith(big.take(HistoryTrim.KEEP_CHARS)))
        assertTrue(out, out.contains("resultado viejo de cargo_check recortado (300 líneas)"))
    }

    @Test
    fun `short results and short answers are left alone`() {
        val one = turn("uno", big)
        assertSame(one, HistoryTrim.forRequest(one))
        val small = turn("uno", "corto") + turn("dos", "corto")
        assertEquals(small, HistoryTrim.forRequest(small))
        val twoSteps = listOf(Message("user", "x")) + step("a", "a.rs", big) + step("b", "b.rs", big)
        assertEquals(twoSteps, HistoryTrim.forRequest(twoSteps))
    }

    @Test
    fun `R4A notes do not count as a new user turn`() {
        val history = turn("uno", big) + Message("user", "[R4A: el usuario deshizo este cambio: x]")
        assertEquals(big, HistoryTrim.forRequest(history)[2].content)
    }
}
