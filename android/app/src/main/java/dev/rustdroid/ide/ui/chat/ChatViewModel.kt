package dev.rustdroid.ide.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.ai.AiClient
import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.ai.ProjectTools
import dev.rustdroid.ide.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * R4A: chat con la IA dentro del editor.
 * Fase 2: la IA puede LEER el proyecto con [ProjectTools] (archivos,
 * búsqueda, Problems, qué está abierto). Todavía no modifica nada.
 *
 * Ciclo: se manda el historial + las herramientas; si el modelo pide
 * herramientas, se ejecutan, se le devuelven los resultados y se repite
 * hasta que contesta con texto (o se llega a [MAX_STEPS]).
 */
class ChatViewModel(
    private val container: AppContainer,
    private val projectName: String,
    private val tools: ProjectTools,
) : ViewModel() {

    /** TOOL = una línea chiquita con lo que hizo la IA ("Leyó src/main.rs"). */
    enum class Role { USER, ASSISTANT, TOOL, ERROR }

    data class ChatMessage(val role: Role, val text: String)

    private val client = AiClient(container.http)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Historial tal como lo ve la API (con pedidos y resultados de herramientas). */
    private val apiHistory = mutableListOf<AiClient.Message>()

    /** false si el modelo elegido no acepta herramientas: se sigue sin ellas. */
    private var toolsSupported = true

    private var job: Job? = null

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        // la API key activa (se puede cambiar desde la cabecera del chat)
        val cfg = AiSettings.state.value.active
        val key = cfg?.let { AiSettings.apiKey(container.context, it.id) }
        if (cfg == null || !cfg.ready || key == null) {
            _messages.update { it + ChatMessage(Role.ERROR, container.context.getString(dev.rustdroid.ide.R.string.ai_not_configured)) }
            return
        }

        _messages.update { it + ChatMessage(Role.USER, prompt) }
        apiHistory += AiClient.Message("user", prompt)
        _busy.value = true

        job = viewModelScope.launch {
            try {
                for (step in 1..MAX_STEPS) {
                    _messages.update { it + ChatMessage(Role.ASSISTANT, "") }
                    val reply = ask(cfg.baseUrl, key, cfg.model)
                    dropEmptyLast()
                    apiHistory += AiClient.Message("assistant", reply.content, toolCalls = reply.toolCalls)
                    if (reply.toolCalls.isEmpty()) break

                    for (call in reply.toolCalls) {
                        val out = tools.execute(call.name, call.arguments)
                        _messages.update { it + ChatMessage(Role.TOOL, out.summary) }
                        apiHistory += AiClient.Message("tool", out.result, toolCallId = call.id)
                    }
                    if (step == MAX_STEPS) {
                        _messages.update { it + ChatMessage(Role.ERROR, "La IA usó demasiadas herramientas seguidas; se cortó acá. Pedile que siga.") }
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
            }
        }
    }

    /** Una vuelta al modelo. Si rechaza las herramientas, reintenta sin ellas. */
    private suspend fun ask(baseUrl: String, key: String, model: String): AiClient.Reply {
        val messages = listOf(AiClient.Message("system", systemPrompt())) + apiHistory
        if (toolsSupported) {
            try {
                return client.chatStream(baseUrl, key, model, messages, tools.definitions) { appendToLast(it) }
            } catch (e: AiClient.AiException) {
                val msg = e.message.orEmpty().lowercase()
                if (!("tool" in msg || "function" in msg)) throw e
                toolsSupported = false
                _messages.update {
                    it.dropLast(1) + ChatMessage(Role.TOOL, "Este modelo no puede leer el proyecto; contesta sin mirarlo") + ChatMessage(Role.ASSISTANT, "")
                }
            }
        }
        return client.chatStream(baseUrl, key, model, messages, null) { appendToLast(it) }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        apiHistory.clear()
        toolsSupported = true
    }

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
        en el teléfono con cargo. Los juegos se hacen con la crate macroquad y se pueden
        exportar como APK. La pantalla es chica: respondé corto y claro, con bloques de código
        en Rust cuando haga falta. Respondé en el idioma en que te escriba el usuario.
        Proyecto abierto ahora: "$projectName".

        Tenés herramientas para LEER el proyecto: list_files, read_file, search_text,
        get_problems y get_editor_state. Usalas antes de opinar sobre el código del usuario
        en vez de adivinar (por ejemplo, leé src/main.rs y Cargo.toml, o mirá los errores con
        get_problems). Todavía NO podés crear, modificar ni borrar archivos ni ejecutar nada:
        si hace falta un cambio, mostrá el código y explicá dónde va para que lo haga el usuario.
    """.trimIndent()

    companion object {
        private const val MAX_STEPS = 8

        fun factory(container: AppContainer, projectName: String, bridge: ProjectTools.Bridge) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(container, projectName, ProjectTools(bridge)) as T
            }
    }
}
