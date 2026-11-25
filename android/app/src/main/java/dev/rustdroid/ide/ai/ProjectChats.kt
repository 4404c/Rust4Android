package dev.rustdroid.ide.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * R4A: todas las charlas con la IA. Una por proceso, en AppContainer.
 * Cada proyecto tiene sus charlas en files/chats/<sha1 de la ruta>/<id>.json.
 */
class ChatStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val repo: dev.rustdroid.ide.projects.ProjectRepository,
) {
    private val root = File(context.filesDir, "chats")
    private val projects = HashMap<String, ProjectChats>()

    /** Qué pasa con la IA en cada proyecto: para la tarjeta del proyecto en Inicio. */
    data class Activity(val name: String, val working: Boolean = false, val unread: Boolean = false, val error: Boolean = false)

    private val _activity = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Activity>>(emptyMap())
    /** Clave = ruta canónica del proyecto ([keyOf]). */
    val activity: kotlinx.coroutines.flow.StateFlow<Map<String, Activity>> = _activity

    fun keyOf(projectDir: File) = key(projectDir)

    /** Lo llama [ProjectChats] cuando una charla empieza o termina de trabajar. */
    internal fun report(key: String, name: String, working: Boolean, finishedUnseen: Boolean, error: Boolean) {
        val old = _activity.value[key] ?: Activity(name)
        val now = old.copy(
            working = working,
            unread = if (working) old.unread else (old.unread || finishedUnseen),
            error = if (working) false else error,
        )
        _activity.value = _activity.value + (key to now)
        // servicio en primer plano mientras alguna trabaja; aviso al terminar sin mirar
        AiWorkService.update(context, _activity.value.values.filter { it.working }.map { it.name })
        if (!working && finishedUnseen) AiWorkService.notifyFinished(context, name, error)
    }

    fun isUnread(key: String) = _activity.value[key]?.unread == true

    /** Se abrió el proyecto: ya no hay nada sin leer. */
    internal fun seen(key: String) {
        val old = _activity.value[key] ?: return
        if (old.unread) _activity.value = _activity.value + (key to old.copy(unread = false))
    }

    /** Las charlas del proyecto en [projectDir] (las carga del disco la primera vez). */
    fun project(projectDir: File, projectName: String): ProjectChats =
        projects.getOrPut(key(projectDir)) {
            val hash = MessageDigest.getInstance("SHA-1").digest(key(projectDir).toByteArray())
                .joinToString("") { "%02x".format(it) }
            // antes había UN archivo por proyecto: pasa a ser la primera charla
            val dir = File(root, hash)
            val old = File(root, "$hash.json")
            if (old.isFile) {
                dir.mkdirs()
                old.renameTo(File(dir, "${old.lastModified()}.json"))
            }
            ProjectChats(context, http, repo, projectName, dir, store = this, key = key(projectDir))
        }

    /**
     * R4A (auditoría B18): el proyecto se borró o cambió de nombre: corta lo
     * que estén haciendo sus charlas y las suelta de memoria (si no, un
     * proyecto nuevo con la misma ruta heredaba las charlas viejas).
     */
    fun forget(canonicalPath: String) {
        projects.remove(canonicalPath)?.stopAll()
        _activity.value = _activity.value - canonicalPath
        AiWorkService.update(context, _activity.value.values.filter { it.working }.map { it.name })
    }

    /** Las charlas del chat de Inicio (crear proyectos). */
    val home: ProjectChats by lazy {
        ProjectChats(context, http, repo, "Inicio", File(root, "home"), home = true, store = this, key = "home")
    }

    /** La IA creó un proyecto y el usuario lo abre: la charla sigue allá (como charla nueva). */
    fun copyTo(from: ChatSession, projectDir: File, projectName: String) {
        project(projectDir, projectName).adopt(from, projectName)
    }

    private fun key(projectDir: File) = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)
}

/**
 * Las charlas de UN proyecto. Hay una actual ([current]); "Nueva charla"
 * archiva la actual y empieza una vacía (la IA arranca sin recordar nada).
 * Las anteriores se pueden retomar o borrar desde la lista.
 */
class ProjectChats(
    private val context: Context,
    private val http: OkHttpClient,
    private val repo: dev.rustdroid.ide.projects.ProjectRepository,
    private val projectName: String,
    private val dir: File,
    private val home: Boolean = false,
    private val store: ChatStore? = null,
    private val key: String = "",
) : ChatHistory {
    override fun chats(): List<Summary> = _list.value
    override fun currentChatId(): String? = _current.value.id
    override fun transcript(id: String): String? = sessions[id]?.transcript()

    /** Modelos disponibles para una conexión (para cambiarlo desde el chat). */
    suspend fun listModels(c: AiSettings.Connection): List<String> = ModelCatalog.list(context, http, c)

    data class Summary(val id: String, val title: String, val updatedAt: Long, val messages: Int)

    private val sessions = LinkedHashMap<String, ChatSession>()

    private val _list = MutableStateFlow<List<Summary>>(emptyList())
    /** Charlas con al menos un mensaje, la más reciente primero. */
    val list: StateFlow<List<Summary>> = _list.asStateFlow()

    private val _current: MutableStateFlow<ChatSession>
    val current: StateFlow<ChatSession>

    private var tools: ChatTools? = null

    init {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedBy { it.lastModified() }
            .forEach { f -> sessions[f.nameWithoutExtension] = newSession(f).also { it.load() } }
        // arranca en la última charla usada
        val start = sessions.values.maxByOrNull { it.updatedAt } ?: create()
        _current = MutableStateFlow(start)
        current = _current.asStateFlow()
        refreshList()
    }

    /** El editor de este proyecto (o la pantalla de Inicio) está abierto. */
    fun attach(tools: ChatTools) {
        this.tools = tools
        _current.value.attach(tools)
        // si la IA te había escrito, esa respuesta se muestra (no se oculta al entrar)
        openedWithUnread = store?.isUnread(key) == true
        store?.seen(key)
    }

    private var openedWithUnread = false

    fun detach(tools: ChatTools) {
        if (this.tools !== tools) return
        _current.value.detach(tools)
        this.tools = null
    }

    /** Se abrió el proyecto: en la charla actual, lo de antes queda oculto. */
    fun markVisitStart() {
        if (openedWithUnread) { openedWithUnread = false; return }
        _current.value.markVisitStart()
    }

    /** "Nueva charla": si la actual está vacía, se queda en esa. */
    fun newChat() {
        if (_current.value.isEmpty()) return
        switchTo(create())
    }

    fun open(id: String) {
        sessions[id]?.let { if (it !== _current.value) switchTo(it) }
    }

    /** Borra una charla del teléfono. Si era la actual, pasa a la más reciente (o a una vacía). */
    fun delete(id: String) {
        val s = sessions.remove(id) ?: return
        s.clear() // corta lo que esté haciendo y borra su archivo
        if (s === _current.value) {
            switchTo(sessions.values.filter { !it.isEmpty() }.maxByOrNull { it.updatedAt } ?: create())
        }
        refreshList()
    }

    /** R4A (auditoría B18): corta todas las charlas de este proyecto. */
    fun stopAll() = sessions.values.forEach { it.stop() }

    fun adopt(from: ChatSession, newName: String) {
        val target = if (_current.value.isEmpty()) _current.value else create()
        target.adopt(from, newName)
        _current.value = target
        refreshList()
    }

    private fun switchTo(s: ChatSession) {
        val old = _current.value
        tools?.let { t -> old.detach(t); s.attach(t) }
        s.revealOlder() // al retomar una charla se ve entera
        _current.value = s
        refreshList()
    }

    private fun create(): ChatSession {
        val id = System.currentTimeMillis().toString()
        return newSession(File(dir, "$id.json")).also { sessions[id] = it }
    }

    private fun newSession(file: File) =
        ChatSession(
            context, http, repo, projectName, file, home = home,
            onSaved = { refreshList() },
            onBusy = { s, working, error ->
                // "sin mirar" = el usuario ya no está en el proyecto (o la app está en segundo plano)
                val unseen = !working && (!s.attached || !AppVisibility.foreground)
                store?.report(key, projectName, working, unseen, error)
            },
        )

    private fun refreshList() {
        _list.value = sessions.values
            .filter { !it.isEmpty() }
            .sortedByDescending { it.updatedAt }
            .map { Summary(it.id, it.title, it.updatedAt, it.messages.value.size) }
    }
}
