package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.GameBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameBuildTest {

    private val toml = """
        [package]
        name = "mi-juego"
        version = "0.1.0"
        edition = "2021"

        [dependencies]
        macroquad = { version = "0.4", default-features = false }
    """.trimIndent()

    @Test
    fun `detects macroquad in its usual spellings`() {
        assertTrue(GameBuild.isGame(toml))
        assertTrue(GameBuild.isGame("[dependencies.macroquad]\nversion = \"0.4\""))
        assertFalse(GameBuild.isGame("[dependencies]\nrand = \"0.8\"\n# macroquad = later"))
        assertFalse(GameBuild.isGame("[dependencies]\nmacroquad-extra = \"1\""))
    }

    @Test
    fun `lib manifest adds cdylib and the fontdue pin`() {
        val out = GameBuild.libManifest(toml)!!
        assertTrue(out.startsWith(toml))
        assertTrue(out.contains("[dependencies.fontdue]\nversion = \"=0.9.3\""))
        assertTrue(out.contains("[lib]\ncrate-type = [\"cdylib\"]\npath = \"src/main.rs\""))
    }

    @Test
    fun `an existing fontdue or lib section is respected`() {
        val withFontdue = toml + "\nfontdue = \"0.9.2\"\n"
        assertFalse(GameBuild.libManifest(withFontdue)!!.contains("dependencies.fontdue"))
        assertNull(GameBuild.libManifest(toml + "\n[lib]\nname = \"x\"\n"))
    }

    @Test
    fun `entry point is appended once`() {
        val main = "fn main() {}\n"
        val out = GameBuild.withEntry(main)
        assertTrue(out.contains("pub extern \"C\" fn quad_main()"))
        assertEquals(out, GameBuild.withEntry(out))
    }

    @Test
    fun `library file name follows cargo`() {
        assertEquals("libmi_juego.so", GameBuild.libFileName(toml))
        assertNull(GameBuild.libFileName("[dependencies]\n"))
    }
}
