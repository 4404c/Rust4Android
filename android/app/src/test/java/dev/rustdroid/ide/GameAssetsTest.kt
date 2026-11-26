package dev.rustdroid.ide

import dev.rustdroid.ide.export.GameAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class GameAssetsTest {
    @Test
    fun collectsBothFoldersRootWins() {
        val dir = Files.createTempDirectory("ga").toFile()
        File(dir, "src/assets/sub").mkdirs()
        File(dir, "assets").mkdirs()
        File(dir, "src/assets/a.png").writeText("src")
        File(dir, "src/assets/sub/b.png").writeText("b")
        File(dir, "assets/a.png").writeText("root")
        val got = GameAssets.collect(dir)
        assertEquals(listOf("a.png", "sub/b.png"), got.keys.toList())
        assertEquals("root", got.getValue("a.png").readText())

        val zip = File(dir, "out/assets.zip")
        GameAssets.writeZip(got, zip)
        ZipFile(zip).use { z ->
            assertEquals(setOf("assets/a.png", "assets/sub/b.png"), z.entries().toList().map { it.name }.toSet())
        }
        assertFalse(GameAssets.writeZip(emptyMap(), zip))
        assertFalse(zip.exists())
        dir.deleteRecursively()
    }
}
