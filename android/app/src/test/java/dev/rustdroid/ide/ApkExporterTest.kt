package dev.rustdroid.ide

import com.android.apksig.ApkVerifier
import dev.rustdroid.ide.export.ApkExporter
import dev.rustdroid.ide.export.BinaryManifest
import dev.rustdroid.ide.export.GameSigningKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class ApkExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val template = javaClass.classLoader!!.getResourceAsStream("gameplayer-template.apk")!!.readBytes()

    @Test
    fun `exports a signed apk with the game and the new identity`() {
        val lib = tmp.newFile("libjuego.so").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val keyDir = tmp.newFolder("keys")
        val key = GameSigningKey.loadOrCreate(keyDir, "Naza")
        val out = java.io.File(tmp.root, "out/Pelotita.apk")
        val icon = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        ApkExporter.export(
            template, lib,
            ApkExporter.Options("Pelotita", "com.naza.pelotita", 4, "1.3"),
            key, tmp.newFolder("work"), out, icon,
        )

        val result = ApkVerifier.Builder(out).build().verify()
        assertTrue(result.errors.toString(), result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)

        ZipFile(out).use { z ->
            assertEquals(4096L, z.getEntry("lib/arm64-v8a/libgame.so").size)
            assertEquals(java.util.zip.ZipEntry.STORED, z.getEntry("resources.arsc").method)
            // the launcher icon is the one we passed
            val icons = z.entries().toList().filter { ApkExporter.ICON_ENTRY.matches(it.name) }
            assertEquals(1, icons.size)
            assertTrue(z.getInputStream(icons[0]).readBytes().contentEquals(icon))
            val manifest = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            val s = BinaryManifest.strings(manifest)
            assertTrue("com.naza.pelotita" in s)
            assertTrue("Pelotita" in s)
            assertFalse(ApkExporter.TEMPLATE_PACKAGE in s)
            assertEquals(4, BinaryManifest.intAttribute(manifest, "versionCode"))
        }

        // the same key is reused next time (updates install over the old app)
        val again = GameSigningKey.loadOrCreate(keyDir)
        assertEquals(key.certificate, again.certificate)
    }

    @Test
    fun `a key backup restores the same key elsewhere`() {
        val dirA = tmp.newFolder("a")
        val original = GameSigningKey.loadOrCreate(dirA)
        val backup = GameSigningKey.backup(dirA)

        val dirB = tmp.newFolder("b")
        GameSigningKey.loadOrCreate(dirB) // a different key that gets replaced
        GameSigningKey.restore(dirB, backup)
        val restored = GameSigningKey.loadOrCreate(dirB)
        assertEquals(original.certificate, restored.certificate)
        assertEquals(GameSigningKey.fingerprint(original), GameSigningKey.fingerprint(restored))

        // garbage is rejected and the current key stays
        val bad = runCatching { GameSigningKey.restore(dirB, "hola".toByteArray()) }
        assertTrue(bad.isFailure)
        assertEquals(original.certificate, GameSigningKey.loadOrCreate(dirB).certificate)
    }

    @Test
    fun `package names follow android rules`() {
        assertTrue(ApkExporter.isValidPackage("com.naza.pelotita"))
        assertFalse(ApkExporter.isValidPackage("pelotita"))
        assertFalse(ApkExporter.isValidPackage("com.2juego"))
        assertFalse(ApkExporter.isValidPackage("com.class.juego"))
        assertFalse(ApkExporter.isValidPackage("com.mi-juego"))
        assertEquals("com.r4a.mi_juego", ApkExporter.suggestPackage("Mi-Juego"))
        assertEquals("com.r4a.juego_2048", ApkExporter.suggestPackage("2048"))
    }
}
