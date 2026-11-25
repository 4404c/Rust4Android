package dev.rustdroid.ide

import dev.rustdroid.ide.ai.ProjectTools
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.runtime.LiveDiagnostic
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * R4A (auditoría M3): las herramientas de la IA nunca leen ni escriben fuera
 * del proyecto, ni con "./" repetidos ni a través de un symlink.
 */
class AiToolPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeBridge(override val projectDir: File) : ProjectTools.Bridge {
        override fun tree(): List<FileNode> = emptyList()
        override fun bufferText(relativePath: String): String? = null
        override fun activeFile(): String? = null
        override fun unsavedFiles(): List<String> = emptyList()
        override fun runProblems(): List<Diagnostic> = emptyList()
        override fun liveProblems(): List<LiveDiagnostic> = emptyList()
        override fun onExternalWrite(relativePath: String, content: String?) {}
        override suspend fun cargoCheck(offline: Boolean) = null
        override suspend fun runProgram(onLine: (String) -> Unit) = null
        override fun isGame() = false
    }

    private fun setup(): Pair<File, ProjectTools> {
        val project = tmp.newFolder("proj")
        File(project, "Cargo.toml").writeText("[package]\nname = \"proj\"\n")
        File(project, "src").mkdirs()
        File(project, "src/main.rs").writeText("fn main() {}\n")
        return project to ProjectTools(FakeBridge(project))
    }

    private fun args(vararg kv: Pair<String, String>) =
        kv.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    @Test
    fun `normalizeRelative folds dots and separators and rejects escapes`() {
        assertEquals("a/b/c", Fs.normalizeRelative("./a//b/./c"))
        assertEquals("src/main.rs", Fs.normalizeRelative("././src\\main.rs"))
        assertEquals(".git/config", Fs.normalizeRelative("././.git/config"))
        for (bad in listOf("", "   ", "./", "/etc/passwd", "\\x", "src/../../x", "..")) {
            try {
                Fs.normalizeRelative(bad)
                throw AssertionError("should reject '$bad'")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun `dot prefixes no longer sneak past the git and Cargo toml guards`() = runBlocking {
        val (project, tools) = setup()
        File(project, ".git").mkdirs()
        val w = tools.execute("write_file", args("path" to "././.git/config", "content" to "x"))
        assertTrue(w.result, w.result.startsWith("Error"))
        assertFalse(File(project, ".git/config").exists())

        val d = tools.execute("delete_file", args("path" to "././Cargo.toml"))
        assertTrue(d.result, d.result.startsWith("Error"))
        assertTrue(File(project, "Cargo.toml").exists())
    }

    @Test
    fun `normal relative paths still work`() = runBlocking {
        val (project, tools) = setup()
        val w = tools.execute("write_file", args("path" to "./src/extra.rs", "content" to "pub fn x() {}"))
        assertTrue(w.result, w.result.startsWith("Listo"))
        assertEquals("pub fn x() {}", File(project, "src/extra.rs").readText())
        val r = tools.execute("read_file", args("path" to "src/extra.rs"))
        assertEquals("pub fn x() {}", r.result)
    }

    // R4A (auditoría M7): binarios fuera del alcance de write/edit/delete
    @Test
    fun `binary files are never overwritten edited or deleted`() = runBlocking {
        val (project, tools) = setup()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13, 1, 2, 3)
        File(project, "assets").mkdirs()
        val img = File(project, "assets/ball.png").apply { writeBytes(png) }

        for ((tool, a) in listOf(
            "write_file" to args("path" to "assets/ball.png", "content" to "texto"),
            "edit_file" to args("path" to "assets/ball.png", "old_text" to "PNG", "new_text" to "X"),
            "delete_file" to args("path" to "assets/ball.png"),
        )) {
            val out = tools.execute(tool, a)
            assertTrue("$tool: ${out.result}", out.result.startsWith("Error") && out.result.contains("binario"))
            assertTrue(out.change == null)
        }
        assertTrue(img.exists())
        assertTrue(png.contentEquals(img.readBytes()))

        // un archivo de texto se sigue pudiendo borrar (y deshacer)
        val d = tools.execute("delete_file", args("path" to "src/main.rs"))
        assertTrue(d.result, d.result.startsWith("Listo"))
        assertEquals("fn main() {}\n", d.change?.before)
    }

    @Test
    fun `a symlink inside the project cannot be used to read or write outside`() = runBlocking {
        val (project, tools) = setup()
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("SECRET")
        val made = runCatching {
            Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("symlinks not available here (Windows without privilege)", made)

        val r = tools.execute("read_file", args("path" to "link/secret.txt"))
        assertTrue(r.result, r.result.startsWith("Error"))
        assertFalse(r.result.contains("SECRET"))

        val w = tools.execute("write_file", args("path" to "link/new.txt", "content" to "x"))
        assertTrue(w.result, w.result.startsWith("Error"))
        assertFalse(File(outside, "new.txt").exists())
    }
}
