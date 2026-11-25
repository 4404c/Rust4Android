package dev.rustdroid.ide

import dev.rustdroid.ide.ai.ProjectTools
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.RunResult
import dev.rustdroid.ide.runtime.LiveDiagnostic
import dev.rustdroid.ide.runtime.LiveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R4A (auditoría M4): antes de bajar crates nuevas que agregó la IA, o de
 * ejecutar el programa, se le pregunta al usuario.
 */
class AiApprovalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingBridge(override val projectDir: File) : ProjectTools.Bridge {
        val checks = mutableListOf<Boolean>() // offline de cada cargo_check
        var runs = 0
        override fun tree(): List<FileNode> = emptyList()
        override fun bufferText(relativePath: String): String? = null
        override fun activeFile(): String? = null
        override fun unsavedFiles(): List<String> = emptyList()
        override fun runProblems(): List<Diagnostic> = emptyList()
        override fun liveProblems(): List<LiveDiagnostic> = emptyList()
        override fun onExternalWrite(relativePath: String, content: String?) {}
        override suspend fun cargoCheck(offline: Boolean): LiveResult {
            checks += offline
            return LiveResult(emptyList(), emptyMap())
        }
        override suspend fun runProgram(onLine: (String) -> Unit): RunResult {
            runs++
            onLine("hola")
            return RunResult(0, false, 1)
        }
        override fun isGame() = false
    }

    private fun project(): File = tmp.newFolder("p").also {
        File(it, "Cargo.toml").writeText("[package]\nname = \"p\"\n\n[dependencies]\nrand = \"0.8\"\n")
    }

    private fun addDep(tools: ProjectTools) = runBlocking {
        tools.execute(
            "write_file",
            """{"path":"Cargo.toml","content":"[package]\nname = \"p\"\n\n[dependencies]\nrand = \"0.8\"\nevil = \"1\"\n"}""",
        )
    }

    @Test
    fun `known dependencies compile online without asking`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; true }
        tools.execute("cargo_check", "{}")
        assertEquals(0, asked)
        assertEquals(listOf(false), bridge.checks)
    }

    @Test
    fun `a new dependency is not downloaded without permission`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        val questions = mutableListOf<String>()
        tools.approver = { questions += it; false }
        addDep(tools)
        val out = tools.execute("cargo_check", "{}")
        assertTrue(questions.single().contains("evil"))
        assertEquals(listOf(true), bridge.checks) // sin internet
        assertTrue(out.result, out.result.contains("NO permitió") && out.result.contains("evil"))
    }

    @Test
    fun `once allowed the dependency compiles online and is not asked again`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; true }
        addDep(tools)
        tools.execute("cargo_check", "{}")
        tools.execute("cargo_check", "{}")
        assertEquals(1, asked)
        assertEquals(listOf(false, false), bridge.checks)
    }

    @Test
    fun `plan mode checks offline and never asks`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; true }
        addDep(tools)
        tools.planMode = true
        tools.execute("cargo_check", "{}")
        assertEquals(0, asked)
        assertEquals(listOf(true), bridge.checks)
    }

    @Test
    fun `run_program asks once and does not run when denied`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        tools.approver = { false }
        val denied = tools.execute("run_program", "{}")
        assertEquals(0, bridge.runs)
        assertFalse(denied.result.contains("hola"))

        var asked = 0
        tools.approver = { asked++; true }
        tools.execute("run_program", "{}")
        tools.execute("run_program", "{}")
        assertEquals(1, asked)
        assertEquals(2, bridge.runs)
    }
}
