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
    /** R4A fase 4: false si cargo falló (p. ej. un Cargo.toml roto, sin diagnósticos JSON). */
    val success: Boolean = true,
    /** Últimas líneas de stderr (lo que cargo dice cuando no es un error de compilación). */
    val stderrTail: List<String> = emptyList(),
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
        /** R4A fase 4: la IA chequea online para que se bajen crates nuevas. */
        offline: Boolean = true,
    ): LiveResult = withContext(Dispatchers.IO) {
        val root = File(filesDir, "check/${key(projectDir)}")
        val (shadow, checked) = prepare(projectDir, unsaved)
        val lines = ArrayList<String>()
        val err = ArrayList<String>()
        val result = runner.run(
            listOf(cargo, "check", "--message-format=json") + if (offline) listOf("--offline") else emptyList(),
            cwd = shadow,
            env = env + ("CARGO_TARGET_DIR" to File(root, "target").absolutePath),
            onLine = {
                if (it.stream == Stream.STDOUT) synchronized(lines) { lines += it.text }
                else synchronized(err) { err += it.text; if (err.size > 40) err.removeAt(0) }
            },
        )
        val diagnostics = CargoCheckParser.parse(synchronized(lines) { lines.toList() })
        // files that were checked from disk: record their text too
        for (d in diagnostics) {
            if (d.file !in checked) {
                File(shadow, d.file).takeIf { it.isFile }?.let { checked[d.file] = it.readText() }
            }
        }
        LiveResult(diagnostics, checked, result.success, synchronized(err) { err.toList() })
    }

    /**
     * R4A fase 4: corre un comando (por ejemplo `cargo run`) en la copia
     * sombra, con el texto sin guardar del editor. La carpeta del usuario no
     * se toca. Cancelar la corrutina mata el proceso.
     */
    suspend fun runInShadow(
        projectDir: File,
        unsaved: Map<String, String>,
        command: List<String>,
        env: Map<String, String>,
        onLine: (dev.rustdroid.ide.model.ConsoleLine) -> Unit,
    ): dev.rustdroid.ide.model.RunResult = withContext(Dispatchers.IO) {
        val root = File(filesDir, "check/${key(projectDir)}")
        val (shadow, _) = prepare(projectDir, unsaved)
        runner.run(
            command,
            cwd = shadow,
            env = env + ("CARGO_TARGET_DIR" to File(root, "target-run").absolutePath),
            onLine = onLine,
        )
    }

    /** Copia el proyecto a la sombra y escribe encima el texto sin guardar. */
    private fun prepare(projectDir: File, unsaved: Map<String, String>): Pair<File, HashMap<String, String>> {
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
        return shadow to checked
    }

    private fun key(projectDir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(projectDir.canonicalPath.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
