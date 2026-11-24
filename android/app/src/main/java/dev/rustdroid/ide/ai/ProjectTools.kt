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
 * R4A fase 2 de la IA: herramientas de SOLO LECTURA sobre el proyecto
 * abierto. Nada de esto escribe, borra ni ejecuta: leen archivos (dentro
 * del proyecto, nunca afuera), la lista de Problems y lo que hay abierto
 * en el editor (incluido el texto sin guardar).
 */
class ProjectTools(private val bridge: Bridge) {

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
    }

    /** Resultado para el modelo + una línea corta para mostrar en el chat. */
    data class Outcome(val result: String, val summary: String)

    private val json = Json { ignoreUnknownKeys = true }

    val definitions: JsonArray = buildJsonArray {
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
    }

    suspend fun execute(name: String, arguments: String): Outcome {
        val args = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return try {
            when (name) {
                "list_files" -> listFiles()
                "read_file" -> readFile(arg("path"))
                "search_text" -> search(arg("query"))
                "get_problems" -> problems()
                "get_editor_state" -> editorState()
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
        val rel = path.removePrefix("./").replace('\\', '/')
        if (rel.isEmpty()) return Outcome("Error: falta 'path'.", "Quiso leer un archivo sin decir cuál")
        bridge.bufferText(rel)?.let { return Outcome(clip(it), "Leyó $rel") }
        val text = withContext(Dispatchers.IO) {
            val f = Fs.resolveChild(bridge.projectDir, rel)
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
            for (node in bridge.tree()) {
                if (node.isDirectory || !isCode(node.relativePath)) continue
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

    // ---------------- ayudas ----------------

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
    }
}
