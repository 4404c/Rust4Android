package dev.rustdroid.ide.projects

import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * On-device cargo project CRUD under files/projects. Creation runs
 * `cargo new` through the bundled toolchain (the exact loop the IDE uses);
 * listing/renaming/deletion are plain filesystem ops. Pure JVM.
 *
 * Two kinds of projects share one Home screen:
 *  - **internal** projects live under [projectsRoot] and are referenced by
 *    name (the cargo package name == directory name);
 *  - **external** projects are folders anywhere on storage (Download, …)
 *    the user opened *in place* — RustDroid edits them where they lie, never
 *    copies them, and external edits are picked up on refresh. They are
 *    referenced by absolute path (see [resolve]/[refOf]) and remembered in a
 *    one-path-per-line [externalRegistry] file.
 */
class ProjectRepository(
    private val projectsRoot: File,
    /** Registry of folders opened in place: one canonical absolute path per line. */
    private val externalRegistry: File,
    private val runner: CargoRunner,
    private val envProvider: () -> Map<String, String>,
    /** Absolute path of the bundled cargo binary. Android's JVM does NOT
     *  resolve bare command names against the child env's PATH (verified:
     *  `ProcessBuilder("cargo")` with PATH=files/usr/bin fails with
     *  error=2), so every tool invocation must use the absolute path. */
    private val cargoPath: () -> String,
) {
    fun projectsRootExists(): Boolean = projectsRoot.isDirectory

    // ---- project references (navigation currency) ----

    /**
     * Resolves a project reference to its directory: an absolute path
     * (external, starts with "/") or a bare name under [projectsRoot].
     * Navigation routes carry refs, never File objects.
     */
    fun resolve(ref: String): File =
        if (ref.startsWith("/")) File(ref) else File(projectsRoot, ref)

    /** The ref a project directory is navigated by. */
    fun refOf(dir: File): String =
        if (dir.parentFile == projectsRoot || isInternalCanonical(dir)) dir.name else dir.absolutePath

    // las charlas guardan la ruta canónica (/data/data/...), que no es igual a projectsRoot (/data/user/0/...)
    private fun isInternalCanonical(dir: File): Boolean = runCatching {
        dir.canonicalFile.parentFile == projectsRoot.canonicalFile
    }.getOrDefault(false)

    fun list(): List<dev.rustdroid.ide.model.ProjectSummary> {
        val dirs = projectsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "Cargo.toml").isFile }
            ?: emptyList()
        val internal = dirs.map { dir ->
            summary(dir, external = false)
        }
        // Folders opened in place. R4A (alcance de archivos): one R4A can not
        // read (safe mode without storage permission, deleted, unmounted)
        // stays in the list as `unreachable`, so Home can say why, instead
        // of silently dropping out.
        val external = readRegistry()
            .map { File(it) }
            .map { dir ->
                if (dir.isDirectory && dir.canRead()) summary(dir, external = true)
                else dev.rustdroid.ide.model.ProjectSummary(
                    name = dir.name, dir = dir, lastModifiedMs = 0L, dependencyCount = -1,
                    external = true, unreachable = true,
                )
            }
        return (internal + external).sortedByDescending { it.lastModifiedMs }
    }

    private fun summary(dir: File, external: Boolean): dev.rustdroid.ide.model.ProjectSummary {
        val deps = try {
            CargoToml.readDependencies(File(dir, "Cargo.toml")).size
        } catch (e: IOException) {
            -1 // no/!parseable Cargo.toml marker
        }
        return dev.rustdroid.ide.model.ProjectSummary(
            name = dir.name,
            dir = dir,
            lastModifiedMs = latestMtime(dir),
            dependencyCount = deps,
            external = external,
        )
    }

    /**
     * Bounded "last modified" for the Home sort key. Skips target/ (tens of
     * thousands of build artifacts that never indicate user edits — a built
     * project turned the Home listing from instant into seconds), .git/ and
     * hidden dirs; caps depth; and tracks visited canonical paths so a
     * symlink loop inside a project cannot recurse forever.
     */
    private fun latestMtime(root: File): Long {
        val visited = HashSet<String>()
        fun walk(dir: File, depth: Int): Long {
            val canonical = dir.canonicalPath
            if (!visited.add(canonical)) return dir.lastModified()
            var latest = dir.lastModified()
            if (depth >= 8) return latest
            val children = dir.listFiles() ?: return latest
            for (child in children) {
                if (child.isDirectory) {
                    val n = child.name
                    if (n == "target" || n == ".git" || n.startsWith(".")) continue
                    latest = maxOf(latest, walk(child, depth + 1))
                } else {
                    latest = maxOf(latest, child.lastModified())
                }
            }
            return latest
        }
        return walk(root, 0)
    }

    // ---- external folders (open in place, never copy) ----

    private fun readRegistry(): List<String> {
        if (!externalRegistry.isFile) return emptyList()
        return externalRegistry.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun writeRegistry(paths: List<String>) {
        externalRegistry.parentFile?.mkdirs()
        if (paths.isEmpty()) {
            externalRegistry.delete()
        } else {
            Fs.writeAtomic(externalRegistry, paths.joinToString("\n") + "\n")
        }
    }

    /** True when [dir] (canonicalized) is already opened as a project. */
    fun isRegisteredExternal(dir: File): Boolean =
        readRegistry().contains(dir.canonicalPath)

    /**
     * Remembers a folder anywhere on storage as a project. The folder is
     * NOT copied, moved or written to — it keeps living wherever the user
     * put it (Download, Documents, …) and every edit goes straight back to
     * it. Returns the registered dir (canonical), or null when it was
     * already registered (idempotent: no error, nothing changes).
     */
    fun registerExternal(dir: File): File? {
        val canonical = dir.canonicalPath
        val paths = readRegistry()
        if (canonical in paths) return null
        writeRegistry(paths + canonical)
        return File(canonical)
    }

    /** R4A: every registered external folder, readable right now or not. */
    fun registeredExternal(): List<File> = readRegistry().map(::File)

    /**
     * R4A (alcance de archivos): copies an external project INTO R4A's own
     * projects folder and forgets the external one. The original folder is
     * only read, never changed. Skips `target/` (build output, rebuilt) and
     * symlinks (never followed out of the folder). The copy gets a project
     * name R4A accepts ("R4A RUST PRUEBAS" -> "R4A_RUST_PRUEBAS"), with
     * `_2`, `_3`… when taken. Returns the new project folder.
     */
    fun copyIntoR4A(dir: File): File {
        val target = importFolder(dir, addCargo = false)
        unregisterExternal(dir)
        return target
    }

    /**
     * R4A (alcance de archivos): copies any folder into R4A's projects
     * (same rules as [copyIntoR4A]) without touching the original;
     * [addCargo] adds Cargo.toml + src/main.rs when missing. Returns the
     * new project folder.
     */
    fun importFolder(dir: File, addCargo: Boolean = true): File {
        if (!dir.isDirectory) throw IOException("'${dir.path}' is not a folder")
        val target = newProjectDir(dir.name)
        val tmp = File(projectsRoot, ".copy-" + target.name)
        Fs.deleteRecursively(tmp)
        try {
            copyTree(dir, tmp, top = true)
            if (!tmp.renameTo(target)) throw IOException("could not copy '${dir.name}'")
        } finally {
            if (tmp.exists()) Fs.deleteRecursively(tmp)
        }
        if (addCargo) ensureCargoProject(target, target.name)
        return target
    }

    /** R4A (copia de seguridad): a free project name from [base] (`_2`, `_3`… when taken). */
    fun freeProjectName(base: String): String = newProjectDir(base).name

    /** R4A (copia de seguridad): is [name] already used by a project folder? */
    fun projectNameTaken(name: String): Boolean = File(projectsRoot, name).exists()

    /** R4A (copia de seguridad): restores a backup .zip as the new project [name]; never overwrites. */
    fun restoreBackup(input: java.io.InputStream, name: String, withChats: Boolean, chatsDirFor: (File) -> File): File =
        ProjectBackup.restore(input, projectsRoot, name, withChats, chatsDirFor)

    /** A free folder in R4A's projects for a project called [folderName] (`_2`, `_3`… when taken). */
    private fun newProjectDir(folderName: String): File {
        projectsRoot.mkdirs()
        val base = internalNameFor(folderName)
        var target = File(projectsRoot, base)
        var n = 2
        while (target.exists()) target = File(projectsRoot, "${base}_${n++}")
        return target
    }

    /**
     * R4A (alcance de archivos): a folder read through Android's folder
     * picker (SAF), which needs no storage permission. Pure, so the copy
     * itself is tested on the JVM with a fake.
     */
    interface TreeSource {
        class Entry(val id: String, val name: String, val isDirectory: Boolean, val size: Long)
        fun children(id: String): List<Entry>
        fun open(id: String): java.io.InputStream
    }

    /**
     * R4A (alcance de archivos): imports a COPY of a folder picked with
     * Android's picker into R4A's projects (safe mode: no storage
     * permission). Skips `target/`, refuses odd names and folders bigger
     * than [maxBytes]; adds Cargo.toml + src/main.rs only when missing.
     * Returns the new project folder.
     */
    fun importTree(source: TreeSource, rootId: String, displayName: String, maxBytes: Long = 300L * 1024 * 1024): File {
        val target = newProjectDir(displayName)
        val tmp = File(projectsRoot, ".import-" + target.name)
        Fs.deleteRecursively(tmp)
        var total = 0L
        var files = 0
        fun copy(id: String, to: File, top: Boolean) {
            if (!to.mkdirs() && !to.isDirectory) throw IOException("could not create '${to.path}'")
            for (e in source.children(id)) {
                if (e.name.isEmpty() || e.name == "." || e.name == ".." || '/' in e.name || '\\' in e.name) continue
                if (top && e.name == "target") continue
                val dest = File(to, e.name)
                if (e.isDirectory) { copy(e.id, dest, top = false); continue }
                total += maxOf(0L, e.size)
                if (total > maxBytes || ++files > 50_000) throw IOException("the folder is too big to copy into R4A")
                source.open(e.id).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        try {
            copy(rootId, tmp, top = true)
            if (!tmp.renameTo(target)) throw IOException("could not copy '$displayName'")
        } finally {
            if (tmp.exists()) Fs.deleteRecursively(tmp)
        }
        ensureCargoProject(target, target.name)
        return target
    }

    private fun copyTree(from: File, to: File, top: Boolean) {
        if (!to.mkdirs() && !to.isDirectory) throw IOException("could not create '${to.path}'")
        val children = from.listFiles() ?: throw IOException("cannot read '${from.path}'")
        for (child in children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
            if (top && child.name == "target") continue
            val dest = File(to, child.name)
            if (child.isDirectory) copyTree(child, dest, top = false)
            else child.copyTo(dest, overwrite = false)
        }
    }

    /** Forgets an external folder. Its files are never touched. */
    fun unregisterExternal(dir: File) {
        val canonical = dir.canonicalPath
        writeRegistry(readRegistry().filter { it != canonical })
    }

    /**
     * Creates a project via `cargo new --vcs none --bin/--lib NAME`.
     * `--vcs none` because the bundle ships no git binary — with the
     * default `vcs = "git"` cargo new fails while trying to run `git init`.
     */
    suspend fun create(
        name: String,
        template: ProjectTemplate = ProjectTemplate.CONSOLE,
        onLine: (String) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
                "project name must start with a letter and contain only letters, digits, - and _"
            }
            projectsRoot.mkdirs()
            val dir = File(projectsRoot, name)
            if (dir.exists()) throw IOException("project '$name' already exists")

            // Keep the process output so a failure message can carry the
            // actual stderr instead of a bare exit code.
            val output = StringBuilder()
            val result = runner.run(
                listOf(cargoPath(), "new", "--vcs", "none", if (template.isLib) "--lib" else "--bin", name),
                cwd = projectsRoot,
                env = envProvider(),
                onLine = { line ->
                    synchronized(output) { output.appendLine(line.text) }
                    onLine(line.text)
                },
            )
            if (!result.success || !File(dir, "Cargo.toml").isFile) {
                Fs.deleteRecursively(dir)
                val tail = synchronized(output) { output.toString().trim() }
                    .lines().takeLast(12).joinToString("\n")
                throw IOException(
                    if (tail.isEmpty()) "cargo new failed (exit ${result.exitCode})"
                    else "cargo new failed (exit ${result.exitCode}):\n$tail"
                )
            }
            // R4A: template — extra crates in Cargo.toml + its example code
            if (template.dependencies.isNotBlank()) {
                val toml = File(dir, "Cargo.toml")
                // cargo new ends the manifest with an empty [dependencies]
                val base = toml.readText().trimEnd()
                val deps = if (base.endsWith("[dependencies]")) "" else "\n[dependencies]"
                Fs.writeAtomic(toml, base + deps + "\n" + template.dependencies)
            }
            template.code?.let { Fs.writeAtomic(File(dir, template.mainFile), it) }
            for ((path, bytes) in template.files) {
                File(dir, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
            dir
        }

    /**
     * Scaffold a minimal, fully valid cargo bin project WITHOUT touching
     * the toolchain (`cargo new` needs a working rustc; a user opening a
     * .rs attachment may not have installed one yet). Used by the
     * open-a-.rs flow's "new project" branch — same shape as `cargo new`:
     * Cargo.toml + src/main.rs.
     */
    fun createStandaloneProject(name: String): File {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
            "project name must start with a letter and contain only letters, digits, - and _"
        }
        projectsRoot.mkdirs()
        val dir = File(projectsRoot, name)
        if (dir.exists()) throw IOException("project '$name' already exists")
        dir.resolve("src").mkdirs()
        Fs.writeAtomic(
            dir.resolve("Cargo.toml"),
            "[package]\nname = \"$name\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
        )
        Fs.writeAtomic(dir.resolve("src/main.rs"), TEMPLATE_MAIN_RS)
        return dir
    }

    /**
     * Turns an arbitrary folder into a cargo project IN PLACE: writes
     * Cargo.toml and src/main.rs into [dir] only when missing — existing
     * files are never overwritten (they are the user's). Used by the
     * open-folder-as-project flow when the picked folder has no manifest.
     */
    fun ensureCargoProject(dir: File, packageName: String): File {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
            "package name must start with a letter and contain only letters, digits, - and _"
        }
        if (!File(dir, "Cargo.toml").isFile) {
            Fs.writeAtomic(
                dir.resolve("Cargo.toml"),
                "[package]\nname = \"$packageName\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
            )
        }
        if (!File(dir, "src/main.rs").isFile && !File(dir, "src/lib.rs").isFile) {
            dir.resolve("src").mkdirs()
            Fs.writeAtomic(dir.resolve("src/main.rs"), TEMPLATE_MAIN_RS)
        }
        return dir
    }

    fun rename(old: File, newName: String): File {
        require(newName.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "invalid name" }
        val target = File(old.parentFile, newName)
        if (target.exists()) throw IOException("'$newName' already exists")
        if (!old.renameTo(target)) throw IOException("rename failed")
        return target
    }

    fun delete(dir: File) {
        if (isRegisteredExternal(dir)) {
            // external folders belong to the user — forgetting them must
            // never delete the underlying files
            throw IOException("external folder — remove it from the list instead")
        }
        if (!Fs.deleteRecursively(dir)) throw IOException("could not delete ${dir.name}")
    }

    // ---- file tree for the editor ----

    /**
     * Files under the project, skipping target/ and .git/.
     *
     * External bug report (confirmed): unlike [latestMtime], this walk had
     * no guard against a symlink loop (`ln -s . self` inside a project,
     * or any symlink whose target chain eventually points back at an
     * ancestor) — `child.isDirectory` is true for a symlinked directory,
     * so the recursion had no base case and opening the editor on such a
     * project was a guaranteed StackOverflowError. Same
     * visited-canonical-path guard as [latestMtime], reused here.
     */
    fun fileTree(projectDir: File, allFiles: Boolean): List<dev.rustdroid.ide.model.FileNode> {
        val out = mutableListOf<dev.rustdroid.ide.model.FileNode>()
        val visited = HashSet<String>()
        fun walk(dir: File, depth: Int) {
            val canonical = dir.canonicalPath
            if (!visited.add(canonical)) return
            val children = dir.listFiles() ?: return
            val sorted = children.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            for (child in sorted) {
                val rel = child.relativeTo(projectDir).path
                if (child.isDirectory) {
                    if (child.name == "target" || child.name == ".git" || depth == 0 && child.name == ".cargo") {
                        continue
                    }
                    out += dev.rustdroid.ide.model.FileNode(child, rel, true, depth)
                    walk(child, depth + 1)
                } else {
                    if (!allFiles && !isEditable(child.name)) continue
                    out += dev.rustdroid.ide.model.FileNode(child, rel, false, depth)
                }
            }
        }
        walk(projectDir, 0)
        return out
    }

    private fun isEditable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        // R4A: files without an extension (main, Makefile, LICENSE, ...) are
        // shown too — a file saved without ".rs" used to vanish from Sources
        return ext.isEmpty() || name == "Cargo.toml" || name == "Cargo.lock" ||
            ext in setOf("rs", "toml", "md", "txt", "json", "yml", "yaml", "lock", "cfg", "sh")
    }

    fun readFile(file: File): String {
        if (file.length() > 4L * 1024 * 1024) throw IOException("file too large to edit")
        val bytes = file.readBytes()
        // R4A: images, audio, etc. are listed in the tree but never opened as
        // text — saving them from the editor would corrupt them
        if (bytes.take(8192).any { it == 0.toByte() }) throw IOException("not a text file")
        return String(bytes, Charsets.UTF_8)
    }

    fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        Fs.writeAtomic(file, content)
    }

    /**
     * Creates an empty file at [relativePath] inside [projectDir], making
     * parent directories as needed. Traversal/escape is rejected via
     * [Fs.resolveChild]; an existing file is never overwritten.
     */
    fun createFile(projectDir: File, relativePath: String): File {
        val rel = relativePath.trim()
        if (rel.isEmpty()) throw IOException("file name is empty")
        val file = Fs.resolveChild(projectDir, rel)
        if (file.isDirectory) throw IOException("'$rel' is a directory")
        if (file.exists()) throw IOException("'$rel' already exists")
        file.parentFile?.mkdirs()
        Fs.writeAtomic(file, "")
        return file
    }

    /** R4A: crea una carpeta vacía (y las de arriba) dentro del proyecto. */
    fun createFolder(projectDir: File, relativePath: String): File {
        val rel = relativePath.trim().trimEnd('/')
        if (rel.isEmpty()) throw IOException("folder name is empty")
        val dir = Fs.resolveChild(projectDir, rel)
        if (dir.exists()) throw IOException("'$rel' already exists")
        if (!dir.mkdirs()) throw IOException("could not create '$rel'")
        return dir
    }

    /**
     * Deletes a file — or a whole directory tree — at [relativePath] inside
     * [projectDir]. Same safety envelope as [createFile]: traversal/escape
     * is rejected via [Fs.resolveChild], and two more guards keep the
     * project itself intact: the project root ("." or an empty path) and
     * the root Cargo.toml (a folder without a manifest stops being a
     * project — remove the whole project from the Home screen instead)
     * are refused. Deleting the manifest of a nested workspace member is
     * allowed: that is a deliberate, advanced edit.
     */
    fun deleteFile(projectDir: File, relativePath: String) {
        val rel = relativePath.trim().replace('\\', '/')
        if (rel.isEmpty() || rel == ".") throw IOException("nothing to delete")
        val target = Fs.resolveChild(projectDir, rel)
        if (target.canonicalPath == projectDir.canonicalPath) {
            throw IOException("cannot delete the project root")
        }
        if (target.parentFile?.canonicalPath == projectDir.canonicalPath &&
            target.name == "Cargo.toml"
        ) {
            throw IOException(
                "Cargo.toml is what makes this folder a project — " +
                    "delete the whole project from the Home screen instead",
            )
        }
        if (!target.exists()) throw IOException("'$rel' does not exist")
        if (!Fs.deleteRecursively(target)) throw IOException("could not delete '$rel'")
    }

    /**
     * R4A: renames or moves a file or folder inside [projectDir] — changing
     * only the name renames, changing the folders moves ("src/a.rs" ->
     * "src/juego/a.rs"). Same guards as [deleteFile]: no escaping the
     * project, never the root nor the root Cargo.toml, never over an
     * existing file, and a folder can not go inside itself.
     */
    fun moveFile(projectDir: File, from: String, to: String): File {
        val src = from.trim().replace('\\', '/').trim('/')
        val dst = to.trim().replace('\\', '/').trim('/')
        if (src.isEmpty() || dst.isEmpty()) throw IOException("the name is empty")
        val source = Fs.resolveChild(projectDir, src)
        val target = Fs.resolveChild(projectDir, dst)
        val root = projectDir.canonicalPath
        if (source.canonicalPath == root || target.canonicalPath == root) {
            throw IOException("cannot move the project root")
        }
        if (source.parentFile?.canonicalPath == root && source.name == "Cargo.toml") {
            throw IOException("Cargo.toml is what makes this folder a project — it stays where it is")
        }
        if (!source.exists()) throw IOException("'$src' does not exist")
        if (source.canonicalPath == target.canonicalPath) return target
        if (target.exists()) throw IOException("'$dst' already exists")
        if (source.isDirectory && (target.canonicalPath + "/").startsWith(source.canonicalPath + "/")) {
            throw IOException("a folder can not go inside itself")
        }
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            // renameTo can fail across mount points: copy, then delete
            if (!source.copyRecursively(target) || !Fs.deleteRecursively(source)) {
                throw IOException("could not move '$src'")
            }
        }
        return target
    }

    // ---- .rs intake (ACTION_VIEW from file managers) ----

    /**
     * Places incoming .rs content into a project as `src/<name>`. A name
     * that already exists gets a `-1`, `-2`… suffix instead of being
     * overwritten. R4A: main.rs only replaces the existing one when
     * [replaceMain] is true (a project just created for this file, whose
     * main.rs is the template stub) — never in a project the user already had.
     * Returns the path relative to the project root (for opening a tab).
     * Works for internal AND external projects — the caller picks the
     * destination; this method only writes into [projectDir].
     */
    fun importRsContent(projectDir: File, fileName: String, content: String, replaceMain: Boolean = false): String {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (clean.isEmpty() || !clean.endsWith(".rs")) {
            throw IOException("not a Rust source name: '$fileName'")
        }
        if (!clean.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw IOException("unsupported file name: '$clean'")
        }
        val src = projectDir.resolve("src")
        src.mkdirs() // destination projects may predate src/ (plain folders)
        var target = src.resolve(clean)
        if (clean == "main.rs" && replaceMain) {
            // the template stub exists precisely to be replaced
            Fs.writeAtomic(target, content)
            return "src/main.rs"
        }
        var n = 0
        while (target.exists()) {
            n += 1
            target = src.resolve(clean.removeSuffix(".rs") + "-$n.rs")
        }
        Fs.writeAtomic(target, content)
        return target.relativeTo(projectDir).path
    }

    companion object {
        /**
         * R4A: a project name R4A accepts (same rule as [rename]: a letter,
         * then letters, digits, `-` and `_`) made from any folder name.
         */
        fun internalNameFor(folderName: String): String {
            val clean = folderName.trim()
                .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                .trim('_')
            return when {
                clean.isEmpty() -> "proyecto"
                clean[0].isLetter() -> clean
                else -> "p_$clean"
            }
        }

        /** src/main.rs stub for toolchain-free project scaffolds. */
        const val TEMPLATE_MAIN_RS =
            "fn main() {\n    println!(\"Hello from R4A!\");\n}\n"

        /** R4A: dependencies of the game template. */
        const val GAME_DEPENDENCIES =
            "macroquad = { version = \"0.4\", default-features = false }\n" +
                "# fijado: las versiones nuevas de fontdue piden Rust 1.87\n" +
                "fontdue = \"=0.9.3\"\n"

        /** R4A: game template — a ball that bounces and follows your finger. */
        /** R4A: the game template's 16x16 pixel-art ball (assets/pelota.png). */
        val GAME_BALL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAgUlEQVR42mNkwAL+92j8xybOWHKDEUOMGI34DGLEqjkqD0Ivm0TQIBYUzTCNJAAmnDIEbIcbgNXfRGo+ESD3n4UcjcR5gQA4eeQbZQZQ5AKY7dRxAbbkSaztFhseMbKQoxFrXsCVD3BptNjwiBEjM50IkCMqM8E0YxhAyCBkjTAAAOZmNk8qdU26AAAAAElFTkSuQmCC",
        )

        val GAME_MAIN_RS = """
            |use macroquad::prelude::*;
            |
            |// Tu juego: la pelota rebota en los bordes y sigue tu dedo.
            |// ▶ lo compila y lo abre como app. Atrás vuelve al editor.
            |#[macroquad::main("Mi juego")]
            |async fn main() {
            |    // Imágenes: ponelas en la carpeta assets/ del proyecto y cargalas
            |    // por su nombre. Van solas dentro de la APK al exportar.
            |    // set_pc_assets_folder hace que el mismo código ande en una PC.
            |    set_pc_assets_folder("assets");
            |    let pelota = load_texture("pelota.png").await.expect("falta assets/pelota.png");
            |    pelota.set_filter(FilterMode::Nearest); // pixel art nítido, sin borronear
            |
            |    let mut pos = vec2(200.0, 300.0);
            |    let mut vel = vec2(400.0, 500.0);
            |    let radio = 60.0;
            |
            |    loop {
            |        // tiempo desde el cuadro anterior, con un tope: si el juego
            |        // estuvo en pausa (fuiste al código o a otra app), sin el tope
            |        // la pelota "saltaría" todo lo que se movería en ese tiempo
            |        let dt = get_frame_time().min(1.0 / 20.0);
            |        let (ancho, alto) = (screen_width(), screen_height());
            |
            |        pos += vel * dt;
            |        if pos.x < radio { vel.x = vel.x.abs(); }
            |        if pos.x > ancho - radio { vel.x = -vel.x.abs(); }
            |        if pos.y < radio { vel.y = vel.y.abs(); }
            |        if pos.y > alto - radio { vel.y = -vel.y.abs(); }
            |        // que no quede afuera si la pantalla cambia de tamaño (al girar el celu)
            |        pos.x = pos.x.min(ancho - radio).max(radio);
            |        pos.y = pos.y.min(alto - radio).max(radio);
            |
            |        // tocá la pantalla para mover la pelota
            |        for toque in touches() {
            |            pos = toque.position;
            |        }
            |
            |        clear_background(Color::from_rgba(30, 28, 40, 255));
            |        // la imagen, del tamaño de la pelota y centrada en pos
            |        draw_texture_ex(
            |            &pelota,
            |            pos.x - radio,
            |            pos.y - radio,
            |            WHITE,
            |            DrawTextureParams { dest_size: Some(vec2(radio * 2.0, radio * 2.0)), ..Default::default() },
            |        );
            |        draw_text("Hola desde R4A", 40.0, 120.0, 80.0, WHITE);
            |        next_frame().await
            |    }
            |}
            |""".trimMargin()

        /**
         * Maps a SAF tree document id to an absolute filesystem path:
         * `primary:Download/proj` → `<primaryStorage>/Download/proj`,
         * `ABCD-1234:proj` → `/storage/ABCD-1234/proj` (removable volume).
         * Null for anything else (cloud providers, usb, downloads providers
         * — they have no real path cargo could use). Pure string work: the
         * Android side (Uri → document id) stays a thin, untestable shell
         * around this.
         */
        fun documentIdToPath(documentId: String, primaryStoragePath: String): String? {
            val idx = documentId.indexOf(':')
            if (idx <= 0) return null
            val volume = documentId.substring(0, idx)
            val rest = documentId.substring(idx + 1)
            val base = when {
                volume == "primary" -> primaryStoragePath
                Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(volume) -> "/storage/$volume"
                else -> null
            } ?: return null
            return if (rest.isEmpty()) base else "$base/$rest"
        }
    }
}
