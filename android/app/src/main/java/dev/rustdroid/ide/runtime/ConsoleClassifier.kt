package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Stream

/** What a console line *means*, used to pick its color (R4A). */
enum class ConsoleKind {
    /** Program output on stdout. */
    OUTPUT,
    /** Cargo progress: Compiling / Finished / Running / Downloading ... */
    STATUS,
    /** An `error:` / `error[E..]:` diagnostic and its continuation lines. */
    ERROR,
    /** A `warning:` diagnostic and its continuation lines. */
    WARNING,
    /** A standalone `note:` / `help:` block. */
    NOTE,
    /** Known harmless messages that are always present on Android. */
    NOISE,
    /** App-generated status lines ($ cargo run, exit codes, notes). */
    SYSTEM,
    /** Any other stderr text (e.g. the user's own eprintln!). */
    STDERR,
}

/**
 * Messages that cargo always prints on Android and that the user cannot act
 * on. They are shown dimmed and left out of the Problems panel.
 */
object KnownNoise {
    private val fragments = listOf(
        // App data does not allow hard links, so cargo falls back to copying
        // the incremental cache. Result is identical; it only uses more space.
        "hard linking files in the incremental compilation cache failed",
    )

    fun matches(text: String): Boolean = fragments.any { text.contains(it) }
}

/**
 * Classifies console lines by content. rustc diagnostics span several lines
 * (header, `-->` location, source snippet, `= note`), so a header sets the
 * kind for the following stderr lines until a blank line or a cargo status
 * line ends the block. Pure function — unit-tested on the JVM.
 */
object ConsoleClassifier {

    private val statusWords = setOf(
        "Compiling", "Checking", "Finished", "Running", "Downloading",
        "Downloaded", "Updating", "Locking", "Adding", "Removing", "Blocking",
        "Fresh", "Building", "Fetching", "Packaging", "Installing",
        "Installed", "Replacing", "Documenting", "Doc-tests", "Executable",
        "Waiting", "Unpacking", "Dirty", "Ignored", "Summary", "Creating",
        "Created", "Upgrading", "Packaged", "Verifying", "Uploading",
        "Archiving", "Vendoring", "Unpacked", "Scraping",
    )

    // Cargo right-aligns its status verb in a 12-column gutter.
    private val status = Regex("^\\s{0,12}([A-Z][A-Za-z-]+)\\s+\\S.*$")

    // "error[E0308]: ...", "warning: ...", "note: ...", "help: ..."
    private val header = Regex("^(error|warning|note|help)(\\[[A-Za-z0-9_-]+])?:.*$")

    fun classify(lines: List<ConsoleLine>): List<ConsoleKind> {
        var block: ConsoleKind? = null
        return lines.map { line ->
            when (line.stream) {
                Stream.STDOUT -> ConsoleKind.OUTPUT
                Stream.SYSTEM -> ConsoleKind.SYSTEM
                Stream.STDERR -> {
                    val text = Ansi.strip(line.text)
                    val headerMatch = header.matchEntire(text)
                    val statusMatch = status.matchEntire(text)
                    when {
                        text.isBlank() -> {
                            val kind = block ?: ConsoleKind.STDERR
                            block = null
                            kind
                        }
                        headerMatch != null -> {
                            val kind = when {
                                KnownNoise.matches(text) -> ConsoleKind.NOISE
                                else -> when (headerMatch.groupValues[1]) {
                                    "error" -> ConsoleKind.ERROR
                                    "warning" -> ConsoleKind.WARNING
                                    else -> ConsoleKind.NOTE
                                }
                            }
                            block = kind
                            kind
                        }
                        statusMatch != null && statusMatch.groupValues[1] in statusWords -> {
                            block = null
                            ConsoleKind.STATUS
                        }
                        block != null -> block!!
                        else -> ConsoleKind.STDERR
                    }
                }
            }
        }
    }
}
