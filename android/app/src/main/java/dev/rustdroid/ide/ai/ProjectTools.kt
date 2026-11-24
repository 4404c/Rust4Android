package dev.rustdroid.ide.ai

import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.runtime.LiveDiagnostic
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * R4A: herramientas de la IA sobre el proyecto abierto.
 * Fase 2: leer (archivos dentro del proyecto, nunca afuera; Problems; lo
 * que hay abierto en el editor, incluido el texto sin guardar).
 * Fase 3: escribir. Cada cambio devuelve un [Change] con el contenido de
 * antes, para que el chat muestre una tarjeta con "Deshacer".
 * Fase 4: compilar (cargo check) y ejecutar (cargo run) en una copia.
 * Crear proyectos NO va acá: eso es del chat de Inicio ([HomeTools]).
 */
class ProjectTools(
    val bridge: Bridge,
    /** Las charlas del proyecto (read_chat); null = no se ofrecen esas herramientas. */
    private val history: ChatHistory? = null,
) : ChatTools {

    /** En modo Plan: mirar, compilar para ver errores y leer charlas; nada que cambie archivos. */
    override val planTools = setOf(
        "list_files", "read_file", "search_text", "get_problems", "get_editor_state",
        "cargo_check", "list_chats", "read_chat",
    )

    /** Lo que las herramientas necesitan del editor. */
    interface Bridge {
        val projectDir: File
        /** Árbol del proyecto (sin target/ ni .git). */
        fun tree(): List<FileNode>
        /** Texto en el editor si el archivo está abierto (puede no estar guardado). */
        fun bufferText(relativePath: String): String?
        fun activeFile(): String?
        fun unsavedFiles(): List<String>
        /** Problems de la última ejecución (▶). */
        fun runProblems(): List<Diagnostic>
        /** Errores en vivo (cargo check mientras se escribe). */
        fun liveProblems(): List<LiveDiagnostic>
        /**
         * La IA (o Deshacer) cambió un archivo en disco: el editor lo
         * recarga si está abierto ([content] null = se borró).
         */
        fun onExternalWrite(relativePath: String, content: String?)
        /** Fase 4: cargo check ahora mismo. null = no hay toolchain instalada. */
        suspend fun cargoCheck(): dev.rustdroid.ide.runtime.LiveResult?
        /** Fase 4: cargo run (sin stdin). null = no hay toolchain instalada. */
        suspend fun runProgram(onLine: (String) -> Unit): dev.rustdroid.ide.model.RunResult?
        fun isGame(): Boolean
    }

    /**
     * Un cambio hecho por la IA. [before] null = el archivo no existía;
     * [after] null = se borró. [isDir] = carpeta creada.
     */
    data class Change(
        val projectDir: File,
        val path: String,
        val before: String?,
        val after: String?,
        val isDir: Boolean = false,
    )

    /**
     * Resultado para el modelo + una línea corta para mostrar en el chat.
     * [change]: se puede deshacer. [newProjectRef]: proyecto creado (botón Abrir).
     */
    data class Outcome(
        val result: String,
        val summary: String,
        val change: Change? = null,
        val newProjectRef: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override val definitions: JsonArray = buildJsonArray {
        tool(
            "list_files",
            "Lista los archivos y carpetas del proyecto abierto (rutas relativas a la raíz). Las carpetas terminan en /.",
        )
        tool(
            "read_file",
            "Lee un archivo de texto del proyecto. Si está abierto en el editor devuelve lo que se ve, aunque no esté guardado.",
            "path" to "Ruta relativa a la raíz del proyecto, por ejemplo src/main.rs",
        )
        tool(
            "search_text",
            "Busca un texto (sin distinguir mayúsculas) en los archivos de código del proyecto. Devuelve ruta:línea: contenido.",
            "query" to "Texto a buscar",
        )
        tool(
            "get_problems",
            "Errores y advertencias del compilador: los de la última ejecución y los que se detectan mientras el usuario escribe.",
        )
        tool(
            "get_editor_state",
            "Qué archivo está mirando el usuario en el editor y qué archivos tienen cambios sin guardar.",
        )
        // ---- charlas anteriores del proyecto ----
        tool(
            "list_chats",
            "Lista las OTRAS charlas de este proyecto (id, título, fecha, cantidad de mensajes), por si el usuario se refiere a algo que hablaron antes.",
        )
        tool(
            "read_chat",
            "Lee el texto de otra charla de este proyecto (usá un id de list_chats).",
            "id" to "Id de la charla",
        )
        // ---- fase 4: compilar y probar ----
        tool(
            "cargo_check",
            "Compila el proyecto (cargo check) y devuelve los errores y advertencias. Usalo SIEMPRE después de cambiar código, y corregí hasta que no haya errores. Si agregaste dependencias, las baja (puede tardar).",
        )
        tool(
            "run_program",
            "Ejecuta el programa (cargo run, sin entrada del teclado, máximo 2 minutos) y devuelve lo que imprime. No sirve para juegos de macroquad: esos los abre el usuario con ▶.",
        )
        // ---- fase 3: escribir ----
        tool(
            "write_file",
            "Crea un archivo o reemplaza TODO su contenido. Crea las carpetas que falten. Para cambios chicos en un archivo grande usá edit_file.",
            "path" to "Ruta relativa a la raíz del proyecto, por ejemplo src/juego.rs",
            "content" to "Contenido completo del archivo",
        )
        tool(
            "edit_file",
            "Reemplaza un fragmento exacto de un archivo por otro. old_text tiene que aparecer UNA sola vez, copiado tal cual (con espacios y saltos de línea).",
            "path" to "Ruta relativa a la raíz del proyecto",
            "old_text" to "Texto exacto que hay ahora",
            "new_text" to "Texto que lo reemplaza",
        )
        tool(
            "create_folder",
            "Crea una carpeta vacía dentro del proyecto.",
            "path" to "Ruta relativa, por ejemplo assets",
        )
        tool(
            "delete_file",
            "Borra UN archivo de texto del proyecto (no carpetas ni imágenes u otros binarios). El usuario puede deshacerlo.",
            "path" to "Ruta relativa del archivo",
        )
    }

    override suspend fun execute(name: String, arguments: String): Outcome {
        val args = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // sin trim: el contenido de un archivo se escribe tal cual
        fun raw(key: String) = args[key]?.jsonPrimitive?.contentOrNull
        return try {
            when (name) {
                "list_files" -> listFiles()
                "read_file" -> readFile(arg("path"))
                "search_text" -> search(arg("query"))
                "get_problems" -> problems()
                "get_editor_state" -> editorState()
                "write_file" -> writeFile(arg("path"), raw("content"))
                "edit_file" -> editFile(arg("path"), raw("old_text"), raw("new_text"))
                "create_folder" -> createFolder(arg("path"))
                "delete_file" -> deleteFile(arg("path"))
                "list_chats" -> listChats()
                "read_chat" -> readChat(arg("id"))
                "cargo_check" -> cargoCheck()
                "run_program" -> runProgram()
                else -> Outcome("Error: la herramienta '$name' no existe.", "Pidió una herramienta desconocida ($name)")
            }
        } catch (e: Exception) {
            Outcome("Error: ${e.message ?: e}", "Falló $name: ${e.message ?: e}")
        }
    }

    // ---------------- herramientas ----------------

    private suspend fun listFiles(): Outcome {
        val nodes = withContext(Dispatchers.IO) { bridge.tree() }
        val lines = nodes.take(MAX_LIST).map { if (it.isDirectory) it.relativePath + "/" else it.relativePath }
        val more = if (nodes.size > MAX_LIST) "\n… y ${nodes.size - MAX_LIST} más" else ""
        val text = if (lines.isEmpty()) "(el proyecto está vacío)" else lines.joinToString("\n") + more
        return Outcome(text, "Miró la lista de archivos (${nodes.count { !it.isDirectory }})")
    }

    private suspend fun readFile(path: String): Outcome {
        if (path.isBlank()) return Outcome("Error: falta 'path'.", "Quiso leer un archivo sin decir cuál")
        // R4A (auditoría M3): ruta normalizada y, siguiendo symlinks, dentro del proyecto
        val rel = Fs.normalizeRelative(path)
        val f = withContext(Dispatchers.IO) { Fs.resolveInside(bridge.projectDir, rel) }
        bridge.bufferText(rel)?.let { return Outcome(clip(it), "Leyó $rel") }
        val text = withContext(Dispatchers.IO) {
            if (!f.isFile) return@withContext null
            if (f.length() > MAX_FILE_BYTES) return@withContext "Error: el archivo es muy grande (${f.length() / 1024} KB)."
            val bytes = f.readBytes()
            if (bytes.any { it == 0.toByte() }) "Error: '$rel' no es un archivo de texto." else String(bytes)
        } ?: return Outcome("Error: no existe el archivo '$rel'.", "Buscó $rel (no existe)")
        return Outcome(clip(text), "Leyó $rel")
    }

    private suspend fun search(query: String): Outcome {
        if (query.isEmpty()) return Outcome("Error: falta 'query'.", "Quiso buscar sin decir qué")
        val hits = withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()
            val root = bridge.projectDir
            for (node in bridge.tree()) {
                if (node.isDirectory || !isCode(node.relativePath)) continue
                // una carpeta enlazada (symlink) puede apuntar fuera del proyecto
                if (runCatching { Fs.requireInside(root, node.file) }.isFailure) continue
                val text = bridge.bufferText(node.relativePath)
                    ?: runCatching { if (node.file.length() <= MAX_FILE_BYTES) node.file.readText() else null }.getOrNull()
                    ?: continue
                text.lineSequence().forEachIndexed { i, line ->
                    if (out.size < MAX_HITS && line.contains(query, ignoreCase = true)) {
                        out += "${node.relativePath}:${i + 1}: ${line.trim().take(200)}"
                    }
                }
                if (out.size >= MAX_HITS) break
            }
            out
        }
        val text = if (hits.isEmpty()) "Sin resultados para \"$query\"." else hits.joinToString("\n")
        return Outcome(text, "Buscó «$query» (${hits.size} resultado${if (hits.size == 1) "" else "s"})")
    }

    private fun problems(): Outcome {
        val run = bridge.runProblems()
        val live = bridge.liveProblems()
        val sb = StringBuilder()
        if (live.isNotEmpty()) {
            sb.appendLine("Errores en vivo (cargo check del texto actual):")
            live.forEach { d ->
                sb.append("- ").append(if (d.isError) "error" else "warning")
                d.code?.let { sb.append(" [").append(it).append("]") }
                sb.append(" ${d.file}:${d.lineStart}:${d.colStart}: ${d.message}")
                d.label?.let { sb.append(" (").append(it).append(")") }
                sb.appendLine()
                d.notes.forEach { sb.appendLine("    $it") }
            }
        }
        if (run.isNotEmpty()) {
            sb.appendLine("Problems de la última ejecución:")
            run.forEach { d ->
                sb.append("- ").append(d.severity.name.lowercase())
                d.code?.let { sb.append(" [").append(it).append("]") }
                d.file?.let { sb.append(" $it:${d.line ?: "?"}:${d.col ?: "?"}") }
                sb.appendLine(": ${d.message}")
            }
        }
        val errors = live.count { it.isError } + run.count { it.severity == dev.rustdroid.ide.model.Severity.ERROR }
        val warnings = live.count { !it.isError } + run.count { it.severity == dev.rustdroid.ide.model.Severity.WARNING }
        if (sb.isEmpty()) sb.append("No hay errores ni advertencias.")
        return Outcome(clip(sb.toString()), "Revisó Problems ($errors errores, $warnings advertencias)")
    }

    private fun editorState(): Outcome {
        val active = bridge.activeFile()
        val unsaved = bridge.unsavedFiles()
        val text = buildString {
            appendLine("Archivo abierto: ${active ?: "(ninguno)"}")
            appendLine("Sin guardar: ${if (unsaved.isEmpty()) "(ninguno)" else unsaved.joinToString(", ")}")
        }
        return Outcome(text, "Miró qué archivo tenés abierto")
    }

    // ---------------- charlas anteriores ----------------

    private fun listChats(): Outcome {
        val h = history ?: return Outcome("No hay charlas guardadas.", "Miró las charlas (no hay)")
        val others = h.chats().filter { it.id != h.currentChatId() }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
        val text = if (others.isEmpty()) "No hay otras charlas en este proyecto."
        else others.joinToString("\n") { "${it.id} · ${fmt.format(java.util.Date(it.updatedAt))} · ${it.messages} mensajes · ${it.title.take(80)}" }
        return Outcome(text, "Miró las otras charlas (${others.size})")
    }

    private fun readChat(id: String): Outcome {
        val h = history ?: return Outcome("Error: no hay charlas guardadas.", "Quiso leer una charla (no hay)")
        val text = h.transcript(id) ?: return Outcome("Error: no existe la charla '$id'. Usá list_chats.", "Quiso leer una charla que no existe")
        val title = h.chats().firstOrNull { it.id == id }?.title.orEmpty().take(40)
        return Outcome(clip(text), "Leyó la charla «$title»")
    }

    // ---------------- fase 4: compilar y probar ----------------

    private suspend fun cargoCheck(): Outcome {
        val r = bridge.cargoCheck() ?: return Outcome(
            "Error: la toolchain de Rust no está instalada.", "No pudo compilar (falta la toolchain)",
        )
        val errors = r.diagnostics.count { it.isError }
        val warnings = r.diagnostics.size - errors
        val sb = StringBuilder()
        r.diagnostics.forEach { d ->
            sb.append(if (d.isError) "error" else "warning")
            d.code?.let { sb.append("[").append(it).append("]") }
            sb.append(" ${d.file}:${d.lineStart}:${d.colStart}: ${d.message}")
            d.label?.let { sb.append(" (").append(it).append(")") }
            sb.appendLine()
            d.notes.forEach { sb.appendLine("    $it") }
        }
        // cargo falló sin diagnósticos (Cargo.toml roto, crate que no existe, sin internet…)
        if (!r.success && errors == 0) {
            sb.appendLine("cargo falló:")
            r.stderrTail.filter { it.isNotBlank() }.takeLast(20).forEach { sb.appendLine(it) }
            return Outcome(clip(sb.toString()), "Compiló: cargo falló (mirá el chat)")
        }
        if (sb.isEmpty()) sb.append("Compila sin errores ni advertencias.")
        val summary = when {
            errors > 0 -> "Compiló: $errors error${if (errors == 1) "" else "es"}, $warnings advertencia${if (warnings == 1) "" else "s"}"
            warnings > 0 -> "Compiló bien ($warnings advertencia${if (warnings == 1) "" else "s"})"
            else -> "Compiló bien ✓"
        }
        return Outcome(clip(sb.toString()), summary)
    }

    private suspend fun runProgram(): Outcome {
        if (bridge.isGame()) return Outcome(
            "Es un juego de macroquad: no se puede ejecutar desde acá. Pedile al usuario que toque ▶ para verlo.",
            "Es un juego: lo abrís vos con ▶",
        )
        val out = ArrayList<String>()
        val result = kotlinx.coroutines.withTimeoutOrNull(RUN_TIMEOUT_MS) {
            bridge.runProgram { line -> synchronized(out) { out += line; if (out.size > 300) out.removeAt(0) } }
        }
        val lines = synchronized(out) { out.toList() }
        val text = buildString {
            when {
                result == null -> append("Se cortó a los 2 minutos (¿espera entrada del teclado o tiene un loop infinito?).\n")
                else -> append("Terminó con código ${result.exitCode}.\n")
            }
            append("Salida:\n")
            append(lines.joinToString("\n").ifBlank { "(no imprimió nada)" })
        }
        val summary = when {
            result == null -> "Ejecutó el programa (se cortó a los 2 min)"
            result.success -> "Ejecutó el programa ✓"
            else -> "Ejecutó el programa: falló (código ${result.exitCode})"
        }
        return Outcome(clip(text), summary)
    }

    // ---------------- fase 3: escribir ----------------

    /** Ruta segura dentro del proyecto; null + mensaje si no se puede escribir ahí. */
    private fun target(path: String): Pair<String, File> {
        if (path.isBlank()) throw IllegalArgumentException("falta 'path'")
        // R4A (auditoría M3): "././.git/x" ya no esquiva el chequeo de abajo,
        // y un symlink no deja escribir ni borrar fuera del proyecto
        val rel = Fs.normalizeRelative(path)
        val top = rel.substringBefore('/')
        if (top == "target" || top == ".git") throw IllegalArgumentException("no se escribe en $top/")
        return rel to Fs.resolveInside(bridge.projectDir, rel)
    }

    /** Lo que hay ahora: el texto del editor si está abierto, si no el disco. */
    private suspend fun current(rel: String, f: File): String? =
        bridge.bufferText(rel) ?: withContext(Dispatchers.IO) {
            if (!f.isFile) return@withContext null
            // R4A (auditoría M7): un binario (imagen, sonido…) leído como texto
            // queda roto si se lo pisa o si Deshacer lo vuelve a escribir.
            // write/edit/delete pasan todos por acá: no se tocan binarios.
            val head = f.inputStream().use { input ->
                val buf = ByteArray(8192)
                buf.copyOf(maxOf(0, input.read(buf)))
            }
            if (head.any { it == 0.toByte() }) {
                throw java.io.IOException("'$rel' es un archivo binario (imagen, sonido…); la IA no lo cambia ni lo borra. Pedile al usuario que lo haga desde Files.")
            }
            f.readText()
        }

    private suspend fun writeFile(path: String, content: String?): Outcome {
        if (content == null) return Outcome("Error: falta 'content'.", "Quiso escribir un archivo sin contenido")
        if (content.length > MAX_WRITE_CHARS) return Outcome("Error: contenido demasiado largo.", "Quiso escribir un archivo enorme")
        val (rel, f) = target(path)
        if (f.isDirectory) return Outcome("Error: '$rel' es una carpeta.", "Quiso escribir sobre la carpeta $rel")
        val before = current(rel, f)
        if (before == content) return Outcome("Sin cambios: '$rel' ya tenía ese contenido.", "$rel ya estaba así")
        withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); Fs.writeAtomic(f, content) }
        bridge.onExternalWrite(rel, content)
        val summary = if (before == null) "Creó $rel (${lineCount(content)} líneas)" else "Cambió $rel (${diffStat(before, content)})"
        return Outcome("Listo: '$rel' guardado.", summary, Change(bridge.projectDir, rel, before, content))
    }

    private suspend fun editFile(path: String, old: String?, new: String?): Outcome {
        if (old.isNullOrEmpty() || new == null) return Outcome("Error: faltan 'old_text' o 'new_text'.", "Quiso editar sin decir qué")
        val (rel, f) = target(path)
        val before = current(rel, f) ?: return Outcome("Error: no existe '$rel'.", "Quiso editar $rel (no existe)")
        var count = 0
        var at = before.indexOf(old)
        while (at >= 0 && count < 2) { count++; at = before.indexOf(old, at + 1) }
        if (count == 0) return Outcome(
            "Error: old_text no aparece en '$rel'. Leelo de nuevo con read_file y copiá el fragmento exacto.",
            "No encontró el fragmento a cambiar en $rel",
        )
        if (count > 1) return Outcome(
            "Error: old_text aparece más de una vez en '$rel'. Agregá más líneas alrededor para que sea único.",
            "El fragmento aparece más de una vez en $rel",
        )
        val after = before.replace(old, new)
        withContext(Dispatchers.IO) { Fs.writeAtomic(f, after) }
        bridge.onExternalWrite(rel, after)
        return Outcome("Listo: '$rel' editado.", "Cambió $rel (${diffStat(before, after)})", Change(bridge.projectDir, rel, before, after))
    }

    private suspend fun createFolder(path: String): Outcome {
        val (rel, f) = target(path)
        if (f.exists()) return Outcome("Ya existe '$rel'.", "La carpeta $rel ya existía")
        withContext(Dispatchers.IO) { if (!f.mkdirs()) throw java.io.IOException("no se pudo crear '$rel'") }
        bridge.onExternalWrite(rel, null)
        return Outcome("Listo: carpeta '$rel' creada.", "Creó la carpeta $rel/", Change(bridge.projectDir, rel, null, null, isDir = true))
    }

    private suspend fun deleteFile(path: String): Outcome {
        val (rel, f) = target(path)
        if (rel == "Cargo.toml") return Outcome("Error: no se puede borrar el Cargo.toml del proyecto.", "Quiso borrar Cargo.toml (no se permite)")
        if (f.isDirectory) return Outcome("Error: '$rel' es una carpeta; solo se borran archivos.", "Quiso borrar la carpeta $rel (no se permite)")
        val before = current(rel, f) ?: return Outcome("Error: no existe '$rel'.", "Quiso borrar $rel (no existe)")
        withContext(Dispatchers.IO) { if (!f.delete()) throw java.io.IOException("no se pudo borrar '$rel'") }
        bridge.onExternalWrite(rel, null)
        return Outcome("Listo: '$rel' borrado.", "Borró $rel", Change(bridge.projectDir, rel, before, null))
    }

    // ---------------- ayudas ----------------

    private fun lineCount(text: String) = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    /** "+3 −1 líneas", contando líneas que aparecen o desaparecen. */
    private fun diffStat(before: String, after: String): String {
        val old = before.lines().groupingBy { it }.eachCount().toMutableMap()
        var added = 0
        for (line in after.lines()) {
            val n = old[line] ?: 0
            if (n > 0) old[line] = n - 1 else added++
        }
        val removed = old.values.sum()
        return "+$added −$removed líneas"
    }

    private fun isCode(path: String): Boolean {
        val ext = path.substringAfterLast('.', "")
        return ext in setOf("rs", "toml", "md", "txt", "json", "yml", "yaml", "cfg", "sh", "wgsl", "glsl") || ext.isEmpty()
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + "\n… (recortado: ${text.length - MAX_RESULT_CHARS} caracteres más)"

    private fun kotlinx.serialization.json.JsonArrayBuilder.tool(
        name: String,
        description: String,
        vararg params: Pair<String, String>,
    ) {
        addJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        params.forEach { (p, desc) ->
                            putJsonObject(p) {
                                put("type", "string")
                                put("description", desc)
                            }
                        }
                    }
                    putJsonArray("required") { params.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.first)) } }
                }
            }
        }
    }

    companion object {
        private const val MAX_LIST = 400
        private const val MAX_HITS = 60
        private const val MAX_FILE_BYTES = 512L * 1024
        /** Lo que se le manda al modelo por herramienta (las capas gratis tienen pocos tokens). */
        private const val MAX_RESULT_CHARS = 24_000
        private const val MAX_WRITE_CHARS = 200_000
        private const val RUN_TIMEOUT_MS = 120_000L
    }
}
