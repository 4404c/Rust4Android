package dev.rustdroid.ide.ai

import android.content.Context
import okhttp3.OkHttpClient

/** R4A: la lista de modelos de una conexión, según el proveedor. */
object ModelCatalog {
    suspend fun list(context: Context, http: OkHttpClient, c: AiSettings.Connection): List<String> {
        // R4A (auditoría B3): descifrar fuera del hilo principal
        val key = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AiSettings.apiKey(context, c.id) }
            ?: throw AiClient.AiException(context.getString(dev.rustdroid.ide.R.string.ai_settings_key_unreadable))
        return when (c.provider) {
            AiSettings.Provider.ANTHROPIC -> AnthropicChat().listModels(key).sorted()
            AiSettings.Provider.CHATGPT -> ChatGptClient(http).listModels(ChatGptAuth(http).accessToken(context, c.id))
            else -> AiClient(http).listModels(c.baseUrl, key)
        }
    }
}
