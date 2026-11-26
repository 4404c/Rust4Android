package dev.rustdroid.ide.projects

import java.io.File

/**
 * R4A: find text in every file of a project (Buscar → "en todo el
 * proyecto"). Skips build output (`target/`), hidden folders and files that
 * are not text. [unsaved] (path → text) wins over the file on disk, so open
 * tabs with edits are searched as they are on screen.
 */
object ProjectSearch {

    data class Hit(val path: String, val line: Int, val col: Int, val preview: String)

    data class Result(val hits: List<Hit>, val truncated: Boolean)

    private const val MAX_FILE_BYTES = 1_000_000L

    fun search(
        projectDir: File,
        query: String,
        matchCase: Boolean,
        unsaved: Map<String, String> = emptyMap(),
        limit: Int = 500,
    ): Result {
        if (query.isEmpty()) return Result(emptyList(), false)
        val hits = ArrayList<Hit>()
        for ((path, file) in files(projectDir)) {
            val text = unsaved[path] ?: readText(file) ?: continue
            // one result per line (a line with the word twice shows once)
            text.lineSequence().forEachIndexed { i, line ->
                val at = line.indexOf(query, ignoreCase = !matchCase)
                if (at >= 0) {
                    if (hits.size >= limit) return Result(hits, true)
                    hits += Hit(path, i + 1, at + 1, line.trim().take(160))
                }
            }
        }
        return Result(hits, false)
    }

    /** Project-relative path → file, sorted, without build output or hidden folders. */
    private fun files(projectDir: File): List<Pair<String, File>> =
        projectDir.walkTopDown()
            .onEnter { it == projectDir || !(it.name == "target" || it.name.startsWith(".")) }
            .filter { it.isFile }
            .map { it.relativeTo(projectDir).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()

    /** Null for big or binary files (a NUL byte in the first 8 KB). */
    private fun readText(file: File): String? {
        if (file.length() > MAX_FILE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        for (i in 0 until minOf(bytes.size, 8192)) if (bytes[i] == 0.toByte()) return null
        return bytes.toString(Charsets.UTF_8)
    }
}
