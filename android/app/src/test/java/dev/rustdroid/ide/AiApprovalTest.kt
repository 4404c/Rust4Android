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
 * R4A (auditoría M4 y auditoría 2, S1): antes de compilar con cambios de la
 * IA en cómo se compila (crates, build.rs, .cargo/…), o de ejecutar el
 * programa, se le pregunta al usuario.
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

    private fun write(tools: ProjectTools, path: String, content: String) = runBlocking {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive(path))
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
        }
        tools.execute("write_file", body.toString())
    }

    private fun addDep(tools: ProjectTools) =
        write(tools, "Cargo.toml", "[package]\nname = \"p\"\n\n[dependencies]\nrand = \"0.8\"\nevil = \"1\"\n")

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
    fun `a new dependency is not compiled without permission`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        val questions = mutableListOf<ProjectTools.Ask>()
        tools.approver = { questions += it; false }
        addDep(tools)
        val out = tools.execute("cargo_check", "{}")
        val q = questions.single() as ProjectTools.Ask.Build
        assertTrue("Cargo.toml" in q.files)
        // R4A: dice qué crates nuevas se van a compilar (rand ya estaba)
        assertEquals(listOf("evil"), q.newCrates)
        // ni siquiera sin internet: un build.rs correría igual
        assertEquals(emptyList<Boolean>(), bridge.checks)
        assertTrue(out.result, out.result.contains("NO permitió"))
    }

    @Test
    fun `once allowed it compiles online and is not asked again`() = runBlocking {
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
    fun `plan mode asks too and checks offline`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; true }
        addDep(tools)
        tools.planMode = true
        tools.execute("cargo_check", "{}")
        assertEquals(1, asked)
        assertEquals(listOf(true), bridge.checks)
    }

    /** R4A (auditoría 2, S1): lo que antes pasaba sin preguntar. */
    @Test
    fun `every way to change what runs at build time asks`() = runBlocking {
        val cases = listOf(
            "Cargo.toml" to "[package]\nname = \"p\"\n\n[dependencies]\nrand = { git = \"https://example.invalid/rand\" }\n",
            "Cargo.toml" to "[package]\nname = \"p\"\n\n[target.'cfg(unix)'.dependencies]\nevil = \"1\"\n",
            "Cargo.toml" to "[package]\nname = \"p\"\n\n[patch.crates-io]\nrand = { path = \"x\" }\n",
            ".cargo/config.toml" to "[target.aarch64-linux-android]\nlinker = \"sh\"\n",
            "build.rs" to "fn main() {}\n",
            "member/src/lib.rs" to "pub fn x() {}\n",
            "Cargo.lock" to "# changed\n",
        )
        for ((path, content) in cases) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val bridge = RecordingBridge(project())
            val tools = ProjectTools(bridge)
            var asked = 0
            tools.approver = { asked++; false }
            write(tools, path, content)
            tools.execute("cargo_check", "{}")
            assertEquals(path, 1, asked)
            assertEquals(path, emptyList<Boolean>(), bridge.checks)
        }
    }

    @Test
    fun `ordinary code changes do not ask`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; true }
        write(tools, "src/main.rs", "fn main() { println!(\"hola\"); }\n")
        write(tools, "src/util/mod.rs", "pub fn a() {}\n")
        tools.execute("cargo_check", "{}")
        assertEquals(0, asked)
        assertEquals(listOf(false), bridge.checks)
    }

    @Test
    fun `a denied change is still pending after reopening the project`() = runBlocking {
        val dir = project()
        val store = File(tmp.root, "guard.txt")
        val first = ProjectTools(RecordingBridge(dir), guard = dev.rustdroid.ide.ai.BuildGuard(dir, store))
        first.approver = { false }
        addDep(first)
        first.execute("cargo_check", "{}")

        val bridge = RecordingBridge(dir)
        val reopened = ProjectTools(bridge, guard = dev.rustdroid.ide.ai.BuildGuard(dir, store))
        var asked = 0
        reopened.approver = { asked++; false }
        reopened.execute("cargo_check", "{}")
        assertEquals(1, asked)
        assertEquals(emptyList<Boolean>(), bridge.checks)
    }

    @Test
    fun `undoing the change by hand clears it`() = runBlocking {
        val dir = project()
        val original = File(dir, "Cargo.toml").readText()
        val bridge = RecordingBridge(dir)
        val tools = ProjectTools(bridge)
        var asked = 0
        tools.approver = { asked++; false }
        addDep(tools)
        File(dir, "Cargo.toml").writeText(original) // lo que hace "Deshacer"
        tools.execute("cargo_check", "{}")
        assertEquals(0, asked)
        assertEquals(listOf(false), bridge.checks)
    }

    @Test
    fun `run_program asks about build changes first`() = runBlocking {
        val bridge = RecordingBridge(project())
        val tools = ProjectTools(bridge)
        val questions = mutableListOf<ProjectTools.Ask>()
        tools.approver = { questions += it; false }
        write(tools, "build.rs", "fn main() {}\n")
        tools.execute("run_program", "{}")
        assertEquals(0, bridge.runs)
        assertTrue("build.rs" in (questions.single() as ProjectTools.Ask.Build).files)
    }

    @Test
    fun `sensitive paths`() {
        val s = dev.rustdroid.ide.ai.BuildGuard.Companion::isSensitive
        for (p in listOf("Cargo.toml", "Cargo.lock", "build.rs", "m/Cargo.toml", ".cargo/config.toml", "m/src/lib.rs", "rust-toolchain.toml")) {
            assertTrue(p, s(p))
        }
        for (p in listOf("src/main.rs", "src/a/b.rs", "tests/t.rs", "README.md", "assets/x.png", "main.rs")) {
            assertFalse(p, s(p))
        }
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
