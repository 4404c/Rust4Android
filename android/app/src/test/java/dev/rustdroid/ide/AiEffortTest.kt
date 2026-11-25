package dev.rustdroid.ide

import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.ai.AiSettings.EFFORT_OFF
import dev.rustdroid.ide.ai.AiSettings.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4A: «Apagado» en el esfuerzo y solo las opciones que acepta cada modelo. */
class AiEffortTest {

    private fun opts(p: Provider, model: String) = AiSettings.effortOptions(p, model)

    @Test
    fun claudeThatAlwaysThinksHasNoOff() {
        assertFalse(EFFORT_OFF in opts(Provider.ANTHROPIC, "claude-opus-5-5"))
        assertFalse(EFFORT_OFF in opts(Provider.ANTHROPIC, "claude-fable-5-2"))
        assertTrue("max" in opts(Provider.ANTHROPIC, "claude-opus-5-5"))
    }

    @Test
    fun claudeThatCanStopThinkingHasOff() {
        assertTrue(EFFORT_OFF in opts(Provider.ANTHROPIC, "claude-sonnet-5-5"))
        assertTrue(EFFORT_OFF in opts(Provider.ANTHROPIC, "claude-opus-4-8"))
        assertFalse("xhigh" in opts(Provider.ANTHROPIC, "claude-opus-4-6"))
        assertEquals(listOf(""), opts(Provider.ANTHROPIC, "claude-haiku-4-5"))
    }

    @Test
    fun openAiFamilies() {
        assertTrue(EFFORT_OFF in opts(Provider.OPENAI, "gpt-5.1"))
        assertEquals("none", AiSettings.offWireValue("gpt-5.1"))
        assertEquals("minimal", AiSettings.offWireValue("gpt-5-mini"))
        assertEquals("minimal", AiSettings.offWireValue("openai/gpt-5"))
        assertEquals(listOf("", "low", "medium", "high"), opts(Provider.OPENAI, "o4-mini"))
        assertEquals(listOf(""), opts(Provider.OPENAI, "gpt-4o"))
    }

    @Test
    fun otherProviders() {
        assertEquals(listOf("", "low", "medium", "high"), opts(Provider.GROQ, "openai/gpt-oss-20b"))
        assertEquals(listOf("", EFFORT_OFF), opts(Provider.GROQ, "qwen/qwen3-32b"))
        assertEquals(listOf(""), opts(Provider.GROQ, "llama-3.3-70b-versatile"))
        assertTrue(EFFORT_OFF in opts(Provider.GEMINI, "gemini-2.5-flash"))
        assertFalse(EFFORT_OFF in opts(Provider.GEMINI, "gemini-2.5-pro"))
        assertTrue(EFFORT_OFF in opts(Provider.OPENROUTER, "openrouter/free"))
    }

    @Test
    fun effectiveEffortFallsBackToLowThenAuto() {
        fun conn(p: Provider, model: String, effort: String) =
            AiSettings.Connection("id", "n", p, "", model, effort)
        assertEquals("low", AiSettings.Connection("id", "n", Provider.OPENROUTER, "", "x").effort)
        // "Apagado" guardado, pero este modelo no se puede apagar → Bajo
        assertEquals("low", AiSettings.effectiveEffort(conn(Provider.ANTHROPIC, "claude-opus-5-5", EFFORT_OFF)))
        // el modelo no deja elegir nada → automático
        assertEquals("", AiSettings.effectiveEffort(conn(Provider.GROQ, "llama-3.3-70b-versatile", "high")))
        assertEquals(EFFORT_OFF, AiSettings.effectiveEffort(conn(Provider.OPENROUTER, "x", EFFORT_OFF)))
    }
}
