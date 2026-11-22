package dev.rustdroid.ide

import dev.rustdroid.ide.export.BinaryManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryManifestTest {

    /** The real compiled manifest of the game template (gameplayer module). */
    private val template = javaClass.classLoader!!.getResourceAsStream("template-manifest.bin")!!.readBytes()

    @Test
    fun `reads the template strings`() {
        val s = BinaryManifest.strings(template)
        assertTrue("r4a.player.template" in s)
        assertTrue("R4A Game Template" in s)
        assertTrue("r4a.player.MainActivity" in s)
        assertEquals(1, BinaryManifest.intAttribute(template, "versionCode"))
    }

    @Test
    fun `rewrites package, name, version and keeps the rest`() {
        val out = BinaryManifest.rewrite(
            template,
            mapOf(
                "r4a.player.template" to "com.naza.pelotita",
                "R4A Game Template" to "Pelotita — ñandú", // longer, non-ASCII
                "1.0" to "2.3",
            ),
            versionCode = 7,
        )
        val s = BinaryManifest.strings(out)
        assertTrue("com.naza.pelotita" in s)
        assertTrue("Pelotita — ñandú" in s)
        assertTrue("2.3" in s)
        assertFalse("r4a.player.template" in s)
        // the activity class name must not change
        assertTrue("r4a.player.MainActivity" in s)
        assertEquals(7, BinaryManifest.intAttribute(out, "versionCode"))
        // header size matches the real size
        val size = (out[4].toInt() and 0xff) or ((out[5].toInt() and 0xff) shl 8) or
            ((out[6].toInt() and 0xff) shl 16) or ((out[7].toInt() and 0xff) shl 24)
        assertEquals(out.size, size)
        // everything after the pool is untouched apart from versionCode
        assertEquals(BinaryManifest.strings(template).size, s.size)
    }

    @Test
    fun `no changes gives an equivalent manifest`() {
        val out = BinaryManifest.rewrite(template, emptyMap())
        assertEquals(BinaryManifest.strings(template), BinaryManifest.strings(out))
        assertEquals(1, BinaryManifest.intAttribute(out, "versionCode"))
    }
}
