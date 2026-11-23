package dev.rustdroid.ide.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.ai.AiClient
import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * R4A fase 1 de la IA: chat simple dentro del editor. Todavía no toca
 * archivos; solo conversa (con streaming).
 */
class ChatViewModel(
    private val container: AppContainer,
    private val projectName: String,
) : ViewModel() {

    enum class Role { USER, ASSISTANT, ERROR }

    data class ChatMessage(val role: Role, val text: String)

    private val client = AiClient(container.http)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var job: Job? = null

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        val cfg = AiSettings.config.value
        val key = AiSettings.apiKey(container.context)
        if (!cfg.ready || key == null) {
            _messages.update { it + ChatMessage(Role.ERROR, container.context.getString(dev.rustdroid.ide.R.string.ai_not_configured)) }
            return
        }

        // historial para la API: sin los mensajes de error locales
        val history = _messages.value.filter { it.role != Role.ERROR }
        _messages.update { it + ChatMessage(Role.USER, prompt) + ChatMessage(Role.ASSISTANT, "") }
        _busy.value = true

        job = viewModelScope.launch {
            try {
                val apiMessages = buildList {
                    add(AiClient.Message("system", systemPrompt()))
                    history.forEach {
                        add(AiClient.Message(if (it.role == Role.USER) "user" else "assistant", it.text))
                    }
                    add(AiClient.Message("user", prompt))
                }
                client.chatStream(cfg.baseUrl, key, cfg.model, apiMessages) { delta ->
                    appendToLast(delta)
                }
                dropEmptyLast()
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
                _busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
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
        Sos el asistente de programación de R4A (Rust 4 Android), un IDE de Rust que corre
        directamente en un celular Android (aarch64), sin PC. El usuario escribe, compila
        y corre programas Rust en el teléfono con cargo. Los juegos se hacen con la crate
        macroquad y se pueden exportar como APK. La pantalla es chica: respondé corto y
        claro, con bloques de código en Rust cuando haga falta. Respondé en el idioma en
        que te escriba el usuario.
        Proyecto abierto ahora: "$projectName".
        Por ahora solo podés conversar: todavía no podés leer ni modificar archivos del proyecto.
    """.trimIndent()

    companion object {
        fun factory(container: AppContainer, projectName: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(container, projectName) as T
        }
    }
}
