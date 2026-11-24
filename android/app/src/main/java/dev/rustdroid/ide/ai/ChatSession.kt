package dev.rustdroid.ide.ai

import android.content.Context
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * R4A: las charlas con la IA, UNA POR PROYECTO, guardadas en
 * files/chats/ para recuperarlas al volver a abrir el proyecto (aunque se
 * cierre la app). Una por proceso, en AppContainer.
 */
class ChatStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val repo: dev.rustdroid.ide.projects.ProjectRepository,
) {
    private val dir = File(context.filesDir, "chats")
    private val sessions = HashMap<String, ChatSession>()

    /** La charla del proyecto en [projectDir] (la carga del disco la primera vez). */
    fun session(projectDir: File, projectName: String): ChatSession =
        sessions.getOrPut(key(projectDir)) {
            ChatSession(context, http, repo, projectName, file(projectDir)).also { it.load() }
        }

    /** La IA creó un proyecto y el usuario lo abre: la charla sigue allá. */
    fun copyTo(from: ChatSession, projectDir: File, projectName: String) {
        val to = session(projectDir, projectName)
        if (!to.isEmpty()) return // ya tenía su propia charla: no se pisa
        to.adopt(from, projectName)
    }

    private fun key(projectDir: File) = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)

    private fun file(projectDir: File): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key(projectDir).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }
}

/**
 * Una charla con la IA sobre un proyecto.
 * Fase 2: la IA LEE el proyecto con [ProjectTools]. Fase 3: también lo
 * cambia; cada cambio queda como tarjeta con "Abrir" y "Deshacer".
 *
 * Ciclo: se manda el historial + las herramientas; si el modelo pide
 * herramientas, se ejecutan, se le devuelven los resultados y se repite
 * hasta que contesta con texto (o se llega a [MAX_STEPS]).
 * Vive fuera de la pantalla (su propio scope): si se cierra el cajón sigue.
 */
class ChatSession(
    private val context: Context,
    http: OkHttpClient,
    private val repo: dev.rustdroid.ide.projects.ProjectRepository,
    private var projectName: String,
    private val saveFile: File,
) {
    /** TOOL = línea chiquita ("Leyó src/main.rs"); CHANGE = tarjeta con Abrir/Deshacer. */
    enum class Role { USER, ASSISTANT, TOOL, CHANGE, ERROR }

    /**
     * [path]: archivo para el botón Abrir. [changeId]: para Deshacer (solo
     * mientras la app sigue abierta). [projectRef]: proyecto nuevo para abrir.
     */
    data class ChatMessage(
        val role: Role,
        val text: String,
        val path: String? = null,
        val changeId: Int? = null,
        val undone: Boolean = false,
        val projectRef: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = AiClient(http)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Historial tal como lo ve la API (con pedidos y resultados de herramientas). */
    private val apiHistory = mutableListOf<AiClient.Message>()

    /** Cambios que se pueden deshacer (solo en memoria). */
    private val changes = HashMap<Int, ProjectTools.Change>()
    private var nextChangeId = 1

    /** false si el modelo elegido no acepta herramientas: se sigue sin ellas. */
    private var toolsSupported = true

    private var tools: ProjectTools? = null
    private var bridge: ProjectTools.Bridge? = null
    private var job: Job? = null

    fun isEmpty() = _messages.value.isEmpty()

    /**
     * Los primeros [hiddenBefore] mensajes son de visitas anteriores al
     * proyecto: no se muestran hasta que el usuario desliza hacia abajo.
     * Solo es visual: la IA sigue recordando toda la charla.
     */
    private val _hiddenBefore = MutableStateFlow(0)
    val hiddenBefore: StateFlow<Int> = _hiddenBefore.asStateFlow()

    /** true justo después de [adopt]: esa charla se sigue viendo. */
    private var keepVisible = false

    /** Se abrió el proyecto: lo de antes queda oculto. */
    fun markVisitStart() {
        if (keepVisible) { keepVisible = false; return }
        _hiddenBefore.value = _messages.value.size
    }

    fun revealOlder() { _hiddenBefore.value = 0 }

    /** El editor de este proyecto está abierto: las herramientas trabajan sobre él. */
    fun attach(bridge: ProjectTools.Bridge) {
        this.bridge = bridge
        tools = ProjectTools(bridge, repo)
    }

    /** Se cerró el editor: se corta lo que esté haciendo (no hay dónde escribir). */
    fun detach(bridge: ProjectTools.Bridge) {
        if (this.bridge !== bridge) return
        stop()
        this.bridge = null
        tools = null
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        val tools = this.tools ?: return
        // la API key activa (se puede cambiar desde la cabecera del chat)
        val cfg = AiSettings.state.value.active
        val key = cfg?.let { AiSettings.apiKey(context, it.id) }
        if (cfg == null || !cfg.ready || key == null) {
            _messages.update { it + ChatMessage(Role.ERROR, context.getString(dev.rustdroid.ide.R.string.ai_not_configured)) }
            return
        }

        _messages.update { it + ChatMessage(Role.USER, prompt) }
        apiHistory += AiClient.Message("user", prompt)
        _busy.value = true

        job = scope.launch {
            try {
                for (step in 1..MAX_STEPS) {
                    _messages.update { it + ChatMessage(Role.ASSISTANT, "") }
                    val reply = ask(tools, cfg.baseUrl, key, cfg.model)
                    dropEmptyLast()
                    apiHistory += AiClient.Message("assistant", reply.content, toolCalls = reply.toolCalls)
                    if (reply.toolCalls.isEmpty()) break

                    for (call in reply.toolCalls) {
                        val out = tools.execute(call.name, call.arguments)
                        _messages.update { it + toMessage(out) }
                        apiHistory += AiClient.Message("tool", out.result, toolCallId = call.id)
                    }
                    if (step == MAX_STEPS) {
                        _messages.update { it + ChatMessage(Role.ERROR, "La IA hizo muchos pasos seguidos y se cortó acá. Pedile que siga.") }
                    }
                }
            } catch (e: AiClient.AiException) {
                dropEmptyLast()
                _messages.update { it + ChatMessage(Role.ERROR, e.message ?: "Error") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                dropEmptyLast()
                throw e
            } catch (e: Exception) {
                dropEmptyLast()
                _messages.update { it + ChatMessage(Role.ERROR, e.message ?: e.toString()) }
            } finally {
                closeDanglingToolCalls()
                _busy.value = false
                save()
            }
        }
    }

    private fun toMessage(out: ProjectTools.Outcome): ChatMessage {
        out.newProjectRef?.let { return ChatMessage(Role.CHANGE, out.summary, projectRef = it) }
        val change = out.change ?: return ChatMessage(Role.TOOL, out.summary)
        val id = nextChangeId++
        changes[id] = change
        return ChatMessage(
            Role.CHANGE, out.summary,
            path = change.path.takeIf { change.after != null && !change.isDir },
            changeId = id,
        )
    }

    /** Una vuelta al modelo. Si rechaza las herramientas, reintenta sin ellas. */
    private suspend fun ask(tools: ProjectTools, baseUrl: String, key: String, model: String): AiClient.Reply {
        val messages = listOf(AiClient.Message("system", systemPrompt())) + apiHistory
        if (toolsSupported) {
            try {
                return client.chatStream(baseUrl, key, model, messages, tools.definitions) { appendToLast(it) }
            } catch (e: AiClient.AiException) {
                val msg = e.message.orEmpty().lowercase()
                if (!("tool" in msg || "function" in msg)) throw e
                toolsSupported = false
                _messages.update {
                    it.dropLast(1) + ChatMessage(Role.TOOL, "Este modelo no puede usar el proyecto; contesta sin mirarlo") + ChatMessage(Role.ASSISTANT, "")
                }
            }
        }
        return client.chatStream(baseUrl, key, model, messages, null) { appendToLast(it) }
    }

    /**
     * Deshace un cambio de la IA. No pisa lo que el usuario haya escrito
     * después: si el archivo ya no está como lo dejó la IA, avisa y no toca nada.
     */
    fun undo(changeId: Int) {
        val change = changes[changeId] ?: return
        val b = bridge
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val f = Fs.resolveChild(change.projectDir, change.path)
                    if (change.isDir) {
                        if (f.isDirectory && (f.list()?.isEmpty() != false)) f.delete()
                        else return@runCatching "La carpeta ${change.path} ya tiene archivos; no se borró."
                        return@runCatching null
                    }
                    val now = b?.takeIf { it.projectDir == change.projectDir }?.bufferText(change.path)
                        ?: if (f.isFile) f.readText() else null
                    if (now != change.after) return@runCatching "${change.path} cambió después; no se deshizo para no perder lo nuevo."
                    if (change.before == null) f.delete()
                    else { f.parentFile?.mkdirs(); Fs.writeAtomic(f, change.before) }
                    null
                }.getOrElse { it.message ?: it.toString() }
            }
            if (error != null) {
                _messages.update { it + ChatMessage(Role.ERROR, error) }
                return@launch
            }
            if (b != null && b.projectDir == change.projectDir) b.onExternalWrite(change.path, change.before)
            changes.remove(changeId)
            _messages.update { list -> list.map { if (it.changeId == changeId) it.copy(undone = true) else it } }
            // que la IA sepa que el usuario lo deshizo
            apiHistory += AiClient.Message("user", "[R4A: el usuario deshizo este cambio: ${_messages.value.firstOrNull { it.changeId == changeId }?.text}]")
            save()
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        apiHistory.clear()
        changes.clear()
        toolsSupported = true
        _hiddenBefore.value = 0
        save()
    }

    /** Copia la charla de otro proyecto (el que creó este). */
    fun adopt(from: ChatSession, newName: String) {
        projectName = newName
        _messages.value = from._messages.value.map { it.copy(changeId = null) } +
            ChatMessage(Role.TOOL, "Seguimos en el proyecto $newName")
        apiHistory.clear()
        apiHistory += from.apiHistory
        apiHistory += AiClient.Message("user", "[R4A: ahora está abierto el proyecto nuevo \"$newName\"; tus herramientas trabajan sobre él]")
        keepVisible = true
        save()
    }

    // ---------------- guardar / cargar ----------------

    private fun save() {
        val msgs = _messages.value
        val api = apiHistory.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (msgs.isEmpty()) { saveFile.delete(); return@runCatching }
                val root = JSONObject()
                    .put("project", projectName)
                    .put("messages", JSONArray().apply {
                        msgs.forEach { m ->
                            put(JSONObject().put("role", m.role.name).put("text", m.text)
                                .putOpt("path", m.path).putOpt("projectRef", m.projectRef).put("undone", m.undone))
                        }
                    })
                    .put("api", JSONArray().apply {
                        api.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content ?: "")
                                .putOpt("toolCallId", m.toolCallId)
                                .put("toolCalls", JSONArray().apply {
                                    m.toolCalls.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.arguments)) }
                                }))
                        }
                    })
                saveFile.parentFile?.mkdirs()
                Fs.writeAtomic(saveFile, root.toString())
            }
        }
    }

    fun load() {
        val root = runCatching { JSONObject(saveFile.readText()) }.getOrNull() ?: return
        val msgs = root.optJSONArray("messages") ?: JSONArray()
        // los cambios viejos ya no se pueden deshacer (el "antes" no se guarda)
        _messages.value = (0 until msgs.length()).mapNotNull { i ->
            val o = msgs.getJSONObject(i)
            val role = Role.entries.firstOrNull { it.name == o.optString("role") } ?: return@mapNotNull null
            ChatMessage(
                role, o.optString("text"),
                path = o.optString("path").ifEmpty { null },
                projectRef = o.optString("projectRef").ifEmpty { null },
                undone = o.optBoolean("undone"),
            )
        }
        val api = root.optJSONArray("api") ?: JSONArray()
        apiHistory.clear()
        for (i in 0 until api.length()) {
            val o = api.getJSONObject(i)
            val calls = o.optJSONArray("toolCalls") ?: JSONArray()
            apiHistory += AiClient.Message(
                role = o.optString("role"),
                content = o.optString("content"),
                toolCalls = (0 until calls.length()).map { j ->
                    val c = calls.getJSONObject(j)
                    AiClient.ToolCall(c.optString("id"), c.optString("name"), c.optString("arguments"))
                },
                toolCallId = o.optString("toolCallId").ifEmpty { null },
            )
        }
    }

    // ---------------- ayudas ----------------

    /**
     * Si se cortó (Detener o error) después de que el modelo pidió
     * herramientas, la API exige una respuesta por cada pedido: se completan
     * con "cancelado" para que el próximo mensaje no falle.
     */
    private fun closeDanglingToolCalls() {
        val lastAssistant = apiHistory.indexOfLast { it.role == "assistant" }
        if (lastAssistant < 0) return
        val asked = apiHistory[lastAssistant].toolCalls.map { it.id }
        val answered = apiHistory.drop(lastAssistant + 1).mapNotNull { it.toolCallId }.toSet()
        asked.filterNot { it in answered }.forEach {
            apiHistory += AiClient.Message("tool", "Cancelado por el usuario.", toolCallId = it)
        }
    }

    private fun appendToLast(delta: String) {
        _messages.update { list ->
            val last = list.lastOrNull() ?: return@update list
            list.dropLast(1) + last.copy(text = last.text + delta)
        }
    }

    /** Si el asistente no llegó a escribir nada, saca la burbuja vacía. */
    private fun dropEmptyLast() {
        _messages.update { list ->
            val last = list.lastOrNull()
            if (last != null && last.role == Role.ASSISTANT && last.text.isEmpty()) list.dropLast(1) else list
        }
    }

    private fun systemPrompt() = """
        Sos el asistente de programación de R4A, un IDE de Rust que corre directamente en un
        celular Android (aarch64), sin PC. El usuario escribe, compila y corre programas Rust
        en el teléfono con cargo (Rust 1.85, edición 2024). La pantalla es chica: respondé
        corto y claro. Respondé en el idioma en que te escriba el usuario.
        Proyecto abierto ahora: "$projectName".

        Herramientas para LEER: list_files, read_file, search_text, get_problems, get_editor_state.
        Herramientas para CAMBIAR: write_file, edit_file, create_folder, delete_file, create_project.
        - Antes de cambiar un archivo, leelo. Para cambios chicos usá edit_file; para archivos
          nuevos o reescrituras completas, write_file.
        - Si el usuario pide algo concreto ("haceme...", "agregá...", "arreglá..."), hacelo con
          las herramientas en vez de solo mostrar el código. Cada cambio le aparece como una
          tarjeta y lo puede deshacer.
        - Si pide un proyecto NUEVO, usá create_project y pedile que lo abra con el botón; los
          archivos de ese proyecto se editan recién cuando esté abierto.
        - No podés compilar ni ejecutar: el usuario toca ▶. Después de cambiar código podés mirar
          get_problems (los errores en vivo tardan unos segundos en actualizarse).
        - Juegos: macroquad 0.4 con default-features = false y fontdue = "=0.9.3" en
          [dependencies] (fontdue más nuevo pide Rust 1.87). Usá #[macroquad::main("Nombre")]
          y async fn main() con loop { ...; next_frame().await }. Las imágenes van con
          include_bytes! (load_texture con archivos no anda en Android). En edición 2024,
          #[no_mangle] se escribe #[unsafe(no_mangle)].
        - Al terminar, contá en una o dos frases qué hiciste.
    """.trimIndent()

    private companion object {
        const val MAX_STEPS = 20
    }
}
