package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** R4A: rename / move inside a project, with the same guards as delete. */
class FileMoveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    private fun project(): File = tmp.newFolder("proj").apply {
        File(this, "Cargo.toml").writeText("[package]\nname = \"p\"\n")
        File(this, "src").mkdirs()
        File(this, "src/main.rs").writeText("fn main() {}")
        File(this, "src/util.rs").writeText("// util")
    }

    private fun refused(block: () -> Unit) {
        try { block() } catch (e: IOException) { return }
        throw AssertionError("expected the move to be refused")
    }

    @Test
    fun `renames a file`() {
        val p = project()
        repo().moveFile(p, "src/util.rs", "src/ayuda.rs")
        assertFalse(File(p, "src/util.rs").exists())
        assertEquals("// util", File(p, "src/ayuda.rs").readText())
    }

    @Test
    fun `moves a file into a new folder`() {
        val p = project()
        repo().moveFile(p, "src/util.rs", "src/juego/util.rs")
        assertTrue(File(p, "src/juego/util.rs").isFile)
    }

    @Test
    fun `moves a whole folder`() {
        val p = project()
        repo().moveFile(p, "src", "codigo")
        assertTrue(File(p, "codigo/main.rs").isFile)
        assertFalse(File(p, "src").exists())
    }

    @Test
    fun `refuses what would break or escape the project`() {
        val p = project()
        refused { repo().moveFile(p, "Cargo.toml", "otro.toml") }
        refused { repo().moveFile(p, "src/util.rs", "src/main.rs") } // exists
        refused { repo().moveFile(p, "src", "src/dentro") } // inside itself
        refused { repo().moveFile(p, "src/util.rs", "../afuera.rs") } // escape
        refused { repo().moveFile(p, "no-existe.rs", "x.rs") }
        assertTrue(File(p, "src/util.rs").isFile)
        assertTrue(File(p, "Cargo.toml").isFile)
    }
}
