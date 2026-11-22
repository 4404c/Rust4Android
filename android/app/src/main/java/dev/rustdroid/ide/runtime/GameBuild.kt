package dev.rustdroid.ide.runtime

import java.io.File
import java.security.MessageDigest

/**
 * R4A: "run as app" for macroquad games. A game can not run in the console:
 * it is compiled as a library (.so) and shown by GameActivity. The user's
 * project stays a normal bin crate — the changes Android needs are made on a
 * shadow copy:
 *  - Cargo.toml gets `[lib] crate-type = ["cdylib"]` pointing at main.rs
 *    (and fontdue pinned, see [FONTDUE_PIN]);
 *  - main.rs gets the `quad_main` entry point miniquad calls on Android.
 */
object GameBuild {

    /** The function miniquad's Android glue calls to start the game. */
    // unsafe(...) form: required by edition 2024, accepted by older ones.
    // main() returns right away (miniquad runs the game on its own thread,
    // after installing a panic hook that only writes to logcat). Our hook,
    // installed after it, also prints the panic where Console reads it and
    // closes the game — otherwise it stays frozen with a dead render thread.
    const val ENTRY = "\n\n// R4A: Android entry point (added only in R4A's build copy)\n" +
        "#[unsafe(no_mangle)]\npub extern \"C\" fn quad_main() {\n" +
        "    main();\n" +
        "    let miniquad_hook = std::panic::take_hook();\n" +
        "    std::panic::set_hook(Box::new(move |info| {\n" +
        // same shape as Rust's own message: "thread 'x' panicked at file:line:col:"
        "        let name = std::thread::current().name().unwrap_or(\"<unnamed>\").to_string();\n" +
        "        eprintln!(\"thread '{name}' {info}\");\n" +
        "        miniquad_hook(info);\n" +
        // _exit, not process::exit: skipping the atexit cleanup avoids
        // "FORTIFY: pthread_mutex_lock called on a destroyed mutex" noise
        // from the game's other threads
        "        unsafe extern \"C\" { fn _exit(code: i32) -> !; }\n" +
        "        unsafe { _exit(101) }\n" +
        "    }));\n" +
        "}\n"

    /**
     * fontdue 0.9.4+ (pulled by macroquad) uses a Rust 1.87 API without
     * declaring it, so the MSRV-aware resolver can not avoid it. 0.9.3 works.
     */
    const val FONTDUE_PIN = "=0.9.3"

    private val MACROQUAD_DEP = Regex("""(?m)^\s*(macroquad\s*=|\[(target\..*\.)?dependencies\.macroquad])""")
    private val LIB_TABLE = Regex("""(?m)^\s*\[lib]""")
    private val PACKAGE_NAME = Regex("""(?ms)^\s*\[package].*?^\s*name\s*=\s*"([^"]+)"""")

    /** Does this manifest depend on macroquad? Then ▶ runs it as an app. */
    fun isGame(cargoToml: String): Boolean = MACROQUAD_DEP.containsMatchIn(cargoToml)

    /**
     * The manifest for the build copy, or null when the project already
     * has its own `[lib]` (not supported yet).
     */
    fun libManifest(cargoToml: String): String? {
        if (LIB_TABLE.containsMatchIn(cargoToml)) return null
        // main.rs becomes the lib: stop cargo from also seeing it as a bin
        // ("found to be present in multiple build targets" warning)
        val noBins = Regex("""(?m)^(\s*\[package]\s*)$""").replaceFirst(cargoToml, "$1\nautobins = false")
        val sb = StringBuilder(noBins.trimEnd()).append("\n")
        if (!Regex("""(?m)^\s*fontdue\s*=|dependencies\.fontdue]""").containsMatchIn(cargoToml)) {
            sb.append("\n# R4A: newer fontdue needs Rust 1.87\n")
            sb.append("[dependencies.fontdue]\nversion = \"$FONTDUE_PIN\"\n")
        }
        sb.append("\n[lib]\ncrate-type = [\"cdylib\"]\npath = \"src/main.rs\"\n")
        return sb.toString()
    }

    /** main.rs plus the Android entry point (unless the user wrote one). */
    fun withEntry(mainRs: String): String =
        if (mainRs.contains("fn quad_main")) mainRs else mainRs.trimEnd() + ENTRY

    /** File name cargo gives the library: lib<name with - as _>.so */
    fun libFileName(cargoToml: String): String? =
        PACKAGE_NAME.find(cargoToml)?.groupValues?.get(1)?.let { "lib${it.replace('-', '_')}.so" }

    /** Where a game build of one project lives. */
    class Prepared(val dir: File, val targetDir: File, val library: File)

    /**
     * Sync [projectDir] into app storage and apply the Android changes.
     * Throws [IllegalStateException] with a user-facing reason when the
     * project can not be built as a game.
     */
    fun prepare(projectDir: File, filesDir: File): Prepared {
        val manifest = File(projectDir, "Cargo.toml").readText()
        val main = File(projectDir, "src/main.rs")
        check(main.isFile) { "src/main.rs" }
        val libToml = libManifest(manifest) ?: error("[lib]")
        val libName = libFileName(manifest) ?: error("[package] name")

        val root = File(filesDir, "games/${key(projectDir)}")
        val copy = File(root, "src-copy")
        val skip = setOf("Cargo.toml", "src/main.rs", "Cargo.lock")
        ShadowCopy.sync(projectDir, copy, skip = skip)
        ShadowCopy.writeIfChanged(File(copy, "Cargo.toml"), libToml)
        ShadowCopy.writeIfChanged(File(copy, "src/main.rs"), withEntry(main.readText()))
        // the copy keeps its own lock (the fontdue pin lives there); start
        // from the project's one so versions match what the user tested
        val lock = File(projectDir, "Cargo.lock")
        val copyLock = File(copy, "Cargo.lock")
        if (!copyLock.isFile && lock.isFile) lock.copyTo(copyLock)

        val target = File(root, "target")
        return Prepared(copy, target, File(target, "debug/$libName"))
    }

    private fun key(projectDir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(projectDir.canonicalPath.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
