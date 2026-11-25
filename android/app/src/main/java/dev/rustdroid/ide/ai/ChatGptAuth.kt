package dev.rustdroid.ide.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * R4A: "Continuar con ChatGPT" — usar el plan de ChatGPT del usuario en
 * lugar de una API key (programa "Sign in with ChatGPT" de OpenAI para apps
 * de código abierto que corren en el equipo del usuario).
 *
 * Según la documentación de OpenAI (developers.openai.com/siwc):
 * - OAuth con PKCE (S256) en auth.openai.com; la primera vez la app se
 *   registra sola con client_id=dynamic_agent_client y la respuesta trae el
 *   client_id definitivo, que se guarda y se reusa.
 * - La vuelta del navegador es SOLO a http://127.0.0.1:<puerto>/auth/callback:
 *   la app escucha un momento en ese puerto del propio teléfono.
 * - Token de acceso: 1 hora; el de renovación: 30 días, y cambia en cada uso.
 * Los tokens se guardan cifrados como el "secreto" de la conexión.
 */
class ChatGptAuth(private val http: OkHttpClient) {

    class AuthException(message: String) : Exception(message)

    private val random = SecureRandom()

    /**
     * Inicia sesión: abre el navegador con [openBrowser] y espera (hasta
     * 5 minutos) a que OpenAI vuelva a 127.0.0.1. Devuelve los tokens en JSON.
     */
    suspend fun signIn(context: Context, openBrowser: (Uri) -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = randomToken(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(24)
        val nonce = randomToken(24)
        val savedClient = prefs(context).getString(KEY_CLIENT_ID, null)

        val server = try {
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) // solo el puerto puede cambiar
        }
        val job = currentCoroutineContext()[Job]
        val closer = job?.invokeOnCompletion { runCatching { server.close() } }
        try {
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            val url = Uri.parse(AUTHORIZE_URL).buildUpon().apply {
                appendQueryParameter("client_id", savedClient ?: "dynamic_agent_client")
                if (savedClient == null) appendQueryParameter("agent_name_hint", APP_NAME)
                appendQueryParameter("ext_agent_host_id", hostId(context))
                appendQueryParameter("response_type", "code")
                appendQueryParameter("redirect_uri", redirect)
                appendQueryParameter("scope", SCOPES)
                appendQueryParameter("resource", RESOURCE)
                appendQueryParameter("state", state)
                appendQueryParameter("nonce", nonce)
                appendQueryParameter("code_challenge_method", "S256")
                appendQueryParameter("code_challenge", challenge)
            }.build()
            withContext(Dispatchers.Main) { openBrowser(url) }

            val params = waitForCallback(server, state)
            params["error"]?.let {
                throw AuthException(if (it == "access_denied") "Cancelaste el inicio de sesión." else "OpenAI respondió: $it")
            }
            if (params["state"] != state) throw AuthException("La respuesta no coincide con este inicio de sesión. Probá de nuevo.")
            val code = params["code"] ?: throw AuthException("OpenAI no devolvió un código.")
            val clientId = params["client_id"] ?: savedClient
                ?: throw AuthException("OpenAI no devolvió el client_id de la app.")
            prefs(context).edit().putString(KEY_CLIENT_ID, clientId).apply()

            val tokens = postForm(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", clientId)
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirect)
                    .add("resource", RESOURCE)
                    .build(),
            )
            tokens.optString("id_token").takeIf { it.isNotEmpty() }?.let { idToken ->
                if (jwtClaim(idToken, "nonce") != nonce) throw AuthException("La respuesta de OpenAI no es válida (nonce). Probá de nuevo.")
            }
            if (!tokens.optString("scope").split(" ").contains("chatgpt.tokens.use.direct")) {
                throw AuthException("Tu cuenta no dio permiso para usar el plan de ChatGPT en esta app.")
            }
            store(tokens, clientId, previousRefresh = null).toString()
        } finally {
            closer?.dispose()
            runCatching { server.close() }
        }
    }

    /** Token de acceso válido para [connectionId]; lo renueva si está por vencer. */
    suspend fun accessToken(context: Context, connectionId: String): String = refreshLock.withLock {
        // R4A (auditoría B3): Keystore y prefs fuera del hilo principal
        val saved = withContext(Dispatchers.IO) { AiSettings.apiKey(context, connectionId) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw AuthException(SESSION_LOST)
        if (saved.optLong("expires_at") - 60_000 > System.currentTimeMillis()) {
            return@withLock saved.getString("access_token")
        }
        val clientId = saved.optString("client_id")
        val refresh = saved.optString("refresh_token")
        if (clientId.isEmpty() || refresh.isEmpty()) throw AuthException(SESSION_LOST)
        val fresh = withContext(Dispatchers.IO) {
            postForm(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("refresh_token", refresh)
                    .add("resource", RESOURCE)
                    .build(),
            )
        }
        val updated = store(fresh, clientId, previousRefresh = refresh)
        withContext(Dispatchers.IO) { AiSettings.setSecret(context, connectionId, updated.toString()) }
        updated.getString("access_token")
    }

    /** Cerrar sesión: revoca el token de renovación en OpenAI (si falla, igual se borra local). */
    suspend fun signOut(context: Context, connectionId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val saved = JSONObject(AiSettings.apiKey(context, connectionId) ?: return@runCatching)
            val config = http.newCall(Request.Builder().url(OPENID_CONFIG).get().build()).execute()
                .use { JSONObject(it.body?.string().orEmpty()) }
            val endpoint = config.optString("revocation_endpoint").ifEmpty { return@runCatching }
            http.newCall(
                Request.Builder().url(endpoint).post(
                    FormBody.Builder()
                        .add("token", saved.optString("refresh_token"))
                        .add("token_type_hint", "refresh_token")
                        .add("client_id", saved.optString("client_id"))
                        .build(),
                ).build(),
            ).execute().close()
        }
    }

    // ---------------- ayudas ----------------

    /** Espera el GET del navegador a /auth/callback y le contesta con una página corta. */
    /**
     * R4A (auditoría B2): cualquier app del celu puede conectarse a
     * 127.0.0.1 mientras esperamos. Una vuelta con otro `state` ya no corta el
     * inicio de sesión: se le contesta 400 y se sigue esperando la verdadera.
     * La primera línea se lee con tope (8 KB) para que nadie llene la memoria.
     */
    private fun waitForCallback(server: ServerSocket, expectedState: String): Map<String, String> {
        server.soTimeout = 5 * 60 * 1000
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw AuthException("Pasaron 5 minutos sin respuesta de OpenAI. Probá de nuevo.")
            } catch (e: java.net.SocketException) {
                throw AuthException("Se canceló el inicio de sesión.")
            }
            socket.use { s ->
                s.soTimeout = 10_000
                val line = readFirstLine(s.getInputStream(), 8 * 1024)
                val target = line.split(" ").getOrNull(1).orEmpty()
                val uri = Uri.parse("http://127.0.0.1$target")
                if (uri.path != "/auth/callback") {
                    s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                if (uri.getQueryParameter("state") != expectedState) {
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                val ok = uri.getQueryParameter("error") == null
                val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                    |<title>R4A</title></head><body style="font-family:sans-serif;background:#111;color:#eee;text-align:center;padding:48px 16px">
                    |<h2>${if (ok) "Listo ✓" else "No se pudo iniciar sesión"}</h2>
                    |<p>${if (ok) "Ya podés volver a R4A." else "Volvé a R4A e intentá de nuevo."}</p>
                    |<p><a style="color:#9cd634;font-size:20px" href="intent:#Intent;package=dev.rustdroid.ide;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;end">Volver a R4A</a></p>
                    |</body></html>""".trimMargin()
                val body = html.toByteArray()
                s.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body,
                )
                s.getOutputStream().flush()
                return uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            }
        }
    }

    /** La primera línea (hasta el salto de línea), con [max] bytes como mucho. */
    private fun readFirstLine(input: java.io.InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun postForm(body: FormBody): JSONObject {
        http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (!resp.isSuccessful) {
                val err = json.optString("error")
                throw AuthException(
                    when (err) {
                        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
                        "refresh_token_invalidated", "refresh_token_reused" -> SESSION_LOST
                        "invalid_client" -> "OpenAI no reconoce a R4A como app (invalid_client)."
                        else -> "Error ${resp.code} al iniciar sesión con ChatGPT${if (err.isNotEmpty()) ": $err" else ""}"
                    },
                )
            }
            return json
        }
    }

    /** Lo que se guarda (cifrado): tokens, vencimiento y el client_id de la app. */
    private fun store(tokens: JSONObject, clientId: String, previousRefresh: String?) = JSONObject()
        .put("access_token", tokens.getString("access_token"))
        // el de renovación cambia en cada uso; si no vino uno nuevo, se conserva el anterior
        .put("refresh_token", tokens.optString("refresh_token").ifEmpty { previousRefresh.orEmpty() })
        .put("expires_at", System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000)
        .put("client_id", clientId)

    /** Identificador de ESTE teléfono para OpenAI (no es un dato personal). */
    private fun hostId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_HOST_ID, null)?.let { return it }
        return "urn:uuid:${UUID.randomUUID()}".also { p.edit().putString(KEY_HOST_ID, it).commit() }
    }

    private fun jwtClaim(jwt: String, claim: String): String? = runCatching {
        val payload = jwt.split(".")[1]
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optString(claim)
    }.getOrNull()

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let(::b64url)

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun prefs(context: Context) = context.getSharedPreferences("r4a_ai", Context.MODE_PRIVATE)

    companion object {
        /** Una renovación a la vez en toda la app: el token de renovación cambia en cada uso. */
        private val refreshLock = Mutex()
        const val APP_NAME = "R4A"
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val PORT = 1455
        private const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
        private const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
        private const val OPENID_CONFIG = "https://auth.openai.com/.well-known/openid-configuration"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private const val KEY_HOST_ID = "chatgpt_host_id"
        private const val KEY_CLIENT_ID = "chatgpt_client_id"
        private const val SESSION_LOST = "Se cerró la sesión de ChatGPT. Borrá esta conexión y volvé a iniciar sesión."
    }
}
