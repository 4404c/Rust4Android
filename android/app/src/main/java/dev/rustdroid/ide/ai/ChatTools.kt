package dev.rustdroid.ide.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * R4A: las herramientas que tiene un chat. El de un proyecto usa
 * [ProjectTools] (leer, cambiar, compilar ese proyecto); el de Inicio usa
 * [HomeTools] (crear proyectos nuevos).
 */
interface ChatTools {
    /** Definiciones en formato OpenAI (`type: function`). */
    val definitions: JsonArray
    suspend fun execute(name: String, arguments: String): ProjectTools.Outcome

    /** Herramientas permitidas en modo Plan (solo mirar, nada que cambie archivos). */
    val planTools: Set<String> get() = emptySet()

    /** [definitions] filtradas por nombre. */
    fun definitionsFor(names: Set<String>): JsonArray = JsonArray(
        definitions.filter { def ->
            def.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull in names
        },
    )
}

/** R4A: las charlas del proyecto, para que la IA pueda leer las anteriores. */
interface ChatHistory {
    fun chats(): List<ProjectChats.Summary>
    fun currentChatId(): String?
    /** La charla como texto (Usuario / IA / pasos), o null si no existe. */
    fun transcript(id: String): String?
}

/**
 * R4A: herramientas del chat de la pantalla de Inicio. Solo crea proyectos
 * (con `cargo new`, como el botón "Nuevo proyecto") y lista los que hay;
 * no lee ni cambia archivos de ningún proyecto.
 */
class HomeTools(private val repo: dev.rustdroid.ide.projects.ProjectRepository) : ChatTools {

    private val json = Json { ignoreUnknownKeys = true }

    override val definitions: JsonArray = buildJsonArray {
        fn("list_projects", "Lista los proyectos que ya tiene el usuario (nombre y cuántas dependencias tiene).")
        fn(
            "create_project",
            "Crea un proyecto de R4A NUEVO (cargo new). Después el usuario lo abre con el botón \"Abrir proyecto\" y ahí se escribe el código.",
            "name" to "Nombre: empieza con letra; solo letras, números, - y _ (por ejemplo pelota_rebota)",
            "template" to "\"basico\" (hola mundo) o \"juego\" (macroquad con una pelota de ejemplo)",
        )
    }

    override suspend fun execute(name: String, arguments: String): ProjectTools.Outcome {
        val args = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return try {
            when (name) {
                "list_projects" -> listProjects()
                "create_project" -> createProject(arg("name"), arg("template"))
                else -> ProjectTools.Outcome(
                    "Error: desde Inicio no existe la herramienta '$name'. Primero creá el proyecto y pedile al usuario que lo abra.",
                    "Pidió una herramienta que no hay en Inicio ($name)",
                )
            }
        } catch (e: Exception) {
            ProjectTools.Outcome("Error: ${e.message ?: e}", "Falló $name: ${e.message ?: e}")
        }
    }

    private suspend fun listProjects(): ProjectTools.Outcome {
        val list = withContext(Dispatchers.IO) { repo.list() }
        val text = if (list.isEmpty()) "(no hay proyectos todavía)"
        else list.joinToString("\n") { "${it.name} (${it.dependencyCount} dependencias)" }
        return ProjectTools.Outcome(text, "Miró tus proyectos (${list.size})")
    }

    private suspend fun createProject(name: String, template: String): ProjectTools.Outcome {
        if (!name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) return ProjectTools.Outcome(
            "Error: nombre inválido. Tiene que empezar con letra y tener solo letras, números, - y _.",
            "Nombre de proyecto inválido: $name",
        )
        val game = template.lowercase().let { "jueg" in it || "game" in it || "macroquad" in it }
        val dir = repo.create(name, isLib = false, game = game)
        return ProjectTools.Outcome(
            "Listo: proyecto '$name' creado (${if (game) "juego con macroquad" else "básico"}). " +
                "Pedile al usuario que toque \"Abrir proyecto\" para seguir; recién ahí vas a poder escribir el código.",
            "Creó el proyecto $name${if (game) " (juego)" else ""}",
            newProjectRef = repo.refOf(dir),
        )
    }

    private fun JsonArrayBuilder.fn(name: String, description: String, vararg params: Pair<String, String>) {
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
                    putJsonArray("required") { params.forEach { add(JsonPrimitive(it.first)) } }
                }
            }
        }
    }
}
