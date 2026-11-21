package dev.rustdroid.ide

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.runtime.ConsoleClassifier
import dev.rustdroid.ide.runtime.ConsoleKind
import dev.rustdroid.ide.runtime.ConsoleKind.ERROR
import dev.rustdroid.ide.runtime.ConsoleKind.NOISE
import dev.rustdroid.ide.runtime.ConsoleKind.OUTPUT
import dev.rustdroid.ide.runtime.ConsoleKind.STATUS
import dev.rustdroid.ide.runtime.ConsoleKind.STDERR
import dev.rustdroid.ide.runtime.ConsoleKind.SYSTEM
import dev.rustdroid.ide.runtime.ConsoleKind.WARNING
import dev.rustdroid.ide.runtime.DiagnosticsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleClassifierTest {

    private fun err(text: String) = ConsoleLine(Stream.STDERR, text, 0)
    private fun out(text: String) = ConsoleLine(Stream.STDOUT, text, 0)
    private fun sys(text: String) = ConsoleLine(Stream.SYSTEM, text, 0)

    private fun kinds(vararg lines: ConsoleLine): List<ConsoleKind> =
        ConsoleClassifier.classify(lines.toList())

    @Test
    fun `successful run on a phone - the real log from the moto g54`() {
        val result = kinds(
            sys("$ cargo run"),
            sys("note: this folder is on shared storage (noexec) — binaries are built and run from app storage"),
            err("   Compiling Rust4Android v0.1.0 (/storage/emulated/0/Scripts/Rust4Android)"),
            err("warning: hard linking files in the incremental compilation cache failed. copying files instead. consider moving the cache directory to a file system which supports hard linking in session dir `/data/user/0/dev.rustdroid.ide/files/build/x/debug/incremental/y-working`"),
            err(""),
            err("warning: `Rust4Android` (bin \"Rust4Android\") generated 1 warning"),
            err("    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.44s"),
            err("     Running `/data/user/0/dev.rustdroid.ide/files/build/x/debug/Rust4Android`"),
            out("Hello world!"),
            sys("(exit 0 in 0.614s)"),
        )
        assertEquals(
            listOf(SYSTEM, SYSTEM, STATUS, NOISE, NOISE, WARNING, STATUS, STATUS, OUTPUT, SYSTEM),
            result,
        )
    }

    @Test
    fun `multi-line error keeps its color until the blank line`() {
        val result = kinds(
            err("   Compiling demo v0.1.0 (/data/demo)"),
            err("error[E0308]: mismatched types"),
            err(" --> src/main.rs:2:18"),
            err("  |"),
            err("2 |     let x: i32 = \"hola\";"),
            err("  |            ---   ^^^^^^ expected `i32`, found `&str`"),
            err("  = note: expected type annotation"),
            err(""),
            err("error: could not compile `demo` (bin \"demo\") due to 1 previous error"),
        )
        assertEquals(
            listOf(STATUS, ERROR, ERROR, ERROR, ERROR, ERROR, ERROR, ERROR, ERROR),
            result,
        )
    }

    @Test
    fun `warning block then status line ends the block`() {
        val result = kinds(
            err("warning: unused variable: `y`"),
            err(" --> src/main.rs:3:9"),
            err("  |"),
            err("3 |     let y = 5;"),
            err("    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.2s"),
        )
        assertEquals(listOf(WARNING, WARNING, WARNING, WARNING, STATUS), result)
    }

    @Test
    fun `error continuation with capitalized words is not mistaken for status`() {
        val result = kinds(
            err("error: process didn't exit successfully: `rustc -vV` (exit status: 255)"),
            err("--- stderr"),
            err("  Permission denied (os error 13)"),
        )
        assertEquals(listOf(ERROR, ERROR, ERROR), result)
    }

    @Test
    fun `program eprintln stays plain stderr`() {
        val result = kinds(
            err("     Running `target/debug/demo`"),
            out("calculando..."),
            err("algo salió mal: archivo no encontrado"),
        )
        assertEquals(listOf(STATUS, OUTPUT, STDERR), result)
    }

    @Test
    fun `hard-link fallback is left out of Problems`() {
        val diags = DiagnosticsParser.parseAll(
            listOf(
                "warning: hard linking files in the incremental compilation cache failed. copying files instead.",
                "",
                "warning: unused variable: `y`",
                " --> src/main.rs:3:9",
            )
        )
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.startsWith("unused variable"))
    }
}
