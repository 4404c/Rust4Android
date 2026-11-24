package dev.rustdroid.ide.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R4A: ajustes de la IA. El usuario guarda varias API keys ("conexiones"),
 * cada una con un nombre, su proveedor y el modelo elegido; una está activa
 * y es la que usa el Chat.
 *
 * Todos los proveedores hablan el formato "compatible con OpenAI"
 * (/models y /chat/completions), así que solo cambia la URL base.
 * Cada API key se guarda cifrada con una clave del Android Keystore: nunca
 * queda en texto plano en las preferencias.
 */
object AiSettings {

    enum class Provider(val label: String, val baseUrl: String, val keyUrl: String?) {
        GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
        OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        /** Claude: usa el SDK oficial ([AnthropicChat]), no el formato de OpenAI. */
        ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", "https://console.anthropic.com/settings/keys"),
        CUSTOM("URL propia", "", null),
    }

    /** Una API key guardada. La clave en sí no está acá: ver [apiKey]. */
    data class Connection(
        val id: String,
        val name: String,
        val provider: Provider,
        val customUrl: String,
        val model: String,
    ) {
        val baseUrl: String get() =
            (if (provider == Provider.CUSTOM) customUrl else provider.baseUrl).trim().trimEnd('/')
        val ready: Boolean get() = model.isNotBlank() && baseUrl.isNotBlank()
    }

    data class State(val connections: List<Connection>, val activeId: String?) {
        val active: Connection? get() =
            connections.firstOrNull { it.id == activeId } ?: connections.firstOrNull()
    }

    private const val PREFS = "r4a_ai"
    private const val KEYSTORE_ALIAS = "r4a_ai_api_key"

    private val _state = MutableStateFlow(State(emptyList(), null))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Llamar una vez al arrancar la app (RustDroidApp.onCreate). */
    fun init(context: Context) {
        val p = prefs(context)
        if (!p.contains("connections")) migrateSingleKeys(context)
        val list = runCatching { parse(p.getString("connections", "[]") ?: "[]") }.getOrDefault(emptyList())
        _state.value = State(list, p.getString("active_id", null))
    }

    fun add(context: Context, name: String, provider: Provider, customUrl: String, key: String): Connection {
        val c = Connection(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { provider.label },
            provider = provider,
            customUrl = customUrl.trim(),
            // Claude: arranca en el modelo recomendado (se puede cambiar)
            model = if (provider == Provider.ANTHROPIC) "claude-opus-5-5" else "",
        )
        prefs(context).edit().putString(keyPref(c.id), encrypt(key.trim())).apply()
        save(context, State(_state.value.connections + c, c.id))
        return c
    }

    fun rename(context: Context, id: String, name: String) =
        update(context, id) { it.copy(name = name.trim().ifBlank { it.provider.label }) }

    fun setModel(context: Context, id: String, model: String) =
        update(context, id) { it.copy(model = model) }

    fun setActive(context: Context, id: String) =
        save(context, _state.value.copy(activeId = id))

    fun remove(context: Context, id: String) {
        prefs(context).edit().remove(keyPref(id)).apply()
        val rest = _state.value.connections.filterNot { it.id == id }
        val active = _state.value.activeId.takeIf { it != id } ?: rest.firstOrNull()?.id
        save(context, State(rest, active))
    }

    /** La clave descifrada, solo para armar la llamada HTTP. Nunca mostrarla. */
    fun apiKey(context: Context, id: String): String? =
        prefs(context).getString(keyPref(id), null)
            ?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun update(context: Context, id: String, f: (Connection) -> Connection) =
        save(context, _state.value.copy(connections = _state.value.connections.map { if (it.id == id) f(it) else it }))

    private fun save(context: Context, s: State) {
        val arr = JSONArray()
        s.connections.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("provider", c.provider.name)
                    .put("custom_url", c.customUrl)
                    .put("model", c.model),
            )
        }
        prefs(context).edit()
            .putString("connections", arr.toString())
            .apply { if (s.activeId == null) remove("active_id") else putString("active_id", s.activeId) }
            .apply()
        _state.value = s
    }

    private fun parse(json: String): List<Connection> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val provider = Provider.entries.firstOrNull { it.name == o.optString("provider") } ?: return@mapNotNull null
            Connection(
                id = o.getString("id"),
                name = o.optString("name", provider.label),
                provider = provider,
                customUrl = o.optString("custom_url", ""),
                model = o.optString("model", ""),
            )
        }
    }

    /**
     * Antes había una sola clave por proveedor (key_GROQ, model_GROQ…).
     * Cada una pasa a ser una conexión con el nombre del proveedor.
     */
    private fun migrateSingleKeys(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        val arr = JSONArray()
        var activeId: String? = null
        val oldActive = p.getString("provider", null)
        Provider.entries.forEach { prov ->
            val stored = p.getString("key_${prov.name}", null) ?: return@forEach
            val id = UUID.randomUUID().toString()
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("name", prov.label)
                    .put("provider", prov.name)
                    .put("custom_url", p.getString("custom_url", "") ?: "")
                    .put("model", p.getString("model_${prov.name}", "") ?: ""),
            )
            edit.putString(keyPref(id), stored) // ya está cifrada, se copia tal cual
            edit.remove("key_${prov.name}").remove("model_${prov.name}")
            if (prov.name == oldActive || activeId == null) activeId = id
        }
        edit.remove("provider").remove("custom_url")
        edit.putString("connections", arr.toString())
        activeId?.let { edit.putString("active_id", it) }
        edit.apply()
    }

    private fun keyPref(id: String) = "apikey_$id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- cifrado con Android Keystore (AES-GCM) ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
