package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectLeftovers
import dev.rustdroid.ide.runtime.ProcEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R4A (auditoría B18): lo que queda de un proyecto fuera de su carpeta. */
class ProjectLeftoversTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seed(files: File, canonical: String) {
        ProjectLeftovers.caches(files, canonical).forEach { File(it, "x/y.bin").apply { parentFile.mkdirs(); writeText("z") } }
        File(ProjectLeftovers.chatsDir(files, canonical), "1.json").apply { parentFile.mkdirs(); writeText("{}") }
        ProjectLeftovers.exportIcon(files, canonical).apply { parentFile.mkdirs(); writeText("png") }
    }

    @Test
    fun `build key matches the cargo target redirect`() {
        val files = tmp.newFolder("files")
        val external = tmp.newFolder("sdcard", "Proyecto")
        val canonical = ProjectLeftovers.canonical(external)
        assertEquals(ProcEnv.redirectedTargetDir(files, external), ProjectLeftovers.caches(files, canonical)[2])
    }

    @Test
    fun `delete removes caches and user data of that project only`() {
        val files = tmp.newFolder("files")
        val a = File(files, "projects/a").canonicalPath
        val b = File(files, "projects/b").canonicalPath
        seed(files, a); seed(files, b)
        ProjectLeftovers.delete(files, a, userData = true)
        ProjectLeftovers.caches(files, a).forEach { assertFalse(it.exists()) }
        assertFalse(ProjectLeftovers.chatsDir(files, a).exists())
        assertFalse(ProjectLeftovers.exportIcon(files, a).exists())
        ProjectLeftovers.caches(files, b).forEach { assertTrue(it.exists()) }
        assertTrue(ProjectLeftovers.chatsDir(files, b).exists())
    }

    @Test
    fun `removing an external folder keeps its chats`() {
        val files = tmp.newFolder("files")
        val c = "/storage/emulated/0/Scripts/X"
        seed(files, c)
        ProjectLeftovers.delete(files, c, userData = false)
        ProjectLeftovers.caches(files, c).forEach { assertFalse(it.exists()) }
        assertTrue(ProjectLeftovers.chatsDir(files, c).exists())
    }

    @Test
    fun `rename moves chats and icon to the new key`() {
        val files = tmp.newFolder("files")
        val old = File(files, "projects/viejo").canonicalPath
        val new = File(files, "projects/nuevo").canonicalPath
        seed(files, old)
        ProjectLeftovers.move(files, old, new)
        assertTrue(File(ProjectLeftovers.chatsDir(files, new), "1.json").isFile)
        assertFalse(ProjectLeftovers.chatsDir(files, old).exists())
        assertTrue(ProjectLeftovers.exportIcon(files, new).isFile)
        ProjectLeftovers.caches(files, old).forEach { assertFalse(it.exists()) }
    }
}
