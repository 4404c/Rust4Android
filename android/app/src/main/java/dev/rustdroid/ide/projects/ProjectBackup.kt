package dev.rustdroid.ide.projects

import dev.rustdroid.ide.util.Fs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * R4A: copia de seguridad de UN proyecto en un `.zip` que el usuario guarda
 * donde quiera (con el «Guardar como…» de Android, sin permiso de
 * almacenamiento). Pura JVM: se prueba en la PC.
 *
 * Adentro del zip:
 * - `r4a-backup.json` (primera entrada): formato, nombre del proyecto, si trae charlas.
 * - `project/…`: los archivos del proyecto, sin `target/` y sin symlinks.
 * - `chats/…`: las charlas de la IA de ese proyecto (opcional).
 *
 * Restaurar nunca pisa nada: el proyecto va a una carpeta que no existe, se
 * arma primero en una carpeta temporal y recién al final se mueve. Cada
 * entrada pasa por [Fs.resolveChild] (nada de `..` ni rutas absolutas) y
 * se cuentan los bytes REALES que salen del zip (no lo que dice el
 * encabezado), con tope [MAX_BYTES] y [MAX_FILES]. Los symlinks no existen
 * para `java.util.zip`: cualquier entrada se escribe como archivo común.
 */
object ProjectBackup {

    const val MANIFEST = "r4a-backup.json"
    const val FORMAT = 1
    const val MAX_BYTES = 300L * 1024 * 1024
    const val MAX_FILES = 50_000
    private const val PROJECT = "project/"
    private const val CHATS = "chats/"
    private val NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]*")

    class Info(val name: String, val hasChats: Boolean)

    /** `<proyecto>-AAAA-MM-DD.zip` */
    fun suggestedFileName(projectName: String, date: java.util.Date = java.util.Date()): String =
        projectName + "-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(date) + ".zip"

    /** Bytes que se van a copiar (para la barra de progreso). */
    fun sizeToBackUp(projectDir: File, chatsDir: File?): Long =
        files(projectDir, skipTarget = true).sumOf { it.second.length() } +
            (chatsDir?.takeIf { it.isDirectory }?.let { files(it, false).sumOf { f -> f.second.length() } } ?: 0L)

    /**
     * Escribe la copia de [projectDir] (y de [chatsDir] si no es null) en [out].
     * [onProgress] recibe los bytes copiados hasta ahora. Devuelve cuántos
     * bytes del proyecto y las charlas entraron.
     */
    fun write(
        projectDir: File,
        projectName: String,
        chatsDir: File?,
        out: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long {
        if (!projectDir.isDirectory) throw IOException("'${projectDir.path}' is not a folder")
        val chats = chatsDir?.takeIf { it.isDirectory }
        var done = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifest = buildJsonObject {
                put("format", FORMAT)
                put("name", projectName)
                put("chats", chats != null)
                put("createdAt", System.currentTimeMillis())
            }
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            fun add(prefix: String, list: List<Pair<String, File>>) {
                for ((rel, f) in list) {
                    zip.putNextEntry(ZipEntry(prefix + rel).apply { time = f.lastModified() })
                    f.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                    zip.closeEntry()
                }
            }
            add(PROJECT, files(projectDir, skipTarget = true))
            if (chats != null) add(CHATS, files(chats, skipTarget = false))
        }
        return done
    }

    /** Lee el manifiesto (la primera entrada). Si no está, no es una copia de R4A. */
    fun readInfo(input: InputStream): Info {
        ZipInputStream(input.buffered()).use { zip ->
            val first = zip.nextEntry ?: throw IOException(NOT_A_BACKUP)
            if (first.name != MANIFEST) throw IOException(NOT_A_BACKUP)
            val text = readLimited(zip, 64 * 1024).toString(Charsets.UTF_8)
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(NOT_A_BACKUP) }
            fun field(k: String) = (json[k] as? JsonPrimitive)
            if (field("format")?.intOrNull != FORMAT) throw IOException("this backup was made by a newer R4A")
            val name = field("name")?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: "proyecto"
            return Info(ProjectRepository.internalNameFor(name), field("chats")?.booleanOrNull == true)
        }
    }

    /**
     * Restaura la copia en [projectsRoot]/[name], que NO tiene que existir.
     * Las charlas (si vienen y [withChats]) se dejan en [chatsDirFor] del
     * proyecto nuevo, sin pisar archivos que ya estén. Devuelve la carpeta nueva.
     */
    fun restore(
        input: InputStream,
        projectsRoot: File,
        name: String,
        withChats: Boolean,
        chatsDirFor: (File) -> File,
    ): File {
        if (!name.matches(NAME)) throw IOException("invalid name: $name")
        projectsRoot.mkdirs()
        val target = File(projectsRoot, name)
        if (target.exists()) throw IOException("'$name' already exists")
        val tmp = File(projectsRoot, ".restore-$name")
        Fs.deleteRecursively(tmp)
        try {
            val tmpProject = File(tmp, "project").apply { mkdirs() }
            val tmpChats = File(tmp, "chats").apply { mkdirs() }
            extract(input, tmpProject, tmpChats)
            if (target.exists()) throw IOException("'$name' already exists")
            if (!tmpProject.renameTo(target)) throw IOException("could not restore '$name'")
            if (withChats && tmpChats.list()?.isNotEmpty() == true) {
                moveWithoutOverwrite(tmpChats, chatsDirFor(target))
            }
        } finally {
            Fs.deleteRecursively(tmp)
        }
        return target
    }

    /** Valida y saca las entradas: `project/…` a [projectDir], `chats/…` a [chatsDir]. */
    internal fun extract(input: InputStream, projectDir: File, chatsDir: File) {
        var total = 0L
        var count = 0
        var sawManifest = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (!sawManifest) {
                    if (name != MANIFEST) throw IOException(NOT_A_BACKUP)
                    sawManifest = true
                    continue
                }
                if (++count > MAX_FILES) throw IOException(TOO_BIG)
                val (root, rel) = when {
                    name.startsWith(PROJECT) -> projectDir to name.removePrefix(PROJECT)
                    name.startsWith(CHATS) -> chatsDir to name.removePrefix(CHATS)
                    else -> throw IOException("unexpected entry in backup: $name")
                }
                if (rel.isEmpty()) continue
                // nada de `..`, rutas absolutas, `C:` ni `\`
                if (rel.contains(':') || rel.contains('\\') || rel.contains('\u0000')) {
                    throw IOException("illegal entry path: $name")
                }
                val dest = Fs.resolveChild(root, rel.trimEnd('/'))
                Fs.requireInside(root, dest)
                if (e.isDirectory || name.endsWith("/")) {
                    if (!dest.mkdirs() && !dest.isDirectory) throw IOException("could not create '$rel'")
                    continue
                }
                dest.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("could not create '$rel'") }
                if (dest.exists()) throw IOException("duplicate entry in backup: $name")
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException(TOO_BIG)
                        out.write(buf, 0, n)
                    }
                }
                if (e.time > 0) dest.setLastModified(e.time)
            }
        }
        if (!sawManifest) throw IOException(NOT_A_BACKUP)
    }

    private const val NOT_A_BACKUP = "this file is not an R4A project backup"
    private const val TOO_BIG = "the backup is too big to restore"

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException(NOT_A_BACKUP)
        }
        return out.toByteArray()
    }

    /** Archivos comunes bajo [root] ("a/b.rs" -> File), sin symlinks; [skipTarget] saca `target/` de arriba. */
    private fun files(root: File, skipTarget: Boolean): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        fun walk(dir: File, prefix: String, top: Boolean) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (c in children) {
                if (Files.isSymbolicLink(c.toPath())) continue
                if (top && skipTarget && c.name == "target") continue
                if (c.isDirectory) walk(c, prefix + c.name + "/", false)
                else if (c.isFile) out += (prefix + c.name) to c
            }
        }
        walk(root, "", true)
        return out
    }

    private fun moveWithoutOverwrite(from: File, to: File) {
        if (!to.exists()) {
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) return
        }
        to.mkdirs()
        for (c in from.listFiles().orEmpty()) {
            val dest = File(to, c.name)
            if (c.isDirectory) moveWithoutOverwrite(c, dest)
            else if (!dest.exists() && !c.renameTo(dest)) c.copyTo(dest, overwrite = false)
        }
    }
}
