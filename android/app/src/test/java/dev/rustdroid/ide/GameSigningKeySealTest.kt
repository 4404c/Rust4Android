package dev.rustdroid.ide

import dev.rustdroid.ide.export.GameSigningKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R4A (alcance de archivos): la clave que firma los juegos, cifrada en disco. */
class GameSigningKeySealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Un "Keystore" de juguete: XOR con 0x5A y una marca adelante. */
    private class FakeSealer : GameSigningKey.Sealer {
        var broken = false
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            check(!broken) { "keystore broken" }
            check(sealed[0] == 7.toByte())
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @After
    fun reset() { GameSigningKey.sealer = null }

    @Test
    fun `an old plain key is sealed and the plain file removed, same key after`() {
        val dir = tmp.newFolder("signing")
        GameSigningKey.sealer = null
        val original = GameSigningKey.loadOrCreate(dir)
        assertTrue(File(dir, "r4a-games.pk8").isFile)

        GameSigningKey.sealer = FakeSealer()
        val loaded = GameSigningKey.loadOrCreate(dir)

        assertArrayEquals(original.privateKey.encoded, loaded.privateKey.encoded)
        assertFalse(File(dir, "r4a-games.pk8").exists())
        assertTrue(File(dir, "r4a-games.pk8.sealed").isFile)
        assertFalse(File(dir, "r4a-games.pk8.sealed").readBytes().contentEquals(original.privateKey.encoded))
        assertTrue(GameSigningKey.exists(dir))
        // y se sigue abriendo
        assertArrayEquals(original.privateKey.encoded, GameSigningKey.loadOrCreate(dir).privateKey.encoded)
    }

    @Test
    fun `a new key is saved sealed`() {
        val dir = tmp.newFolder("signing2")
        GameSigningKey.sealer = FakeSealer()
        GameSigningKey.loadOrCreate(dir)
        assertFalse(File(dir, "r4a-games.pk8").exists())
        assertTrue(File(dir, "r4a-games.pk8.sealed").isFile)
    }

    @Test
    fun `backup and restore keep the same key with a sealed store`() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        GameSigningKey.sealer = FakeSealer()
        val key = GameSigningKey.loadOrCreate(a)
        val backup = GameSigningKey.backup(a, "contraseña1".toCharArray())
        val restored = GameSigningKey.restore(b, backup, "contraseña1".toCharArray())
        assertArrayEquals(key.privateKey.encoded, restored.privateKey.encoded)
        assertTrue(File(b, "r4a-games.pk8.sealed").isFile)
        assertFalse(File(b, "r4a-games.pk8").exists())
        assertEquals(GameSigningKey.fingerprint(key), GameSigningKey.fingerprint(GameSigningKey.loadOrCreate(b)))
    }

    @Test
    fun `a sealed key that can not be opened is never replaced by a new one`() {
        val dir = tmp.newFolder("signing3")
        val sealer = FakeSealer()
        GameSigningKey.sealer = sealer
        GameSigningKey.loadOrCreate(dir)
        val before = File(dir, "r4a-games.pk8.sealed").readBytes()

        sealer.broken = true
        assertTrue(runCatching { GameSigningKey.loadOrCreate(dir) }.isFailure)
        assertArrayEquals(before, File(dir, "r4a-games.pk8.sealed").readBytes())
    }
}
