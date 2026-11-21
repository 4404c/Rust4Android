package dev.rustdroid.ide.projects

import dev.rustdroid.ide.model.CrateSummary
import dev.rustdroid.ide.model.parseCratesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * crates.io search client. Complies with their API policy: identifies via
 * User-Agent, low request rate (debounced at the UI layer).
 */
class CratesIoClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun search(query: String, limit: Int = 20): List<CrateSummary> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val url = "https://crates.io/api/v1/crates?q=${
                java.net.URLEncoder.encode(q, "UTF-8")
            }&per_page=$limit"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "R4A/0.2 (https://github.com/4404c/Rust4Android)")
                .header("Accept", "application/json")
                .build()
            val body = await(request).use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("crates.io returned HTTP ${resp.code}")
                }
                resp.body?.string() ?: throw IOException("empty body")
            }
            parseCratesResponse(body)
        }

    /**
     * R4A: details for several crates in ONE request (crates.io `ids[]`
     * filter), most downloaded first. Used for the "popular" list.
     */
    suspend fun byNames(names: List<String>): List<CrateSummary> =
        withContext(Dispatchers.IO) {
            if (names.isEmpty()) return@withContext emptyList()
            val ids = names.joinToString("&") {
                "ids%5B%5D=" + java.net.URLEncoder.encode(it, "UTF-8")
            }
            val request = Request.Builder()
                .url("https://crates.io/api/v1/crates?$ids&per_page=${names.size}")
                .header("User-Agent", "R4A/0.2 (https://github.com/4404c/Rust4Android)")
                .header("Accept", "application/json")
                .build()
            val body = await(request).use { resp ->
                if (!resp.isSuccessful) throw IOException("crates.io returned HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("empty body")
            }
            parseCratesResponse(body).sortedByDescending { it.downloads }
        }

    /** R4A: every published version of [name] (for Rust-version-aware adds). */
    suspend fun versions(name: String): List<CrateVersions.Version> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://crates.io/api/v1/crates/${java.net.URLEncoder.encode(name, "UTF-8")}/versions")
                .header("User-Agent", "R4A/0.2 (https://github.com/4404c/Rust4Android)")
                .header("Accept", "application/json")
                .build()
            val body = await(request).use { resp ->
                if (!resp.isSuccessful) throw IOException("crates.io returned HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("empty body")
            }
            CrateVersions.parse(body)
        }

    private suspend fun await(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }
}
