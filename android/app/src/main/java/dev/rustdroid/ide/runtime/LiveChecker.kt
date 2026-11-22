package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** R4A: result of one live check, with the text each file had when checked. */
data class LiveResult(
    val diagnostics: List<LiveDiagnostic>,
    /** relative path -> text that was checked (to know if it is still current). */
    val checkedText: Map<String, String>,
)

/**
 * R4A: "errors while you type". Runs `cargo check` on a SHADOW copy of the
 * project inside app storage, with the editor's unsaved text written into
 * it — the user's folder is never touched and nothing is saved behind their
 * back. Only changed files are copied, so repeated checks stay cheap.
 * `--offline`: a check after every pause must not hit the network.
 */
class LiveChecker(
    private val runner: CargoRunner,
    private val filesDir: File,
) {

    /** Files that got unsaved editor text last time (shadow differs from disk). */
    private val lastUnsaved = HashSet<String>()

    suspend fun check(
        projectDir: File,
        unsaved: Map<String, String>,
        cargo: String,
        env: Map<String, String>,
    ): LiveResult = withContext(Dispatchers.IO) {
        val root = File(filesDir, "check/${key(projectDir)}")
        val shadow = File(root, "src-copy")
        // files that no longer have unsaved text must come back from disk,
        // even if size/date would not reveal the difference
        val restore = synchronized(lastUnsaved) { lastUnsaved - unsaved.keys }
        ShadowCopy.sync(projectDir, shadow, force = restore)
        synchronized(lastUnsaved) { lastUnsaved.clear(); lastUnsaved += unsaved.keys }
        val checked = HashMap<String, String>()
        for ((rel, text) in unsaved) {
            val f = File(shadow, rel)
            if (!f.isFile || f.readText() != text) {
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
            checked[rel] = text
        }
        val lines = ArrayList<String>()
        runner.run(
            listOf(cargo, "check", "--message-format=json", "--offline"),
            cwd = shadow,
            env = env + ("CARGO_TARGET_DIR" to File(root, "target").absolutePath),
            onLine = { if (it.stream == Stream.STDOUT) synchronized(lines) { lines += it.text } },
        )
        val diagnostics = CargoCheckParser.parse(synchronized(lines) { lines.toList() })
        // files that were checked from disk: record their text too
        for (d in diagnostics) {
            if (d.file !in checked) {
                File(shadow, d.file).takeIf { it.isFile }?.let { checked[d.file] = it.readText() }
            }
        }
        LiveResult(diagnostics, checked)
    }

    private fun key(projectDir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(projectDir.canonicalPath.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
