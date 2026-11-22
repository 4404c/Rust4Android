package dev.rustdroid.ide.runtime

import java.io.File

/**
 * R4A: mirrors a project into app storage so cargo can work on a copy —
 * the user's folder is never touched. Only changed files are copied, so
 * repeated syncs stay cheap. Used by live checks and by game builds.
 */
object ShadowCopy {

    /**
     * Mirror [src] into [dst], skipping build output and VCS. Paths in
     * [force] are always re-copied; paths in [skip] are left alone in [dst]
     * (the caller writes them itself) and never deleted.
     */
    fun sync(src: File, dst: File, force: Set<String> = emptySet(), skip: Set<String> = emptySet()) {
        dst.mkdirs()
        val seen = HashSet<String>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                val rel = c.relativeTo(src).path
                if (c.isDirectory) {
                    if (c.name == "target" || c.name == ".git") continue
                    seen += rel
                    walk(c)
                } else if (c.isFile) {
                    seen += rel
                    if (rel in skip) continue
                    val out = File(dst, rel)
                    if (rel in force || !out.isFile || out.length() != c.length() ||
                        out.lastModified() < c.lastModified()
                    ) {
                        out.parentFile?.mkdirs()
                        // fresh mtime on purpose: cargo decides what to
                        // re-check by mtime, so an old one could hide a change
                        c.copyTo(out, overwrite = true)
                    }
                }
            }
        }
        walk(src)
        // drop files that no longer exist in the project (keep our target/)
        dst.walkBottomUp().forEach { f ->
            if (f == dst) return@forEach
            val rel = f.relativeTo(dst).path
            if (rel == "target" || rel.startsWith("target/")) return@forEach
            if (rel in skip) return@forEach
            if (rel !in seen) f.delete()
        }
    }

    /** Write [text] to [file] only when it differs (keeps the mtime stable). */
    fun writeIfChanged(file: File, text: String) {
        if (file.isFile && file.readText() == text) return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
