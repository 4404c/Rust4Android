package dev.rustdroid.ide.ui.deps

import androidx.lifecycle.ViewModel
import dev.rustdroid.ide.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.CrateSummary
import dev.rustdroid.ide.projects.CargoToml
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(FlowPreview::class)
class DepsViewModel(
    val container: AppContainer,
    val projectName: String,
) : ViewModel() {

    /** R4A: localized message from the language-wrapped app context. */
    private fun str(id: Int, vararg args: Any): String = container.context.getString(id, *args)

    private val repo = container.projectRepository
    private val crates = container.cratesIoClient

    /**
     * [projectName] is a project REF (name for internal projects, absolute
     * path for folders opened in place) — same resolution as the editor.
     */
    val projectDir: File = repo.resolve(projectName)
    private val manifest: File get() = File(projectDir, "Cargo.toml")

    private val _deps = MutableStateFlow<List<CargoToml.Dependency>>(emptyList())
    val deps: StateFlow<List<CargoToml.Dependency>> = _deps.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<CrateSummary>>(emptyList())
    val results: StateFlow<List<CrateSummary>> = _results.asStateFlow()

    /** R4A: well-known pure-Rust crates, shown before the user searches. */
    private val _popular = MutableStateFlow(popularCache)
    val popular: StateFlow<List<CrateSummary>> = _popular.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True while `cargo fetch` (the actual dependency download) is running. */
    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    /**
     * R4A: the crate being added right now (version lookup + download), so
     * only ITS row shows a spinner — `cargo fetch` downloads everything at
     * once, which used to put a spinner on every dependency.
     */
    private val _installing = MutableStateFlow<String?>(null)
    val installing: StateFlow<String?> = _installing.asStateFlow()

    /**
     * Tail of the last `cargo fetch` output — shown (selectable) in the
     * deps screen so download errors are visible, not a mystery snackbar.
     */
    private val _fetchLog = MutableStateFlow<List<String>>(emptyList())
    val fetchLog: StateFlow<List<String>> = _fetchLog.asStateFlow()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null

    private val cargoPath: String
        get() = dev.rustdroid.ide.runtime.ProcEnv.toolchainCommand(
            container.toolchainPaths.prefix, "cargo",
        )

    /**
     * Built per invocation (not cached): cheap after first run (stat
     * fast-path bundle ensure), and rebuilt envs pick up the last-resort
     * TLS escape-hatch marker the moment it appears on disk.
     */
    private fun buildEnv(): Map<String, String> =
        dev.rustdroid.ide.runtime.ProcEnv.env(
            container.toolchainPaths.prefix, container.context.filesDir,
            // APK-pinned CA store: keeps `cargo fetch` working on devices
            // whose system trust store is empty/unreadable (curl err 77).
            assetProvider = {
                dev.rustdroid.ide.runtime.CaBundle.readAssetPem(
                    container.context.assets,
                )
            },
            // last-resort TLS escape hatch: empty marker file in the project
            // root opts THIS project out of certificate verification
            insecureTlsOk = File(
                projectDir,
                dev.rustdroid.ide.runtime.ProcEnv.PROJECT_INSECURE_TLS_MARKER,
            ).isFile,
        )

    init {
        refresh()
        if (popularCache.isEmpty()) {
            viewModelScope.launch {
                // best effort: offline just means no "popular" section
                runCatching { crates.byNames(dev.rustdroid.ide.projects.PopularCrates.names) }
                    .onSuccess { popularCache = it; _popular.value = it }
            }
        }
        // debounce search
        viewModelScope.launch {
            _query.collect { q ->
                searchJob?.cancel()
                if (q.trim().length < 2) {
                    _results.value = emptyList()
                    return@collect
                }
                searchJob = launch {
                    delay(350)
                    runSearch(q)
                }
            }
        }
    }

    private suspend fun runSearch(q: String) {
        _searching.value = true
        try {
            _results.value = crates.search(q)
            if (_results.value.isEmpty()) {
                _message.value = str(R.string.vm_deps_no_match, q)
            }
        } catch (e: Exception) {
            _results.value = emptyList()
            _message.value = str(R.string.vm_deps_unreachable, e.message.orEmpty())
        } finally {
            _searching.value = false
        }
    }

    /** Runs the search immediately (IME "Search" action), skipping the debounce. */
    fun searchNow() {
        val q = _query.value.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _deps.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.readDependencies(manifest)
                }
                checkUpdates()
            } catch (e: IOException) {
                _message.value = "Cargo.toml: ${e.message}"
            }
        }
    }

    /** R4A: an update the user can apply — shown as ↑ next to the crate. */
    data class Update(val current: String, val latest: String, val breaking: Boolean)

    private val _updates = MutableStateFlow<Map<String, Update>>(emptyMap())
    val updates: StateFlow<Map<String, Update>> = _updates.asStateFlow()

    /**
     * R4A: newest version of each dependency that builds with the on-device
     * Rust (same rule as adding one). Pinned specs (`=x`), fontdue (pinned on
     * purpose for macroquad) and git/path dependencies are left alone.
     * Network errors just mean no ↑ this time.
     */
    private suspend fun checkUpdates() {
        val rust = dev.rustdroid.ide.toolchain.ToolchainDistro.RUST_VERSION
        val found = HashMap<String, Update>()
        for (dep in _deps.value) {
            if (dep.name == "fontdue") continue
            val current = dev.rustdroid.ide.projects.CrateVersions.specVersion(dep.spec) ?: continue
            val latest = runCatching {
                latestCache.getOrPut(dep.name) {
                    dev.rustdroid.ide.projects.CrateVersions.pickCompatible(crates.versions(dep.name), rust)
                }
            }.getOrNull() ?: continue
            if (dev.rustdroid.ide.projects.CrateVersions.isNewer(current, latest)) {
                found[dep.name] = Update(
                    current, latest,
                    dev.rustdroid.ide.projects.CrateVersions.isBreaking(current, latest),
                )
            }
        }
        _updates.value = found
    }

    /** R4A: write the newer version (only the number changes) and download it. */
    fun update(name: String) {
        val upd = _updates.value[name] ?: return
        _installing.value = name
        viewModelScope.launch {
            try {
                val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.setVersion(manifest, name, upd.latest)
                }
                if (!ok) {
                    _installing.value = null
                    _message.value = str(R.string.vm_deps_update_manual, name)
                    return@launch
                }
                _message.value = str(R.string.vm_deps_updated, name, upd.current, upd.latest)
                refresh()
                fetchNow()
            } catch (e: Exception) {
                _installing.value = null
                _message.value = str(R.string.vm_deps_add_failed, e.message.orEmpty())
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun add(crate: CrateSummary) {
        _installing.value = crate.name
        viewModelScope.launch {
            try {
                // R4A: the newest version that builds with the on-device Rust
                // (max_version may need a newer toolchain, e.g. uuid 1.29.3
                // requires Rust 1.89 while the bundle is 1.85)
                val rust = dev.rustdroid.ide.toolchain.ToolchainDistro.RUST_VERSION
                val version = dev.rustdroid.ide.projects.CrateVersions.pickCompatible(
                    crates.versions(crate.name), rust,
                )
                if (version == null) {
                    _installing.value = null
                    _message.value = str(R.string.vm_deps_no_compatible, crate.name, rust)
                    return@launch
                }
                val pinnedFontdue = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.addDependency(manifest, crate.name, version)
                    // R4A: macroquad pulls fontdue, whose newer versions need
                    // Rust 1.87 — pin the last one that builds here (unless
                    // the user already chose a fontdue themselves)
                    val needsPin = crate.name == "macroquad" &&
                        !Regex("""(?m)^\s*fontdue\s*=""").containsMatchIn(manifest.readText())
                    if (needsPin) {
                        CargoToml.addDependency(manifest, "fontdue", dev.rustdroid.ide.runtime.GameBuild.FONTDUE_PIN)
                    }
                    needsPin
                }
                _message.value = if (pinnedFontdue) str(R.string.vm_deps_added_fontdue, crate.name, version)
                else str(R.string.vm_deps_added, crate.name, version)
                refresh()
                // Download right away so errors surface here (with the real
                // cargo output) instead of as a mysterious build failure later.
                fetchNow()
            } catch (e: Exception) {
                _installing.value = null
                _message.value = str(R.string.vm_deps_add_failed, e.message.orEmpty())
            }
        }
    }

    fun remove(name: String) {
        viewModelScope.launch {
            try {
                val removed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.removeDependency(manifest, name)
                }
                if (removed) _message.value = "removed $name"
                refresh()
            } catch (e: Exception) {
                _message.value = str(R.string.vm_deps_remove_failed, e.message.orEmpty())
            }
        }
    }

    /**
     * Downloads all dependencies now via `cargo fetch`. Single-flight.
     * Output streams into [fetchLog]; on failure the last error lines are
     * surfaced in the snackbar AND kept selectable in the log area.
     */
    fun fetchNow() {
        if (_fetching.value) return
        if (!container.toolchainPaths.isInstalled()) {
            _message.value = str(R.string.vm_deps_no_toolchain)
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _fetching.value = true
            // first access warms the CA bundle (disk) — keep it off Main;
            // rebuilt per fetch so the escape-hatch marker is honored live
            val fetchEnv = withContext(kotlinx.coroutines.Dispatchers.IO) { buildEnv() }
            val log = mutableListOf<String>()
            try {
                val result = container.cargoRunner.run(
                    listOf(cargoPath, "fetch"),
                    cwd = projectDir,
                    env = fetchEnv,
                    onLine = { line ->
                        synchronized(log) {
                            log.add(line.text)
                            if (log.size > 400) log.removeAt(0)
                        }
                        _fetchLog.value = synchronized(log) { log.toList() }
                    },
                )
                if (result.success) {
                    _message.value = str(R.string.vm_deps_downloaded)
                } else {
                    // Snackbar is 2-line-capped — keep it a pointer; the full
                    // (selectable/copyable) error output is in fetchLog.
                    _message.value =
                        "cargo fetch failed (exit ${result.exitCode}) — see fetch output"
                    // libcurl 77/60 = TLS trust store could not be loaded —
                    // bare curl codes confuse users; surface an actionable hint.
                    val tlsFailed = synchronized(log) { log.toList() }.any {
                        it.contains("error setting certificate verify locations") ||
                            it.contains("[77]") || it.contains("[60]")
                    }
                    if (tlsFailed) {
                        _fetchLog.value = _fetchLog.value + TLS_HINT
                        _fetchLog.value = _fetchLog.value + tlsDiagnostics(fetchEnv)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = str(R.string.vm_deps_fetch_error, e.message.orEmpty())
            } finally {
                _fetching.value = false
                _installing.value = null
            }
        }
    }

    fun clearFetchLog() {
        fetchJob?.cancel()
        _fetchLog.value = emptyList()
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * One-line on-device truth for TLS debugging: app version (so we can
     * tell an outdated build from a broken fix) + the CA bundle state +
     * whether cargo will actually receive CARGO_HTTP_CAINFO + whether the
     * CApath channel (SSL_CERT_DIR) is gone.
     */
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
        return "diag: app=$version | CA ${
            dev.rustdroid.ide.runtime.CaBundle.bundleStatus(container.context.filesDir)
        } | CARGO_HTTP_CAINFO=$cainfo | SSL_CERT_DIR=$certDir"
    }

    companion object {
        /** Popular crates fetched once per app run (crates.io is rate-limited). */
        @Volatile
        private var popularCache: List<CrateSummary> = emptyList()

        /** R4A: newest compatible version per crate, looked up once per app run. */
        private val latestCache: MutableMap<String, String?> =
            java.util.Collections.synchronizedMap(HashMap())

        /** Appended to the fetch log when cargo output shows a TLS trust failure. */
        const val TLS_HINT =
            "hint: TLS trust failure (curl 77/60). Cargo now runs with HTTP debugging — " +
                "the verbose curl output above shows the exact TLS setup failure; paste it " +
                "when reporting. As a last resort, create an empty file named " +
                ".rustdroid-insecure-tls in the project root to skip certificate " +
                "verification for this project (see README → Troubleshooting)"

        fun factory(container: AppContainer, projectName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DepsViewModel(container, projectName) as T
            }
    }
}
