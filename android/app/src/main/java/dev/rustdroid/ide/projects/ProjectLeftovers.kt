package dev.rustdroid.ide.projects

import dev.rustdroid.ide.util.Fs
import java.io.File
import java.security.MessageDigest

/**
 * R4A (auditoría B18): lo que R4A guarda de un proyecto FUERA de su carpeta,
 * con una clave sacada de su ruta canónica. Antes, borrar un proyecto dejaba
 * todo esto (cientos de MB de compilaciones) y renombrarlo "perdía" sus
 * charlas (quedaban bajo la clave de la ruta vieja).
 *
 * - Cachés (se rearman solos): `check/<k>` (errores en vivo),
 *   `games/<k>` (compilaciones del juego), `build/<k>` (target de proyectos
 *   externos). k = 16 hex del SHA-256 de la ruta.
 * - Del usuario: `chats/<sha1>` (charlas y cambios) y `export-icons/<k>.png`.
 *
 * Mantener en sincronía con LiveChecker.key, GameBuild.key,
 * ProcEnv.redirectedTargetDir, ChatStore.project y EditorViewModel.exportIconFile.
 */
object ProjectLeftovers {

    fun canonical(projectDir: File): String =
        runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)

    fun caches(filesDir: File, canonicalPath: String): List<File> {
        val k = key16(canonicalPath)
        return listOf(File(filesDir, "check/$k"), File(filesDir, "games/$k"), File(filesDir, "build/$k"))
    }

    fun chatsDir(filesDir: File, canonicalPath: String): File =
        File(filesDir, "chats/" + hex(MessageDigest.getInstance("SHA-1").digest(canonicalPath.toByteArray())))

    fun exportIcon(filesDir: File, canonicalPath: String): File =
        File(filesDir, "export-icons/${key16(canonicalPath)}.png")

    /** Borrar el proyecto: cachés y, si [userData], también charlas e ícono. */
    fun delete(filesDir: File, canonicalPath: String, userData: Boolean) {
        caches(filesDir, canonicalPath).forEach { Fs.deleteRecursively(it) }
        if (userData) {
            Fs.deleteRecursively(chatsDir(filesDir, canonicalPath))
            exportIcon(filesDir, canonicalPath).delete()
        }
    }

    /** Renombrar: charlas e ícono pasan a la clave nueva; los cachés viejos se borran. */
    fun move(filesDir: File, oldCanonical: String, newCanonical: String) {
        caches(filesDir, oldCanonical).forEach { Fs.deleteRecursively(it) }
        val oldChats = chatsDir(filesDir, oldCanonical)
        val newChats = chatsDir(filesDir, newCanonical)
        if (oldChats.isDirectory && !newChats.exists()) {
            newChats.parentFile?.mkdirs()
            if (!oldChats.renameTo(newChats)) {
                oldChats.copyRecursively(newChats, overwrite = false)
                Fs.deleteRecursively(oldChats)
            }
        }
        val oldIcon = exportIcon(filesDir, oldCanonical)
        val newIcon = exportIcon(filesDir, newCanonical)
        if (oldIcon.isFile && !newIcon.exists()) oldIcon.renameTo(newIcon)
    }

    /**
     * Cachés de proyectos que ya no están en la lista (borrados antes de
     * este arreglo, renombrados, o carpetas externas quitadas). Se rearman
     * solos si el proyecto vuelve.
     */
    fun orphanCaches(filesDir: File, keepCanonicals: Collection<String>): List<File> {
        val keep = keepCanonicals.map(::key16).toSet()
        return listOf("check", "games", "build")
            .flatMap { File(filesDir, it).listFiles()?.toList().orEmpty() }
            .filter { it.isDirectory && it.name.length == 16 && it.name !in keep }
    }

    private fun key16(canonicalPath: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(canonicalPath.toByteArray())).take(16)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
