package dev.rustdroid.ide.ui.editor

import androidx.lifecycle.ViewModel
import dev.rustdroid.ide.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.EditorTab
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.JumpRequest
import dev.rustdroid.ide.model.RunResult
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.CaBundle
import dev.rustdroid.ide.runtime.ConsoleBuffer
import dev.rustdroid.ide.runtime.DiagnosticsParser
import dev.rustdroid.ide.runtime.ProcEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Shown when cargo output reports a TLS trust failure. Points at the
 * on-console curl debug trace (CARGO_HTTP_DEBUG is on) and at the
 * README-documented last-resort escape hatch.
 */
private const val TLS_HINT =
    "hint: TLS trust failure (curl 77/60). Cargo now runs with HTTP debugging — " +
        "the verbose curl output above shows the exact TLS setup failure; paste it " +
        "when reporting. As a last resort, create an empty file named " +
        ".rustdroid-insecure-tls in the project root to skip certificate " +
        "verification for this project (see README → Troubleshooting)"

/**
 * Drives the editor screen: tabs, file tree, console, problems, runs.
 * One build at a time — [runJob] is the single in-flight invocation.
 */
class EditorViewModel(
    val container: AppContainer,
    val projectName: String,
    initialFile: String? = null,
) : ViewModel() {

    /** R4A: localized message from the language-wrapped app context. */
    private fun str(id: Int, vararg args: Any): String = container.context.getString(id, *args)

    /**
     * [projectName] is a project REF: a bare name (internal project under
     * files/projects) or an absolute path (folder opened in place — see
     * ProjectRepository.resolve). Either way the editor edits the folder
     * where it lives; external folders are never copied.
     */
    val projectDir: File = container.projectRepository.resolve(projectName)

    private val repo = container.projectRepository
    private val runner = container.cargoRunner

    // ---------------- live errors (R4A) ----------------

    private val liveChecker = dev.rustdroid.ide.runtime.LiveChecker(runner, container.context.filesDir)
    private val _live = MutableStateFlow<dev.rustdroid.ide.runtime.LiveResult?>(null)
    /** Latest "errors while you type" result (null = none / disabled). */
    val live: StateFlow<dev.rustdroid.ide.runtime.LiveResult?> = _live.asStateFlow()
    private var liveJob: kotlinx.coroutines.Job? = null

    /**
     * Runs a live `cargo check` [delayMs] after the last call (each edit
     * restarts the wait; an in-flight check is cancelled and its process
     * killed). Unsaved tab text is checked from a shadow copy, never saved.
     */
    fun scheduleLiveCheck(delayMs: Long = 1000) {
        liveJob?.cancel()
        if (!dev.rustdroid.ide.util.AppSettings.liveCheck.value) {
            _live.value = null
            return
        }
        liveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!container.toolchainPaths.isInstalled()) return@launch
            activeTab?.let { it.cachedText = _activeText.value }
            val unsaved = _tabs.value.filter { it.dirty }.associate { it.relativePath to it.cachedText }
            val env = withContext(Dispatchers.IO) { buildEnv() }
            runCatching { liveChecker.check(projectDir, unsaved, cargoPath, env) }
                .onSuccess { _live.value = it }
        }
    }
    /**
     * Built per invocation (not cached): cheap after first run (stat
     * fast-path bundle ensure), and rebuilt envs pick up the last-resort
     * TLS escape-hatch marker the moment it appears on disk.
     */
    private fun buildEnv(): Map<String, String> = ProcEnv.env(
        container.toolchainPaths.prefix, container.context.filesDir,
        // APK-pinned CA store: cargo fetch/build must reach crates.io
        // even when the device system trust store is unusable.
        assetProvider = {
            dev.rustdroid.ide.runtime.CaBundle.readAssetPem(
                container.context.assets,
            )
        },
        // last-resort TLS escape hatch: empty marker file in the project
        // root opts THIS project out of certificate verification
        insecureTlsOk = File(projectDir, ProcEnv.PROJECT_INSECURE_TLS_MARKER).isFile,
        // noexec-storage workaround: folders opened in place (Download, …)
        // can hold build output but never execute it — run the artifacts
        // from app storage instead (see ProcEnv.redirectedTargetDir)
        cargoTargetDir = ProcEnv.redirectedTargetDir(
            container.context.filesDir, projectDir,
        ),
    )

    // ---- tabs ----
    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _activeIndex = MutableStateFlow(-1)
    val activeIndex: StateFlow<Int> = _activeIndex.asStateFlow()

    val activeTab: EditorTab? get() = _tabs.value.getOrNull(_activeIndex.value)

    /** Editor text of the active tab — the view pushes changes here. */
    private val _activeText = MutableStateFlow("")
    val activeText: StateFlow<String> = _activeText.asStateFlow()

    // ---- tree ----
    private val _tree = MutableStateFlow<List<FileNode>>(emptyList())
    val tree: StateFlow<List<FileNode>> = _tree.asStateFlow()


    // ---- run state ----
    // scope-owned: the buffer's trailing-line publish is a child of the
    // viewModelScope, cancelled when the VM dies — no leaked background work
    val console = ConsoleBuffer(scope = viewModelScope)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    private val _problems = MutableStateFlow<List<Diagnostic>>(emptyList())
    val problems: StateFlow<List<Diagnostic>> = _problems.asStateFlow()

    private val _jump = MutableStateFlow<JumpRequest?>(null)
    val jump: StateFlow<JumpRequest?> = _jump.asStateFlow()

    private val _bottomTab = MutableStateFlow(BottomTab.CONSOLE)
    val bottomTab: StateFlow<BottomTab> = _bottomTab.asStateFlow()

    val stdinPipe = CargoRunner.StdinPipe()

    private var runJob: Job? = null
    private val parser = DiagnosticsParser()

    /** R4A: a game library is built — the screen opens it in GameActivity. */
    data class GameLaunch(val library: String, val log: String)
    private val _launchGame = kotlinx.coroutines.flow.MutableSharedFlow<GameLaunch>(extraBufferCapacity = 1)
    val launchGame: kotlinx.coroutines.flow.SharedFlow<GameLaunch> = _launchGame

    /** R4A: a game is open (its process is alive) — Run becomes Stop. */
    private val _gameRunning = MutableStateFlow(false)
    val gameRunning: StateFlow<Boolean> = _gameRunning.asStateFlow()
    private var gameWatch: Job? = null

    private val gameProcessName get() = container.context.packageName + ":game"

    private fun gamePid(): Int? {
        val am = container.context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.processName == gameProcessName }?.pid
    }

    @Volatile private var gameStoppedByUser = false

    /** R4A: close the running game (its process holds the old library). */
    fun stopGame() {
        gamePid()?.let {
            gameStoppedByUser = true
            android.os.Process.killProcess(it)
        }
    }

    /**
     * R4A: copy what the game prints into Console while it runs, and say how
     * it ended. The game writes to [log] (see GameActivity.redirectOutput).
     */
    private fun watchGame(log: File) {
        gameWatch?.cancel()
        gameStoppedByUser = false
        gameWatch = viewModelScope.launch(Dispatchers.IO) {
            val output = dev.rustdroid.ide.runtime.GameOutput()
            val buf = ByteArray(8192)
            var pos = 0L
            var sawAlive = false
            val start = System.currentTimeMillis()
            fun readNew() {
                if (!log.isFile) return
                java.io.RandomAccessFile(log, "r").use { f ->
                    f.seek(pos)
                    while (true) {
                        val n = f.read(buf)
                        if (n <= 0) break
                        pos += n
                        output.feed(buf, n).forEach { console.append(it) }
                    }
                }
            }
            try {
                while (true) {
                    val alive = gamePid() != null
                    if (alive) { sawAlive = true; _gameRunning.value = true }
                    readNew()
                    if (!alive && (sawAlive || System.currentTimeMillis() - start > 15_000)) break
                    kotlinx.coroutines.delay(250)
                }
                readNew()
                output.flush().forEach { console.append(it) }
                val closedOk = File(log.path + dev.rustdroid.ide.game.GameActivity.CLOSED_SUFFIX).isFile
                console.system(
                    str(
                        when {
                            gameStoppedByUser -> R.string.vm_game_stopped
                            closedOk || !sawAlive -> R.string.vm_game_closed
                            else -> R.string.vm_game_crashed
                        },
                    ),
                )
            } finally {
                _gameRunning.value = false
                console.flush()
            }
        }
    }

    enum class BottomTab { CONSOLE, PROBLEMS }

    /**
     * R4A: Console/Problems content folded away (only the two tab buttons
     * stay, at the bottom). Tapping the selected tab folds/unfolds; tapping
     * the other tab, Run, or jumping to a problem unfolds it.
     */
    private val _panelCollapsed = MutableStateFlow(false)
    val panelCollapsed: StateFlow<Boolean> = _panelCollapsed.asStateFlow()

    init {
        refreshTree()
        // deep-linked file ("open with" import) wins; otherwise open the
        // entry file the way cargo new creates it
        val entry = initialFile?.takeIf { File(projectDir, it).isFile }
            ?: listOf("src/main.rs", "src/lib.rs", "Cargo.toml")
                .firstOrNull { File(projectDir, it).isFile }
        entry?.let { openFile(it) }
    }

    // ---------------- tabs ----------------

    fun openFile(relativePath: String) {
        val idx = _tabs.value.indexOfFirst { it.relativePath == relativePath }
        if (idx >= 0) {
            setActive(idx)
            return
        }
        val file = File(projectDir, relativePath)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { repo.readFile(file) }
                _tabs.value = _tabs.value + EditorTab(relativePath, file, text)
                setActive(_tabs.value.size - 1)
            } catch (e: IOException) {
                console.system(str(R.string.vm_editor_cannot_open, relativePath, e.message.orEmpty()))
            }
        }
    }

    fun setActive(index: Int) {
        if (index !in _tabs.value.indices) return
        // flush current text into the tab object before switching
        activeTab?.let { it.cachedText = _activeText.value }
        _activeIndex.value = index
        _activeText.value = _tabs.value[index].cachedText
        scheduleLiveCheck(300) // R4A: show this file's errors soon
    }

    /** Called by the editor view on every keystroke. */
    fun onTextChange(text: String) {
        _activeText.value = text
        activeTab?.let {
            it.cachedText = text
            it.dirty = true
        }
        scheduleLiveCheck()
    }

    fun closeTab(index: Int, force: Boolean) {
        val tab = _tabs.value.getOrNull(index) ?: return
        if (tab.dirty && !force) {
            _pendingClose = index
            _showCloseConfirm.value = true
            return
        }
        doClose(index)
    }

    private var _pendingClose = -1
    private val _showCloseConfirm = MutableStateFlow(false)
    val showCloseConfirm: StateFlow<Boolean> = _showCloseConfirm.asStateFlow()

    // ---------------- new file ----------------

    /** One-shot: relative path of the last created file (closes the dialog). */
    private val _createdFile = MutableStateFlow<String?>(null)
    val createdFile: StateFlow<String?> = _createdFile.asStateFlow()

    /** Error of the last createFile attempt (shown inline in the dialog). */
    private val _createFileError = MutableStateFlow<String?>(null)
    val createFileError: StateFlow<String?> = _createFileError.asStateFlow()

    fun clearNewFileSignals() {
        _createdFile.value = null
        _createFileError.value = null
    }

    /**
     * Creates an empty file (mkdirs parents) inside the project and opens it
     * in a new tab. Path safety lives in the repository (Fs.resolveChild);
     * existing files are never overwritten.
     */
    fun createFile(relativePath: String, folder: Boolean = false) {
        viewModelScope.launch {
            val rel = relativePath.trim()
            try {
                withContext(Dispatchers.IO) {
                    if (folder) repo.createFolder(projectDir, rel) else repo.createFile(projectDir, rel)
                }
                _createFileError.value = null
                refreshTree()
                if (!folder) openFile(rel) // R4A: una carpeta nueva solo aparece en Files
                _createdFile.value = rel
            } catch (e: Exception) {
                _createFileError.value = e.message ?: str(R.string.vm_editor_cannot_create, rel)
            }
        }
    }

    // ---------------- delete file ----------------

    /** One-shot: relative path of the last deleted file (closes the dialog). */
    private val _deletedFile = MutableStateFlow<String?>(null)
    val deletedFile: StateFlow<String?> = _deletedFile.asStateFlow()

    /** Error of the last deleteFile attempt (shown inline in the dialog). */
    private val _deleteFileError = MutableStateFlow<String?>(null)
    val deleteFileError: StateFlow<String?> = _deleteFileError.asStateFlow()

    fun clearDeleteSignals() {
        _deletedFile.value = null
        _deleteFileError.value = null
    }

    /**
     * Deletes a file (or folder, recursively) inside the project. Any open
     * tab pointing INTO the deleted path is closed without a save prompt —
     * the file is gone, saving would only resurrect a stale copy. Guards
     * (root, root Cargo.toml, traversal) live in the repository.
     */
    fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            val rel = relativePath.trim().replace('\\', '/')
            try {
                withContext(Dispatchers.IO) { repo.deleteFile(projectDir, rel) }
                _deleteFileError.value = null
                closeTabsUnder(rel)
                refreshTree()
                _deletedFile.value = rel
            } catch (e: Exception) {
                _deleteFileError.value = e.message ?: str(R.string.vm_editor_cannot_delete, rel)
            }
        }
    }

    // ---------------- rename / move (R4A) ----------------

    /** One-shot: the file was renamed/moved (closes the dialog). */
    private val _movedFile = MutableStateFlow<String?>(null)
    val movedFile: StateFlow<String?> = _movedFile.asStateFlow()
    private val _moveFileError = MutableStateFlow<String?>(null)
    val moveFileError: StateFlow<String?> = _moveFileError.asStateFlow()

    fun clearMoveSignals() {
        _movedFile.value = null
        _moveFileError.value = null
    }

    /**
     * Renames or moves [from] to [to] (paths relative to the project). Open
     * tabs follow their files: same position, same unsaved text.
     */
    fun moveFile(from: String, to: String) {
        viewModelScope.launch {
            val src = from.trim().replace('\\', '/').trim('/')
            val dst = to.trim().replace('\\', '/').trim('/')
            try {
                withContext(Dispatchers.IO) { repo.moveFile(projectDir, src, dst) }
                _moveFileError.value = null
                activeTab?.let { it.cachedText = _activeText.value }
                val prefix = "$src/"
                _tabs.value = _tabs.value.map { tab ->
                    val newRel = when {
                        tab.relativePath == src -> dst
                        tab.relativePath.startsWith(prefix) -> dst + "/" + tab.relativePath.removePrefix(prefix)
                        else -> return@map tab
                    }
                    EditorTab(newRel, File(projectDir, newRel), tab.cachedText).also { it.dirty = tab.dirty }
                }
                refreshTree()
                _movedFile.value = dst
            } catch (e: Exception) {
                _moveFileError.value = e.message ?: str(R.string.vm_editor_cannot_move, src)
            }
        }
    }

    /** Closes every tab at [rel] or nested under it (dir delete). */
    private fun closeTabsUnder(rel: String) {
        val prefix = rel.trimEnd('/') + "/"
        // high indices first: doClose shifts the list, removing from the
        // top down keeps the collected indices valid
        _tabs.value.withIndex()
            .filter { (i, tab) -> tab.relativePath == rel || tab.relativePath.startsWith(prefix) }
            .sortedByDescending { it.index }
            .forEach { doClose(it.index) }
    }

    /** R4A: keep the tab open (dialog dismissed or "Cancel"). */
    fun cancelClose() {
        _pendingClose = -1
        _showCloseConfirm.value = false
    }

    fun confirmClose(save: Boolean) {
        val idx = _pendingClose
        _showCloseConfirm.value = false
        if (save) {
            // the pending tab may not be the active one; cachedText is kept
            // current for both cases (onTextChange / setActive flush it)
            _tabs.value.getOrNull(idx)?.let { tab ->
                viewModelScope.launch { saveTab(tab, tab.cachedText) }
            }
        }
        doClose(idx)
    }

    private fun doClose(index: Int) {
        if (index !in _tabs.value.indices) return
        val wasActive = index == _activeIndex.value
        _tabs.value = _tabs.value.filterIndexed { i, _ -> i != index }
        if (wasActive) {
            val next = (index - 1).coerceAtLeast(0).coerceAtMost(_tabs.value.size - 1)
            _activeIndex.value = if (_tabs.value.isEmpty()) -1 else next
            _activeText.value = if (_tabs.value.isEmpty()) "" else _tabs.value[next].cachedText
        } else if (index < _activeIndex.value) {
            _activeIndex.value -= 1
        }
    }

    /** Writes one tab's text to disk off the main thread. */
    private suspend fun saveTab(tab: EditorTab, text: String) = withContext(Dispatchers.IO) {
        try {
            repo.writeFile(tab.file, text)
            tab.dirty = false
        } catch (e: IOException) {
            console.system(str(R.string.vm_editor_save_failed, tab.relativePath, e.message.orEmpty()))
        }
    }

    /**
     * Saves every DIRTY tab. Deliberately skips clean tabs: their cached
     * text can be stale (e.g. Cargo.toml changed by the Deps screen while
     * its tab sat open) and rewriting them would clobber external edits —
     * the bug that made freshly added dependencies vanish on Run.
     */
    private suspend fun saveAllChanged() = withContext(Dispatchers.IO) {
        activeTab?.let { it.cachedText = _activeText.value }
        _tabs.value.filter { it.dirty }.forEach { tab ->
            try {
                repo.writeFile(tab.file, tab.cachedText)
                tab.dirty = false
            } catch (e: IOException) {
                console.system(str(R.string.vm_editor_save_failed, tab.relativePath, e.message.orEmpty()))
            }
        }
    }

    // ---------------- IA (R4A fase 2): lo que la IA puede leer ----------------

    /** Solo lectura. Puede llamarse desde IO: lee StateFlows, no escribe nada. */
    val aiBridge = object : dev.rustdroid.ide.ai.ProjectTools.Bridge {
        override val projectDir: File get() = this@EditorViewModel.projectDir
        override fun tree() = repo.fileTree(projectDir, allFiles = true)
        override fun bufferText(relativePath: String): String? {
            val active = activeTab
            if (active?.relativePath == relativePath) return _activeText.value
            return _tabs.value.firstOrNull { it.relativePath == relativePath }?.cachedText
        }
        override fun activeFile(): String? = activeTab?.relativePath
        override fun unsavedFiles(): List<String> = _tabs.value.filter { it.dirty }.map { it.relativePath }
        override fun runProblems() = _problems.value
        override fun liveProblems() = _live.value?.diagnostics.orEmpty()
        override fun onExternalWrite(relativePath: String, content: String?) =
            this@EditorViewModel.onExternalWrite(relativePath, content)
    }

    /**
     * La charla con la IA de este proyecto. Queda enganchada a este editor
     * mientras el VM vive (ir a Opciones o Deps no la corta; salir del
     * proyecto sí). Cada vez que se abre el proyecto, lo anterior se oculta
     * hasta que el usuario desliza hacia abajo.
     */
    val chat: dev.rustdroid.ide.ai.ChatSession by lazy {
        container.chatStore.session(projectDir, projectName.substringAfterLast('/')).also {
            it.attach(aiBridge)
            it.markVisitStart()
        }
    }

    override fun onCleared() {
        chat.detach(aiBridge)
        super.onCleared()
    }

    /**
     * R4A fase 3: la IA (o Deshacer) cambió un archivo en disco. Si está
     * abierto, la pestaña muestra lo nuevo y queda guardada; si se borró, se
     * cierra. Después se refresca Files y los errores en vivo.
     */
    fun onExternalWrite(relativePath: String, content: String?) {
        activeTab?.let { it.cachedText = _activeText.value }
        val idx = _tabs.value.indexOfFirst { it.relativePath == relativePath }
        if (idx >= 0) {
            if (content == null) {
                closeTab(idx, force = true)
            } else {
                val tab = _tabs.value[idx]
                tab.cachedText = content
                tab.dirty = false
                if (idx == _activeIndex.value) _activeText.value = content
                _tabs.value = _tabs.value.toList() // que la pestaña deje de verse "sin guardar"
            }
        }
        refreshTree()
        scheduleLiveCheck(300)
    }

    // ---------------- leaving the project (R4A) ----------------

    /** Names of the open files with unsaved edits (empty = nothing to save). */
    fun unsavedFiles(): List<String> {
        activeTab?.let { it.cachedText = _activeText.value }
        return _tabs.value.filter { it.dirty }.map { it.relativePath }
    }

    /**
     * Saves every dirty tab, then calls [onSaved] only if all of them were
     * written. A failed save stays dirty (the error is in the console), so the
     * screen does not leave and silently drop those edits.
     */
    fun saveAllThen(onSaved: () -> Unit) {
        viewModelScope.launch {
            saveAllChanged()
            if (_tabs.value.none { it.dirty }) onSaved()
        }
    }

    /**
     * Re-reads tabs that have no unsaved edits. Called when the editor
     * screen re-enters composition (back-navigation from Deps), so files
     * changed elsewhere are refreshed instead of being overwritten later
     * with stale content.
     */
    fun reloadUnchangedTabs() {
        viewModelScope.launch {
            val changed = withContext(Dispatchers.IO) {
                _tabs.value.filter { !it.dirty }.mapNotNull { tab ->
                    val fresh = runCatching { repo.readFile(tab.file) }.getOrNull()
                    if (fresh != null && fresh != tab.cachedText) tab to fresh else null
                }
            }
            if (changed.isEmpty()) return@launch
            changed.forEach { (tab, fresh) -> tab.cachedText = fresh }
            // refresh the visible text if the active tab was one of them
            _activeIndex.value.takeIf { it >= 0 }?.let { idx ->
                _tabs.value.getOrNull(idx)?.let { tab ->
                    if (changed.any { it.first === tab }) _activeText.value = tab.cachedText
                }
            }
        }
    }

    // ---------------- tree ----------------

    fun refreshTree() {
        viewModelScope.launch {
            _tree.value = withContext(Dispatchers.IO) { repo.fileTree(projectDir, allFiles = false) }
        }
    }

    // ---------------- runs ----------------

    // Absolute path: Android's JVM does not resolve bare command names
    // against the child env's PATH — only absolute paths exec reliably.
    private val cargoPath: String
        get() = ProcEnv.toolchainCommand(container.toolchainPaths.prefix, "cargo")

    /** R4A: macroquad projects run as an app (GameActivity), not in Console. */
    fun run() {
        val manifest = File(projectDir, "Cargo.toml")
        val game = runCatching { dev.rustdroid.ide.runtime.GameBuild.isGame(manifest.readText()) }
            .getOrDefault(false)
        if (game) startCargo(listOf(cargoPath, "build", "--lib"), game = true)
        else startCargo(listOf(cargoPath, "run"))
    }
    fun build() = startCargo(listOf(cargoPath, "build"))
    fun clean() = startCargo(listOf(cargoPath, "clean"))

    // ---------------- export APK (R4A) ----------------

    /** Is this a macroquad game (Run opens it as an app, it can be exported)? */
    fun isGameProject(): Boolean = runCatching {
        dev.rustdroid.ide.runtime.GameBuild.isGame(File(projectDir, "Cargo.toml").readText())
    }.getOrDefault(false)

    private val exportPrefs
        get() = container.context.getSharedPreferences("r4a_export", android.content.Context.MODE_PRIVATE)
    private val exportKey get() = projectDir.canonicalPath

    /** What the export dialog proposes: last choices, next version code. */
    fun exportDefaults(): dev.rustdroid.ide.export.ApkExporter.Options {
        val p = exportPrefs
        val k = exportKey
        val lastCode = p.getInt("$k|code", 0)
        return dev.rustdroid.ide.export.ApkExporter.Options(
            appName = p.getString("$k|name", null) ?: projectDir.name,
            packageName = p.getString("$k|package", null)
                ?: dev.rustdroid.ide.export.ApkExporter.suggestPackage(projectDir.name),
            versionCode = lastCode + 1,
            versionName = p.getString("$k|version", null)?.let { bumpVersion(it) } ?: "1.0",
        )
    }

    /** "1.2" -> "1.3": a sensible next version name to propose. */
    private fun bumpVersion(v: String): String {
        val parts = v.split('.')
        val last = parts.last().toIntOrNull() ?: return v
        return (parts.dropLast(1) + (last + 1).toString()).joinToString(".")
    }

    /** R4A: this project's APK icon (192x192 PNG in app storage), if chosen. */
    val exportIconFile: File
        get() {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val h = md.digest(exportKey.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return File(container.context.filesDir, "export-icons/$h.png")
        }

    /**
     * R4A: load a picked image for the icon cropper, scaled down so a big
     * photo fits in memory (the longest side stays around 1024 px).
     * Null when it can not be read.
     */
    suspend fun loadIconSource(uri: android.net.Uri): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = container.context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /** R4A: save the chosen square of [src] as this project's 192x192 PNG icon. */
    suspend fun saveExportIcon(src: android.graphics.Bitmap, crop: android.graphics.Rect): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val side = minOf(crop.width(), crop.height())
                require(side > 0)
                val square = android.graphics.Bitmap.createBitmap(src, crop.left, crop.top, side, side)
                val icon = android.graphics.Bitmap.createScaledBitmap(square, 192, 192, true)
                val out = exportIconFile
                out.parentFile?.mkdirs()
                out.outputStream().use { icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                true
            }.getOrDefault(false)
        }

    fun clearExportIcon() {
        exportIconFile.delete()
    }

    /** The exported APK, once ready — the screen offers Install / Share. */
    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported.asStateFlow()
    fun clearExported() { _exported.value = null }

    fun exportApk(options: dev.rustdroid.ide.export.ApkExporter.Options) {
        startCargo(listOf(cargoPath, "build", "--lib", "--release"), game = true, export = options)
    }

    private suspend fun writeApk(lib: File, options: dev.rustdroid.ide.export.ApkExporter.Options) {
        console.system(str(R.string.vm_export_packing))
        try {
            val out = withContext(Dispatchers.IO) {
                val ctx = container.context
                val template = ctx.assets.open("export/gameplayer.apk").use { it.readBytes() }
                val key = dev.rustdroid.ide.export.GameSigningKey.loadOrCreate(File(ctx.filesDir, "signing"))
                val fileName = options.appName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
                    .ifEmpty { "juego" } + "-" + options.versionName + ".apk"
                // Download/R4A when shared storage is writable, else app storage
                val public = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    ),
                    "R4A",
                )
                val dir = if ((public.isDirectory || public.mkdirs()) && public.canWrite()) public
                else File(ctx.getExternalFilesDir(null), "exports")
                val out = File(dir, fileName)
                val icon = exportIconFile.takeIf { it.isFile }?.readBytes()
                dev.rustdroid.ide.export.ApkExporter.export(
                    template, lib, options, key, File(ctx.cacheDir, "export"), out, icon,
                )
                exportPrefs.edit()
                    .putString("$exportKey|name", options.appName)
                    .putString("$exportKey|package", options.packageName)
                    .putInt("$exportKey|code", options.versionCode)
                    .putString("$exportKey|version", options.versionName)
                    .apply()
                out
            }
            console.system(str(R.string.vm_export_done, out.absolutePath, (out.length() + 1023) / 1024))
            _exported.value = out
        } catch (e: Exception) {
            console.system(str(R.string.vm_export_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun startCargo(
        command: List<String>,
        game: Boolean = false,
        export: dev.rustdroid.ide.export.ApkExporter.Options? = null,
    ) {
        if (_running.value) return
        _panelCollapsed.value = false // R4A: show the output of the run
        runJob = viewModelScope.launch {
            // persist unsaved edits BEFORE the toolchain reads them
            saveAllChanged()
            _running.value = true
            _lastResult.value = null
            _problems.value = emptyList()
            parser.reset()
            console.clear()
            // Show the tool's short name, not its full path
            val display = command.first().substringAfterLast('/') +
                command.drop(1).joinToString(" ", prefix = " ")
            console.system("\$ $display")
            try {
                // built here (not at class init) so bundle warm-up and the
                // escape-hatch marker check happen off the main thread and
                // reflect the state at the moment of invocation
                var runEnv = withContext(Dispatchers.IO) { buildEnv() }
                var cwd = projectDir
                // R4A: games are built as a library in a copy of the project
                var gameLib: File? = null
                if (game) {
                    val prepared = try {
                        withContext(Dispatchers.IO) {
                            dev.rustdroid.ide.runtime.GameBuild.prepare(projectDir, container.context.filesDir)
                        }
                    } catch (e: Exception) {
                        console.system(str(R.string.vm_game_cannot_build, e.message.orEmpty()))
                        _lastResult.value = null
                        return@launch
                    }
                    console.system(str(if (export != null) R.string.vm_export_note else R.string.vm_game_note))
                    cwd = prepared.dir
                    runEnv = runEnv + ("CARGO_TARGET_DIR" to prepared.targetDir.absolutePath)
                    // export: release build (smaller, faster) next to the debug one
                    gameLib = if (export != null) File(prepared.targetDir, "release/" + prepared.library.name)
                    else prepared.library
                }
                // the insecure-TLS escape hatch is a global, persistent
                // footgun — never let it run silently
                if (runEnv["CARGO_HTTP_DANGER_ACCEPT_INVALID_CERTS"] == "true") {
                    console.system(
                        "warning: TLS certificate verification is DISABLED " +
                            "(insecure-tls marker present) — cargo will accept " +
                            "invalid certificates; delete the marker to re-enable",
                    )
                }
                // external folder: build output runs from app storage
                // because shared storage is noexec — say so once per run,
                // right where "Permission denied (os error 13)" used to be
                if (!game && runEnv.containsKey("CARGO_TARGET_DIR")) {
                    console.system(
                        str(R.string.vm_editor_shared_storage_note),
                    )
                }
                val result = runner.run(
                    command, cwd = cwd, env = runEnv, stdin = stdinPipe,
                    onLine = { line ->
                        console.append(line)
                        if (line.stream == dev.rustdroid.ide.model.Stream.STDERR) {
                            parser.feed(line.text)
                            if (parser.errorCount() + parser.warningCount() > 0) {
                                _problems.value = parser.snapshot()
                            }
                        }
                    },
                )
                _lastResult.value = result
                // publish any lines still sitting in the rate-limited batch
                // BEFORE scanning them for TLS failure signatures
                console.flush()
                // libcurl 77/60 = TLS trust store could not be loaded — bare
                // curl codes confuse users; surface an actionable hint.
                if (!result.cancelled && result.exitCode != 0 &&
                    console.lines.value.any {
                        it.text.contains("error setting certificate verify locations") ||
                            it.text.contains("[77]") || it.text.contains("[60]")
                    }
                ) {
                    console.system(TLS_HINT)
                    console.system(tlsDiagnostics(runEnv))
                }
                console.system(
                    if (result.cancelled) "(terminated)"
                    else "(exit ${result.exitCode} in ${result.durationMs / 1000.0}s)"
                )
                if (gameLib != null && !result.cancelled && result.exitCode == 0) {
                    if (export != null && gameLib.isFile) {
                        writeApk(gameLib, export)
                    } else if (gameLib.isFile) {
                        console.system(str(R.string.vm_game_opening))
                        // a game still open holds the old library: close it
                        if (gamePid() != null) {
                            stopGame()
                            gameWatch?.join()
                        }
                        val log = File(container.context.filesDir, "games/run.log")
                        log.delete()
                        File(log.path + dev.rustdroid.ide.game.GameActivity.CLOSED_SUFFIX).delete()
                        _launchGame.tryEmit(GameLaunch(gameLib.absolutePath, log.absolutePath))
                        watchGame(log)
                    } else {
                        console.system(str(R.string.vm_game_cannot_build, gameLib.name))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                console.system("(terminated)")
            } finally {
                _running.value = false
                // rate-limited batches are published here at the latest
                console.flush()
            }
        }
    }

    /** See DepsViewModel.tlsDiagnostics — same one-line on-device truth. */
    private fun tlsDiagnostics(env: Map<String, String>): String {
        val version = runCatching {
            container.context.packageManager
                .getPackageInfo(container.context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val cainfo = env["CARGO_HTTP_CAINFO"] ?: "<not set — bundle could not be built>"
        // SSL_CERT_DIR must print "unset" on builds from 2026-09-03 onwards:
        // a non-empty CApath (from that env var) makes some statically-linked
        // TLS backends reject the whole verify-location setup (curl 77)
        val certDir = env["SSL_CERT_DIR"] ?: "unset"
        return "diag: app=$version | CA ${CaBundle.bundleStatus(container.context.filesDir)} | " +
            "CARGO_HTTP_CAINFO=$cainfo | SSL_CERT_DIR=$certDir"
    }

    fun stop() {
        runJob?.cancel()
    }

    /** Manual console clear (toolbar button); disabled while a build streams. */
    fun clearConsole() {
        console.clear()
    }

    fun sendStdin(line: String) {
        // echo into the (selectable) console so the input is visible in
        // the output history and can be copied with the rest
        console.system("> $line")
        stdinPipe.sendLine(line)
    }

    // ---------------- problems ----------------

    fun jumpTo(diagnostic: Diagnostic) {
        val resolved = diagnostic.resolvePath(projectDir) ?: return
        if (!resolved.isFile) return
        val rel = diagnostic.file ?: return
        if (rel.startsWith("/")) return // absolute paths outside project: no jump
        openFile(rel)
        _jump.value = JumpRequest(
            rel,
            (diagnostic.line ?: 1),
            (diagnostic.col ?: 1),
        )
        _bottomTab.value = BottomTab.PROBLEMS
        _panelCollapsed.value = false
    }

    fun clearJump() {
        _jump.value = null
    }

    fun selectBottomTab(tab: BottomTab) {
        _bottomTab.value = tab
        _panelCollapsed.value = false
    }

    /** R4A: tab button tapped — the selected one toggles the fold. */
    fun onBottomTabClicked(tab: BottomTab) {
        if (tab == _bottomTab.value) {
            _panelCollapsed.value = !_panelCollapsed.value
        } else {
            selectBottomTab(tab)
        }
    }

    companion object {
        fun factory(container: AppContainer, projectName: String, initialFile: String? = null) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(container, projectName, initialFile) as T
            }
    }
}
