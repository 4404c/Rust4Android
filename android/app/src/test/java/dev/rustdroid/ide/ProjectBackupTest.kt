package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** R4A: copia de seguridad de un proyecto (.zip) y restaurarla sin pisar nada. */
class ProjectBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projects by lazy { tmp.newFolder("projects") }
    private val chatsRoot by lazy { tmp.newFolder("chats") }
    private val chatsFor: (File) -> File = { File(chatsRoot, it.name) }

    private fun project(): File {
        val dir = File(projects, "pelota").apply { mkdirs() }
        File(dir, "Cargo.toml").writeText("[package]\nname = \"pelota\"\n")
        File(dir, "src").mkdirs()
        File(dir, "src/main.rs").writeText("fn main() {}\n")
        File(dir, "assets").mkdirs()
        File(dir, "assets/pelota.png").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "target/debug").mkdirs()
        File(dir, "target/debug/big").writeText("build output")
        File(dir, "src/target").mkdirs() // solo se saca el target/ de arriba
        File(dir, "src/target/keep.rs").writeText("// mine")
        return dir
    }

    private fun chats(): File = File(tmp.root, "src-chats").apply {
        mkdirs()
        File(this, "1.json").writeText("{\"m\":1}")
        File(this, "1.changes").apply { mkdirs(); File(this, "a").writeText("x") }
    }

    private fun backup(withChats: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ProjectBackup.write(project(), "pelota", if (withChats) chats() else null, out)
        return out.toByteArray()
    }

    private fun names(zip: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) out += (z.nextEntry ?: break).name }
        return out
    }

    @Test
    fun `backup has manifest first, the project without target and the chats`() {
        val n = names(backup(withChats = true))
        assertEquals(ProjectBackup.MANIFEST, n.first())
        assertTrue("project/src/main.rs" in n)
        assertTrue("project/assets/pelota.png" in n)
        assertTrue("project/src/target/keep.rs" in n)
        assertFalse(n.any { it.startsWith("project/target") })
        assertTrue("chats/1.json" in n)
        assertTrue("chats/1.changes/a" in n)
        val info = ProjectBackup.readInfo(ByteArrayInputStream(backup(withChats = true)))
        assertEquals("pelota", info.name)
        assertTrue(info.hasChats)
    }

    @Test
    fun `without chats the backup has none`() {
        val zip = backup(withChats = false)
        assertFalse(names(zip).any { it.startsWith("chats/") })
        assertFalse(ProjectBackup.readInfo(ByteArrayInputStream(zip)).hasChats)
    }

    @Test
    fun `restore under another name copies files and chats`() {
        val zip = backup(withChats = true)
        val dir = ProjectBackup.restore(ByteArrayInputStream(zip), projects, "pelota_2", true, chatsFor)
        assertEquals("fn main() {}\n", File(dir, "src/main.rs").readText())
        assertTrue(File(dir, "assets/pelota.png").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(dir, "target").exists())
        assertEquals("{\"m\":1}", File(chatsRoot, "pelota_2/1.json").readText())
        assertEquals("x", File(chatsRoot, "pelota_2/1.changes/a").readText())
        // el original sigue igual y no quedan temporales
        assertTrue(File(projects, "pelota/target/debug/big").isFile)
        assertEquals(setOf("pelota", "pelota_2"), projects.list()!!.toSet())
    }

    @Test
    fun `restore without chats leaves them out`() {
        val zip = backup(withChats = true)
        ProjectBackup.restore(ByteArrayInputStream(zip), projects, "otra", false, chatsFor)
        assertFalse(File(chatsRoot, "otra").exists())
    }

    @Test
    fun `restore never overwrites an existing project`() {
        val zip = backup(withChats = false)
        File(projects, "pelota/src/main.rs").writeText("mine")
        try {
            ProjectBackup.restore(ByteArrayInputStream(zip), projects, "pelota", false, chatsFor)
            fail("should refuse")
        } catch (_: IOException) {}
        assertEquals("mine", File(projects, "pelota/src/main.rs").readText())
    }

    @Test
    fun `existing chat files are not overwritten`() {
        val zip = backup(withChats = true)
        File(chatsRoot, "nueva").mkdirs()
        File(chatsRoot, "nueva/1.json").writeText("old")
        ProjectBackup.restore(ByteArrayInputStream(zip), projects, "nueva", true, chatsFor)
        assertEquals("old", File(chatsRoot, "nueva/1.json").readText())
        assertEquals("x", File(chatsRoot, "nueva/1.changes/a").readText())
    }

    private fun evilZip(vararg entries: Pair<String, ByteArray>, manifest: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest) {
                z.putNextEntry(ZipEntry(ProjectBackup.MANIFEST))
                z.write("{\"format\":1,\"name\":\"x\",\"chats\":false}".toByteArray())
                z.closeEntry()
            }
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun assertRefused(zip: ByteArray) {
        try {
            ProjectBackup.restore(ByteArrayInputStream(zip), projects, "mal", true, chatsFor)
            fail("should refuse")
        } catch (_: IOException) {}
        // nada a medio hacer
        assertFalse(File(projects, "mal").exists())
        assertFalse(File(projects, ".restore-mal").exists())
        assertFalse(File(tmp.root, "evil").exists())
    }

    @Test
    fun `rejects traversal, absolute paths and odd entries`() {
        val b = "x".toByteArray()
        assertRefused(evilZip("project/../../evil" to b))
        assertRefused(evilZip("project/a/../../../evil" to b))
        assertRefused(evilZip("chats/../../evil" to b))
        assertRefused(evilZip("project//etc/passwd" to b))
        assertRefused(evilZip("project/C:/evil" to b))
        assertRefused(evilZip("project/a\\..\\..\\evil" to b))
        assertRefused(evilZip("other/file" to b))
        assertRefused(evilZip("project/a" to b, "project/./a" to b))
    }

    @Test
    fun `rejects zips that are not R4A backups`() {
        assertRefused(evilZip("project/src/main.rs" to "x".toByteArray(), manifest = false))
        assertRefused("not a zip at all".toByteArray())
        try {
            ProjectBackup.readInfo(ByteArrayInputStream(evilZip("a" to byteArrayOf(), manifest = false)))
            fail("should refuse")
        } catch (_: IOException) {}
    }

    @Test
    fun `stops at the size limit counting real bytes`() {
        // ~1 MB de ceros comprime a casi nada: el tope mira lo que sale, no el encabezado
        val big = ByteArray(1024 * 1024)
        val zip = evilZip(*(0 until 301).map { "project/f$it" to big }.toTypedArray())
        assertTrue(zip.size < 10 * 1024 * 1024)
        assertRefused(zip)
    }

    @Test
    fun `invalid target name is refused`() {
        try {
            ProjectBackup.restore(ByteArrayInputStream(backup(false)), projects, "../x", false, chatsFor)
            fail("should refuse")
        } catch (_: IOException) {}
    }

    @Test
    fun `suggested file name has the date`() {
        val d = java.util.GregorianCalendar(2026, 10, 26).time
        assertEquals("pelota-2026-11-26.zip", ProjectBackup.suggestedFileName("pelota", d))
    }
}
