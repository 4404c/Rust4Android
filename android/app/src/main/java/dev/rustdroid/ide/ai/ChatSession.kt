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
    /** Chat de la pantalla de Inicio (crear proyectos), no de un proyecto. */
    private val home: Boolean = false,
    /** Identifica la charla dentro del proyecto (nombre del archivo). */
    val id: String = saveFile.nameWithoutExtension,
    /** Avisa a [ProjectChats] que cambió (para la lista de charlas). */
    private val onSaved: (ChatSession) -> Unit = {},
    /** Empezó (true) o terminó (false) de trabajar; al terminar, [error] si falló. */
    private val onBusy: (ChatSession, Boolean, Boolean) -> Unit = { _, _, _ -> },
) {
    /** PLAN = solo mira y propone un plan; BUILD = puede cambiar archivos. */
    enum class Mode { PLAN, BUILD }

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
        /** Clave estable para la lista (no cambia al agregar texto ni al revelar lo anterior). */
        val id: Long = nextMessageId.incrementAndGet(),
    )

    /**
     * Borrador: lo escrito en el cuadro y no enviado. Se guarda aparte (no en
     * el archivo de la charla, que no existe mientras la charla está vacía).
     */
    fun draft(): String = draftPrefs().getString(saveFile.absolutePath, "").orEmpty()

    fun saveDraft(text: String) {
        val p = draftPrefs()
        if (text.isBlank()) p.edit().remove(saveFile.absolutePath).apply()
        else p.edit().putString(saveFile.absolutePath, text).apply()
    }

    private fun draftPrefs() = context.getSharedPreferences("r4a_chat_drafts", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = AiClient(http)
    private val claude = AnthropicChat()
    private val chatgpt = ChatGptClient(http)
    private val chatgptAuth = ChatGptAuth(http)

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

    private var tools: ChatTools? = null
    /** El editor del proyecto (para Deshacer); null en el chat de Inicio. */
    private val bridge: ProjectTools.Bridge? get() = (tools as? ProjectTools)?.bridge
    private var job: Job? = null

    fun isEmpty() = _messages.value.isEmpty()

    private val _mode = MutableStateFlow(Mode.BUILD)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun setMode(m: Mode) {
        _mode.value = m
        save()
    }

    /** false si el modelo no acepta elegir el esfuerzo: se sigue sin él. */
    private var effortSupported = true

    /** La charla como texto, para que la IA la lea desde otra charla (read_chat). */
    fun transcript(): String = _messages.value.joinToString("\n") { m ->
        when (m.role) {
            Role.USER -> "Usuario: ${m.text}"
            Role.ASSISTANT -> "IA: ${m.text}"
            Role.TOOL -> "  (paso) ${m.text}"
            Role.CHANGE -> "  (cambio) ${m.text}${if (m.undone) " — deshecho" else ""}"
            Role.ERROR -> "  (error) ${m.text}"
        }
    }

    /** Para la lista de charlas: el primer mensaje del usuario. */
    val title: String get() =
        _messages.value.firstOrNull { it.role == Role.USER }?.text?.lineSequence()?.firstOrNull().orEmpty()

    /** Última vez que se guardó (ms). */
    var updatedAt: Long = saveFile.takeIf { it.isFile }?.lastModified() ?: System.currentTimeMillis()
        private set

    val busyNow: Boolean get() = _busy.value

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
    fun attach(tools: ChatTools) {
        this.tools = tools
        detachedWhileBusy = false
    }

    /**
     * true = el usuario salió del proyecto mientras la IA trabajaba: sigue con
     * las herramientas de antes y, al terminar, avisa (punto en Inicio y notificación).
     */
    var detachedWhileBusy = false
        private set

    /** ¿Hay alguien mirando este proyecto? (editor abierto) */
    val attached: Boolean get() = tools != null && !detachedWhileBusy

    /** Se cerró el editor: se corta lo que esté haciendo (no hay dónde escribir). */
    fun detach(tools: ChatTools) {
        if (this.tools !== tools) return
        // R4A: si está trabajando, NO se corta: termina con lo que tenía
        if (_busy.value) {
            detachedWhileBusy = true
            return
        }
        this.tools = null
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
        onBusy(this, true, false)
        var failed = false

        job = scope.launch {
            try {
                for (step in 1..MAX_STEPS) {
                    _messages.update { it + ChatMessage(Role.ASSISTANT, "") }
                    // si el usuario volvió a abrir el proyecto, se usan las herramientas nuevas
                    val tools = this@ChatSession.tools ?: tools
                    val reply = ask(tools, cfg, key)
                    dropEmptyLast()
                    apiHistory += AiClient.Message("assistant", reply.content, toolCalls = reply.toolCalls, raw = reply.raw)
                    if (reply.toolCalls.isEmpty()) break

                    for (call in reply.toolCalls) {
                        // en modo Plan, nada que cambie archivos (aunque el modelo lo pida igual)
                        val out = if (_mode.value == Mode.PLAN && !home && call.name !in tools.planTools) {
                            ProjectTools.Outcome(
                                "Error: estás en modo Plan; no podés usar '${call.name}'. Proponé el plan y esperá que el usuario pase a Build.",
                                "No usó ${call.name} (modo Plan)",
                            )
                        } else tools.execute(call.name, call.arguments)
                        _messages.update { it + toMessage(out) }
                        apiHistory += AiClient.Message("tool", out.result, toolCallId = call.id)
                    }
                    if (step == MAX_STEPS) {
                        _messages.update { it + ChatMessage(Role.ERROR, "La IA hizo muchos pasos seguidos y se cortó acá. Pedile que siga.") }
                    }
                }
            } catch (e: AiClient.AiException) {
                failed = true
                dropEmptyLast()
                _messages.update { it + ChatMessage(Role.ERROR, e.message ?: "Error") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                dropEmptyLast()
                throw e
            } catch (e: Exception) {
                failed = true
                dropEmptyLast()
                _messages.update { it + ChatMessage(Role.ERROR, e.message ?: e.toString()) }
            } finally {
                closeDanglingToolCalls()
                _busy.value = false
                save()
                onBusy(this@ChatSession, false, failed)
                // ya terminó: si el usuario se había ido, se sueltan las herramientas viejas
                if (detachedWhileBusy) { this@ChatSession.tools = null; detachedWhileBusy = false }
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

    /**
     * Una vuelta al modelo. Si el proveedor dice "límite por minuto" (las capas
     * gratis), espera lo que pide y reintenta, hasta [MAX_RATE_RETRIES] veces.
     */
    private suspend fun ask(tools: ChatTools, cfg: AiSettings.Connection, key: String): AiClient.Reply {
        var attempt = 0
        while (true) {
            try {
                return askOnce(tools, cfg, key)
            } catch (e: AiClient.AiException) {
                val wait = e.retryAfterSec
                if (wait == null || wait > MAX_RATE_WAIT_SEC || attempt >= MAX_RATE_RETRIES) throw e
                attempt++
                val secs = kotlin.math.ceil(wait).toInt() + 1
                _messages.update {
                    it.dropLast(1) + ChatMessage(Role.TOOL, "Límite de ${cfg.provider.label}: espera $secs s y sigue…") + ChatMessage(Role.ASSISTANT, "")
                }
                kotlinx.coroutines.delay(secs * 1000L)
            }
        }
    }

    /** Si el modelo rechaza las herramientas, reintenta sin ellas. */
    /** Si el modelo rechaza elegir el esfuerzo, reintenta sin él (y lo recuerda). */
    private suspend fun askOnce(tools: ChatTools, cfg: AiSettings.Connection, key: String): AiClient.Reply {
        val effort = if (effortSupported) cfg.effort else ""
        if (effort.isEmpty()) return askWith(tools, cfg, key, "")
        return try {
            askWith(tools, cfg, key, effort)
        } catch (e: Exception) {
            val msg = e.message.orEmpty().lowercase()
            if (e is kotlinx.coroutines.CancellationException || !("effort" in msg || "reasoning" in msg)) throw e
            effortSupported = false
            _messages.update {
                it.dropLast(1) + ChatMessage(Role.TOOL, "Este modelo no permite elegir el esfuerzo; sigue sin eso") + ChatMessage(Role.ASSISTANT, "")
            }
            askWith(tools, cfg, key, "")
        }
    }

    /** Si el modelo rechaza las herramientas, reintenta sin ellas. */
    private suspend fun askWith(tools: ChatTools, cfg: AiSettings.Connection, key: String, effort: String): AiClient.Reply {
        val messages = listOf(AiClient.Message("system", systemPrompt())) + apiHistory
        // modo Plan: solo las herramientas para mirar
        val defs = if (_mode.value == Mode.PLAN && !home) tools.definitionsFor(tools.planTools) else tools.definitions
        // Claude va por el SDK de Anthropic (acepta herramientas siempre)
        if (cfg.provider == AiSettings.Provider.ANTHROPIC) {
            return claude.chatStream(key, cfg.model, messages, defs, effort) { appendToLast(it) }
        }
        val baseUrl = cfg.baseUrl
        val model = cfg.model
        // plan de ChatGPT: API de Responses con el token de la sesión (se renueva solo)
        val stream: suspend (kotlinx.serialization.json.JsonArray?) -> AiClient.Reply =
            if (cfg.provider == AiSettings.Provider.CHATGPT) {
                { t -> chatgpt.chatStream(chatgptAuth.accessToken(context, cfg.id), model, messages, t, effort) { appendToLast(it) } }
            } else {
                { t -> client.chatStream(baseUrl, key, model, messages, t, effort) { appendToLast(it) } }
            }
        if (toolsSupported) {
            try {
                return stream(defs)
            } catch (e: AiClient.AiException) {
                val msg = e.message.orEmpty().lowercase()
                if (!("tool" in msg || "function" in msg)) throw e
                toolsSupported = false
                _messages.update {
                    it.dropLast(1) + ChatMessage(Role.TOOL, "Este modelo no puede usar el proyecto; contesta sin mirarlo") + ChatMessage(Role.ASSISTANT, "")
                }
            }
        }
        return stream(null)
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

    /** El antes/después de un cambio (para el diff); null si ya no está en memoria. */
    fun change(changeId: Int): ProjectTools.Change? = changes[changeId]

    fun clear() {
        stop()
        _messages.value = emptyList()
        apiHistory.clear()
        changes.clear()
        toolsSupported = true
        effortSupported = true
        _hiddenBefore.value = 0
        save()
    }

    /** Copia la charla de otro proyecto (el que creó este). */
    fun adopt(from: ChatSession, newName: String) {
        projectName = newName
        _messages.value = from._messages.value.map { it.copy(changeId = null, projectRef = null) } +
            ChatMessage(Role.TOOL, "Seguimos en el proyecto $newName")
        apiHistory.clear()
        apiHistory += from.apiHistory
        apiHistory += AiClient.Message("user", "[R4A: ahora está abierto el proyecto nuevo \"$newName\"; tus herramientas trabajan sobre él]")
        keepVisible = true
        save()
    }

    // ---------------- guardar / cargar ----------------

    /**
     * El antes/después de cada cambio, aparte de la charla (puede ser grande):
     * así el diff y Deshacer siguen andando después de cerrar la app.
     */
    private val changesFile = File(saveFile.parentFile, saveFile.nameWithoutExtension + ".changes")

    private fun save() {
        val msgs = _messages.value
        val api = apiHistory.toList()
        val chg = changes.filterKeys { id -> msgs.any { it.changeId == id } }
        updatedAt = System.currentTimeMillis()
        onSaved(this)
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (msgs.isEmpty()) { saveFile.delete(); changesFile.delete(); return@runCatching }
                val root = JSONObject()
                    .put("project", projectName)
                    .put("title", title)
                    .put("mode", _mode.value.name)
                    .put("messages", JSONArray().apply {
                        msgs.forEach { m ->
                            put(JSONObject().put("role", m.role.name).put("text", m.text)
                                .putOpt("path", m.path).putOpt("projectRef", m.projectRef).put("undone", m.undone)
                                .putOpt("changeId", m.changeId?.takeIf { it in chg }))
                        }
                    })
                    .put("api", JSONArray().apply {
                        api.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content ?: "")
                                .putOpt("toolCallId", m.toolCallId)
                                .putOpt("raw", m.raw)
                                .put("toolCalls", JSONArray().apply {
                                    m.toolCalls.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.arguments).apply { c.extra?.let { put("extra", it) } }) }
                                }))
                        }
                    })
                saveFile.parentFile?.mkdirs()
                Fs.writeAtomic(saveFile, root.toString())
                // cambios: hasta ~2 MB por cambio (archivos enormes no se guardan)
                val cj = JSONObject()
                chg.forEach { (id, c) ->
                    if ((c.before?.length ?: 0) + (c.after?.length ?: 0) > 2_000_000) return@forEach
                    cj.put(id.toString(), JSONObject()
                        .put("dir", c.projectDir.absolutePath).put("path", c.path)
                        .putOpt("before", c.before).putOpt("after", c.after).put("isDir", c.isDir))
                }
                if (cj.length() == 0) changesFile.delete() else Fs.writeAtomic(changesFile, cj.toString())
            }
        }
    }

    fun load() {
        val root = runCatching { JSONObject(saveFile.readText()) }.getOrNull() ?: return
        _mode.value = Mode.entries.firstOrNull { it.name == root.optString("mode") } ?: Mode.BUILD
        val msgs = root.optJSONArray("messages") ?: JSONArray()
        // los cambios guardados: su diff se sigue viendo y se pueden deshacer
        runCatching {
            val cj = JSONObject(changesFile.readText())
            cj.keys().forEach { k ->
                val o = cj.getJSONObject(k)
                changes[k.toInt()] = ProjectTools.Change(
                    File(o.getString("dir")), o.getString("path"),
                    if (o.isNull("before") || !o.has("before")) null else o.getString("before"),
                    if (o.isNull("after") || !o.has("after")) null else o.getString("after"),
                    o.optBoolean("isDir"),
                )
            }
            nextChangeId = (changes.keys.maxOrNull() ?: 0) + 1
        }
        _messages.value = (0 until msgs.length()).mapNotNull { i ->
            val o = msgs.getJSONObject(i)
            val role = Role.entries.firstOrNull { it.name == o.optString("role") } ?: return@mapNotNull null
            ChatMessage(
                role, o.optString("text"),
                path = o.optString("path").ifEmpty { null },
                projectRef = o.optString("projectRef").ifEmpty { null },
                undone = o.optBoolean("undone"),
                changeId = if (o.has("changeId")) o.optInt("changeId").takeIf { it in changes } else null,
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
                    AiClient.ToolCall(c.optString("id"), c.optString("name"), c.optString("arguments"), c.optString("extra").ifEmpty { null })
                },
                toolCallId = o.optString("toolCallId").ifEmpty { null },
                raw = o.optString("raw").ifEmpty { null },
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

    private fun systemPrompt(): String {
        val base = if (home) homePrompt() else projectPrompt()
        val plan = if (_mode.value == Mode.PLAN && !home) "\n\n" + """
            MODO PLAN (lo eligió el usuario): no cambies archivos. Investigá con las herramientas de
            lectura (y cargo_check si hace falta) y respondé con un plan corto y numerado: qué
            archivos vas a tocar y qué vas a hacer en cada uno. Terminá preguntando si lo ejecutás;
            para ejecutarlo el usuario pasa a modo Build.
        """.trimIndent() else ""
        return base + plan + "\n\nRegla universal del usuario (respetala siempre):\n" + AiSettings.rule.value
    }

    /** Chat de Inicio: arma proyectos nuevos; no toca proyectos existentes. */
    private fun homePrompt() = """
        Sos el asistente de R4A, un IDE de Rust que corre directamente en un celular Android
        (aarch64), sin PC. Estás en la pantalla de INICIO: acá solo se crean proyectos nuevos.
        La pantalla es chica: respondé corto y claro, en el idioma en que te escriba el usuario.

        Herramientas: list_projects (los proyectos que ya tiene) y create_project.
        - Cuando el usuario quiera hacer algo nuevo ("quiero un juego de una pelota que rebota",
          "un programa que calcule..."), proponé un nombre corto y creá el proyecto con
          create_project: template "juego" si es un juego (macroquad), "basico" si no.
        - No inventes un nombre que ya exista: mirá list_projects si hace falta.
        - Después decile que toque "Abrir proyecto": ahí se abre el editor, esta charla sigue
          allá y vas a poder escribir el código, compilarlo y corregirlo.
        - Desde acá no podés leer ni cambiar archivos de ningún proyecto.
    """.trimIndent()

    private fun projectPrompt() = """
        Sos el asistente de programación de R4A, un IDE de Rust que corre directamente en un
        celular Android (aarch64), sin PC. El usuario escribe, compila y corre programas Rust
        en el teléfono con cargo (Rust 1.85, edición 2024). La pantalla es chica: respondé
        corto y claro. Respondé en el idioma en que te escriba el usuario.
        Proyecto abierto ahora: "$projectName".

        Herramientas para LEER: list_files, read_file, search_text, get_problems, get_editor_state.
        Herramientas para PROBAR: cargo_check, run_program.
        Herramientas para CAMBIAR: write_file, edit_file, create_folder, delete_file.
        - Antes de cambiar un archivo, leelo. Para cambios chicos usá edit_file; para archivos
          nuevos o reescrituras completas, write_file.
        - Si el usuario pide algo concreto ("haceme...", "agregá...", "arreglá..."), hacelo con
          las herramientas en vez de solo mostrar el código. Cada cambio le aparece como una
          tarjeta y lo puede deshacer.
        - No podés crear proyectos nuevos desde acá: si el usuario quiere otro proyecto, decile
          que use el chat "AI" de la pantalla de inicio.
        - Después de cambiar código, SIEMPRE usá cargo_check. Si hay errores, leé el archivo,
          corregilos y volvé a chequear, hasta que compile (máximo unos 4 intentos; si no sale,
          explicale al usuario qué falta). Para programas de consola podés usar run_program y
          mirar la salida. Los juegos de macroquad no se pueden ejecutar desde acá: el usuario
          los abre con ▶.
        - Juegos: macroquad 0.4 con default-features = false y fontdue = "=0.9.3" en
          [dependencies] (fontdue más nuevo pide Rust 1.87). Usá #[macroquad::main("Nombre")]
          y async fn main() con loop { ...; next_frame().await }. Las imágenes van con
          include_bytes! (load_texture con archivos no anda en Android). En edición 2024,
          #[no_mangle] se escribe #[unsafe(no_mangle)].
        - Al terminar, contá en una o dos frases qué hiciste.
    """.trimIndent()

    private companion object {
        val nextMessageId = java.util.concurrent.atomic.AtomicLong()
        const val MAX_STEPS = 20
        const val MAX_RATE_RETRIES = 4
        const val MAX_RATE_WAIT_SEC = 65.0
    }
}
