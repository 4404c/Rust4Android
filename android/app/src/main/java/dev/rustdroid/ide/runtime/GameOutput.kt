package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Stream
import java.io.ByteArrayOutputStream

/**
 * R4A: turns the bytes a running game writes (stdout and stderr share one
 * file) into Console lines. A panic block ("thread '…' panicked at …" up to
 * the RUST_BACKTRACE note or a blank line) is marked as stderr so it shows
 * like a panic of a normal `cargo run`; everything else is program output.
 * Bytes are split on '\n' before decoding, so UTF-8 characters cut between
 * two reads are not broken. Pure — unit-tested on the JVM.
 */
class GameOutput {

    private val partial = ByteArrayOutputStream()
    private var inPanic = false

    /** Complete lines found in [bytes] (the rest waits for more data). */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ConsoleLine> {
        val out = ArrayList<ConsoleLine>()
        for (i in 0 until length) {
            val b = bytes[i]
            if (b == '\n'.code.toByte()) {
                val text = partial.toString(Charsets.UTF_8.name()).trimEnd('\r')
                partial.reset()
                // Android noise while a crashing game process goes down
                if (!NOISE.containsMatchIn(text)) out += line(text)
            } else {
                partial.write(b.toInt())
            }
        }
        return out
    }

    /** Whatever is left without a final newline (the game ended). */
    fun flush(): List<ConsoleLine> {
        if (partial.size() == 0) return emptyList()
        val text = partial.toString(Charsets.UTF_8.name())
        partial.reset()
        return listOf(line(text))
    }

    private fun line(text: String): ConsoleLine {
        if (PANIC.containsMatchIn(text)) inPanic = true
        val stream = if (inPanic) Stream.STDERR else Stream.STDOUT
        if (inPanic && (text.isBlank() || text.startsWith("note: run with"))) inPanic = false
        return ConsoleLine(stream, text)
    }

    private companion object {
        val PANIC = Regex("""^thread '.*' panicked at""")
        val NOISE = Regex("""^FORTIFY: pthread_mutex_\w+ called on a destroyed mutex""")
    }
}
