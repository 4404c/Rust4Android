package dev.rustdroid.ide

import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.runtime.GameOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOutputTest {

    @Test
    fun `lines are split and partial lines wait`() {
        val g = GameOutput()
        assertEquals(listOf("vida: 3"), g.feed("vida: 3\nvid".toByteArray()).map { it.text })
        assertEquals(listOf("vida: 2"), g.feed("a: 2\n".toByteArray()).map { it.text })
        assertTrue(g.flush().isEmpty())
        g.feed("sin fin".toByteArray())
        assertEquals(listOf("sin fin"), g.flush().map { it.text })
    }

    @Test
    fun `utf8 cut between reads stays whole`() {
        val g = GameOutput()
        val bytes = "ñandú\n".toByteArray()
        val first = g.feed(bytes.copyOfRange(0, 1))
        val second = g.feed(bytes.copyOfRange(1, bytes.size))
        assertTrue(first.isEmpty())
        assertEquals("ñandú", second.single().text)
    }

    @Test
    fun `fortify noise of a dying process is hidden`() {
        val g = GameOutput()
        val lines = g.feed(
            ("index out of bounds\n" +
                "FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x7b36dc4978)\n").toByteArray()
        )
        assertEquals(listOf("index out of bounds"), lines.map { it.text })
    }

    @Test
    fun `a panic block is stderr, normal output is stdout`() {
        val g = GameOutput()
        val lines = g.feed(
            ("antes\n" +
                "thread '<unnamed>' panicked at src/main.rs:12:9:\n" +
                "index out of bounds\n" +
                "note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace\n" +
                "despues\n").toByteArray()
        )
        assertEquals(
            listOf(Stream.STDOUT, Stream.STDERR, Stream.STDERR, Stream.STDERR, Stream.STDOUT),
            lines.map { it.stream },
        )
    }
}
