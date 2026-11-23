package dev.rustdroid.ide

import dev.rustdroid.ide.projects.FolderBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FolderBrowserTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `lists visible folders sorted, without files`() {
        val root = tmp.newFolder("storage")
        File(root, "Scripts").mkdir()
        File(root, "android").mkdir()
        File(root, ".oculta").mkdir()
        File(root, "nota.txt").writeText("x")
        assertEquals(listOf("android", "Scripts"), FolderBrowser.folders(root).map { it.name })
    }

    @Test
    fun `breadcrumb goes from the root to the folder`() {
        val root = tmp.newFolder("storage")
        val deep = File(root, "Scripts/R4A RUST PRUEBAS").apply { mkdirs() }
        assertEquals(
            listOf("storage", "Scripts", "R4A RUST PRUEBAS"),
            FolderBrowser.crumbs(root, deep).map { it.name },
        )
        assertTrue(FolderBrowser.canGoUp(root, deep))
        assertFalse(FolderBrowser.canGoUp(root, root))
    }

    @Test
    fun `creates folders and refuses bad names`() {
        val root = tmp.newFolder("storage")
        assertTrue(FolderBrowser.createFolder(root, "Mi juego").isDirectory)
        for (bad in listOf("", "  ", "..", "a/b", "Mi juego")) {
            try {
                FolderBrowser.createFolder(root, bad)
                throw AssertionError("expected '$bad' to be refused")
            } catch (_: IOException) {
            }
        }
    }
}
