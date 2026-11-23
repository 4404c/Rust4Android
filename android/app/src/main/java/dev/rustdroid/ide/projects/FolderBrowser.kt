package dev.rustdroid.ide.projects

import java.io.File
import java.io.IOException

/**
 * R4A: the data behind R4A's own folder picker. Android's system picker
 * (OpenDocumentTree) refuses some folders "to protect your privacy" on
 * several devices; R4A does not need it — at targetSdk 28 with the storage
 * permission the plain File API reaches shared storage. Pure (JVM-tested).
 */
object FolderBrowser {

    /** Sub-folders of [dir] to show: no hidden ones, sorted by name. */
    fun folders(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * Path pieces from [root] to [dir] for the breadcrumb: the root first
     * (shown with its own label), then each folder name.
     */
    fun crumbs(root: File, dir: File): List<File> {
        val out = ArrayList<File>()
        var f: File? = dir.canonicalFile
        val r = root.canonicalFile
        while (f != null) {
            out += f
            if (f == r) break
            f = f.parentFile
        }
        return if (out.lastOrNull() == r) out.reversed() else listOf(dir)
    }

    /** Can we go one level up without leaving [root]? */
    fun canGoUp(root: File, dir: File): Boolean =
        dir.canonicalFile != root.canonicalFile &&
            dir.canonicalPath.startsWith(root.canonicalPath + File.separator)

    private val BAD_NAME = Regex("""[\\/:*?"<>|]""")

    /** Create a sub-folder [name] in [dir]; readable error when not possible. */
    fun createFolder(dir: File, name: String): File {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == "..") throw IOException("the name is empty")
        if (BAD_NAME.containsMatchIn(n)) throw IOException("a folder name can not contain \\ / : * ? \" < > |")
        val f = File(dir, n)
        if (f.exists()) throw IOException("'$n' already exists")
        if (!f.mkdir()) throw IOException("could not create '$n'")
        return f
    }
}
