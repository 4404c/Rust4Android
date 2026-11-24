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
            ProjectChats(context, http, repo, projectName, dir)
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
) {
    data class Summary(val id: String, val title: String, val updatedAt: Long, val messages: Int)

    private val sessions = LinkedHashMap<String, ChatSession>()

    private val _list = MutableStateFlow<List<Summary>>(emptyList())
    /** Charlas con al menos un mensaje, la más reciente primero. */
    val list: StateFlow<List<Summary>> = _list.asStateFlow()

    private val _current: MutableStateFlow<ChatSession>
    val current: StateFlow<ChatSession>

    private var bridge: ProjectTools.Bridge? = null

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

    /** El editor de este proyecto está abierto. */
    fun attach(bridge: ProjectTools.Bridge) {
        this.bridge = bridge
        _current.value.attach(bridge)
    }

    fun detach(bridge: ProjectTools.Bridge) {
        if (this.bridge !== bridge) return
        _current.value.detach(bridge)
        this.bridge = null
    }

    /** Se abrió el proyecto: en la charla actual, lo de antes queda oculto. */
    fun markVisitStart() = _current.value.markVisitStart()

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

    fun adopt(from: ChatSession, newName: String) {
        val target = if (_current.value.isEmpty()) _current.value else create()
        target.adopt(from, newName)
        _current.value = target
        refreshList()
    }

    private fun switchTo(s: ChatSession) {
        val old = _current.value
        bridge?.let { b -> old.detach(b); s.attach(b) }
        s.revealOlder() // al retomar una charla se ve entera
        _current.value = s
        refreshList()
    }

    private fun create(): ChatSession {
        val id = System.currentTimeMillis().toString()
        return newSession(File(dir, "$id.json")).also { sessions[id] = it }
    }

    private fun newSession(file: File) =
        ChatSession(context, http, repo, projectName, file, onSaved = { refreshList() })

    private fun refreshList() {
        _list.value = sessions.values
            .filter { !it.isEmpty() }
            .sortedByDescending { it.updatedAt }
            .map { Summary(it.id, it.title, it.updatedAt, it.messages.value.size) }
    }
}
