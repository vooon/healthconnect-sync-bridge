package ru.vehq.hcsync.wanderer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Duration

/** Write side of the bridge. An interface so SyncEngine can be tested without a server. */
interface WandererClient {
    /** Verifies base URL + token; returns a short human-readable description on success. */
    suspend fun testConnection(): String

    /** Uploads one GPX document; returns the created trail id. */
    suspend fun uploadGpx(gpx: String, fileName: String): String
}

/** Thrown for any non-2xx answer. [status] 401 means the API token was rejected. */
class WandererHttpException(val status: Int, val serverMessage: String?) :
    IOException("Wanderer HTTP $status" + (serverMessage?.let { ": $it" } ?: ""))

/**
 * Talks to Wanderer's REST API (`/api/v1`) with an API token (`Authorization: Bearer wanderer_key…`).
 * Verified against Wanderer source commit 58d2e3d: `PUT /trail/upload` is multipart/form-data with a
 * `file` part; `GET /api-token` is the cheapest call that fails with 401 on a bad token.
 */
class OkHttpWandererClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = defaultHttpClient(),
) : WandererClient {
    private val apiBase: HttpUrl = normalizeBaseUrl(baseUrl)

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val url = apiBase.newBuilder().addPathSegment("api-token").addQueryParameter("perPage", "1").build()
        val body = execute(Request.Builder().url(url).get().build())
        val json = runCatching { JSONObject(body) }.getOrNull()
        val items = json?.optJSONArray("items")
        val tokenName = items?.optJSONObject(0)?.optString("name").takeUnless { it.isNullOrEmpty() }
        if (tokenName != null) "Connected (token \"$tokenName\")" else "Connected"
    }

    override suspend fun uploadGpx(gpx: String, fileName: String): String = withContext(Dispatchers.IO) {
        val url = apiBase.newBuilder().addPathSegment("trail").addPathSegment("upload").build()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, gpx.toByteArray(Charsets.UTF_8).toRequestBody(GPX_MEDIA_TYPE))
            // Wanderer rejects a trail whose length/elevation is within 50 m of an existing one;
            // a daily walk on the same loop would never upload without this.
            .addFormDataPart("ignoreDuplicates", "true")
            .build()
        val body = execute(Request.Builder().url(url).put(multipart).build())
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull()
        if (id.isNullOrEmpty()) throw IOException("Wanderer upload succeeded but returned no trail id")
        id
    }

    private fun execute(request: Request): String {
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) throw WandererHttpException(401, "no API token configured")
        val authed = request.newBuilder().header("Authorization", "Bearer $token").build()
        http.newCall(authed).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw WandererHttpException(response.code, serverMessage(response, text))
            return text
        }
    }

    private fun serverMessage(response: Response, text: String): String? =
        runCatching { JSONObject(text).optString("message").takeIf { it.isNotEmpty() } }.getOrNull()
            ?: response.message.takeIf { it.isNotEmpty() }

    companion object {
        val GPX_MEDIA_TYPE = "application/gpx+xml".toMediaType()

        /** Upper bound for one request including the server's GPX parsing / elevation correction. */
        val CALL_TIMEOUT: Duration = Duration.ofMinutes(3)

        /**
         * Generous limits: a self-hosted Wanderer may sit behind a VPN on slow storage and only
         * answers an upload after parsing the file, correcting elevation and indexing the trail.
         * A timeout surfaces as a retryable IOException; the record-id lookup before the next
         * attempt prevents a duplicate if the server had in fact finished.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(90))
            .readTimeout(Duration.ofSeconds(120))
            .callTimeout(CALL_TIMEOUT)
            .retryOnConnectionFailure(true)
            .build()

        /** Accepts `https://host`, `https://host/`, or `https://host/api/v1`; always yields `…/api/v1/`. */
        fun normalizeBaseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim().trimEnd('/')
            val root = if (trimmed.endsWith("/api/v1")) trimmed.removeSuffix("/api/v1") else trimmed
            val url = "$root/".toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server URL: $raw")
            return url.newBuilder().addPathSegment("api").addPathSegment("v1").addPathSegment("").build()
        }
    }
}
