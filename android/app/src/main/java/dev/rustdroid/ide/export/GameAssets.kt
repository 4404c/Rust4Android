package dev.rustdroid.ide.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R4A: a game's files for macroquad's `load_texture` / `load_file`. On
 * Android macroquad only reads from the APK's assets, so these files go into
 * the exported APK (`assets/<name>`) and, for ▶, into a small zip that
 * GameActivity serves as its assets.
 *
 * Files come from `assets/` at the project root (macroquad's convention) and
 * also `src/assets/`; on a name clash the root wins.
 */
object GameAssets {

    val FOLDERS = listOf("src/assets", "assets")

    /** Asset name (path inside the folder, with `/`) → file. */
    fun collect(projectDir: File): Map<String, File> {
        val out = sortedMapOf<String, File>()
        for (folder in FOLDERS) {
            val dir = File(projectDir, folder)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                out[f.relativeTo(dir).invariantSeparatorsPath] = f
            }
        }
        return out
    }

    /**
     * Write [assets] as an assets-only APK-style zip (`assets/<name>`) that
     * an AssetManager can open. Returns false (and writes nothing) when
     * there are no assets.
     */
    fun writeZip(assets: Map<String, File>, out: File): Boolean {
        out.delete()
        if (assets.isEmpty()) return false
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for ((name, file) in assets) {
                z.putNextEntry(ZipEntry("assets/$name"))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return true
    }
}
