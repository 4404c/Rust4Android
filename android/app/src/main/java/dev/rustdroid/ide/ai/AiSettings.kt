package dev.rustdroid.ide.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R4A: ajustes de la IA (proveedor, modelo y API key).
 *
 * Todos los proveedores hablan el formato "compatible con OpenAI"
 * (/models y /chat/completions), así que solo cambia la URL base.
 * La API key se guarda cifrada con una clave del Android Keystore: nunca
 * queda en texto plano en las preferencias.
 */
object AiSettings {

    enum class Provider(val label: String, val baseUrl: String, val keyUrl: String?) {
        GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
        OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        CUSTOM("Otro (URL propia)", "", null),
    }

    data class Config(
        val provider: Provider,
        val customUrl: String,
        val model: String,
        val hasKey: Boolean,
    ) {
        val baseUrl: String get() =
            (if (provider == Provider.CUSTOM) customUrl else provider.baseUrl).trim().trimEnd('/')
        val ready: Boolean get() = hasKey && model.isNotBlank() && baseUrl.isNotBlank()
    }

    private const val PREFS = "r4a_ai"
    private const val KEYSTORE_ALIAS = "r4a_ai_api_key"

    private val _config = MutableStateFlow(Config(Provider.GROQ, "", "", false))
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Llamar una vez al arrancar la app (RustDroidApp.onCreate). */
    fun init(context: Context) {
        val p = prefs(context)
        val provider = p.getString("provider", null)
            ?.let { name -> Provider.entries.firstOrNull { it.name == name } } ?: Provider.GROQ
        _config.value = Config(
            provider = provider,
            customUrl = p.getString("custom_url", "") ?: "",
            model = p.getString(modelKey(provider), "") ?: "",
            hasKey = p.contains(keyPref(provider)),
        )
    }

    fun setProvider(context: Context, provider: Provider) {
        val p = prefs(context)
        p.edit().putString("provider", provider.name).apply()
        // cada proveedor recuerda su propia clave y su modelo
        _config.value = _config.value.copy(
            provider = provider,
            model = p.getString(modelKey(provider), "") ?: "",
            hasKey = p.contains(keyPref(provider)),
        )
    }

    fun setCustomUrl(context: Context, url: String) {
        prefs(context).edit().putString("custom_url", url.trim()).apply()
        _config.value = _config.value.copy(customUrl = url.trim())
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(modelKey(_config.value.provider), model).apply()
        _config.value = _config.value.copy(model = model)
    }

    fun setApiKey(context: Context, key: String) {
        val pref = keyPref(_config.value.provider)
        if (key.isBlank()) {
            prefs(context).edit().remove(pref).apply()
            _config.value = _config.value.copy(hasKey = false)
        } else {
            prefs(context).edit().putString(pref, encrypt(key.trim())).apply()
            _config.value = _config.value.copy(hasKey = true)
        }
    }

    /** La clave descifrada, solo para armar la llamada HTTP. Nunca mostrarla. */
    fun apiKey(context: Context): String? =
        prefs(context).getString(keyPref(_config.value.provider), null)
            ?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun modelKey(p: Provider) = "model_${p.name}"
    private fun keyPref(p: Provider) = "key_${p.name}"

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
