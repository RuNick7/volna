package app.volna.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** HTTP API сервиса Volna: тот же, что у веб-версии. */
class VolnaApi(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // первая сборка профиля на большой библиотеке бывает долгой
        .build()

    val base: String get() = prefs.serviceUrl

    /** Ответы API содержат пути вида /api/stream/…: дополняем адресом сервиса. */
    fun url(path: String): String = if (path.isEmpty() || path.startsWith("http")) path else base + path

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val url = (base + path).toHttpUrlOrNull() ?: throw ApiException(0, "Неверный адрес сервиса: $base")
            val payload = if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody(JSON)
            val request = Request.Builder().url(url).method(method, payload).build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    throw ApiException(response.code, detail?.takeIf { it.isNotBlank() } ?: "Ошибка ${response.code}")
                }
                text
            }
        }

    private suspend fun obj(method: String, path: String, body: JSONObject? = null) = JSONObject(call(method, path, body))
    private suspend fun get(path: String) = obj("GET", path)
    private suspend fun post(path: String, body: JSONObject = JSONObject()) = obj("POST", path, body)
    private suspend fun put(path: String, body: JSONObject) = obj("PUT", path, body)
    private fun q(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    // --- служебное ---

    suspend fun health(): JSONObject = get("/api/health")
    suspend fun server(): ServerInfo = ServerInfo.from(get("/api/server"))
    suspend fun testServer(form: JSONObject): JSONObject = post("/api/server/test", form)
    suspend fun saveServer(form: JSONObject): JSONObject = put("/api/server", form)
    suspend fun syncLibrary(): JSONObject = post("/api/library/sync")
    suspend fun taste(): JSONObject = get("/api/taste")

    suspend fun settings(): JSONObject = get("/api/settings").getJSONObject("settings")
    suspend fun putSettings(patch: JSONObject): JSONObject = put("/api/settings", patch).getJSONObject("settings")
    suspend fun preferences(): JSONObject = get("/api/preferences")
    suspend fun putPreferences(patch: JSONObject): JSONObject = put("/api/preferences", patch)

    // --- волна ---

    suspend fun startWave(seedType: String? = null, seedId: String? = null): WaveState {
        val body = JSONObject().put("device", "android")
        if (seedType != null && seedId != null) body.put("seed", JSONObject().put("type", seedType).put("id", seedId))
        return WaveState.from(post("/api/wave/start", body))
    }

    suspend fun advance(session: String, trackId: String, outcome: String, position: Double, duration: Double) =
        WaveState.from(
            post(
                "/api/wave/$session/advance",
                JSONObject().put("track_id", trackId).put("outcome", outcome)
                    .put("position", position).put("duration", duration),
            ),
        )

    suspend fun likeInWave(session: String, trackId: String, liked: Boolean) =
        WaveState.from(post("/api/wave/$session/like", JSONObject().put("track_id", trackId).put("liked", liked)))

    suspend fun waveSettings(session: String, patch: JSONObject) = WaveState.from(post("/api/wave/$session/settings", patch))
    suspend fun shake(session: String) = WaveState.from(post("/api/wave/$session/shake"))

    suspend fun continueList(trackIds: List<String>, title: String) = WaveState.from(
        post("/api/continue", JSONObject().put("track_ids", JSONArray(trackIds)).put("title", title).put("device", "android")),
    )

    // --- сигналы вне волны ---

    suspend fun event(trackId: String, outcome: String, position: Double, duration: Double, contextType: String, contextId: String) {
        post(
            "/api/events",
            JSONObject().put("track_id", trackId).put("outcome", outcome).put("position", position)
                .put("duration", duration).put("context_type", contextType).put("context_id", contextId)
                .put("device", "android"),
        )
    }

    suspend fun like(type: String, id: String, liked: Boolean) {
        post("/api/like", JSONObject().put("type", type).put("id", id).put("liked", liked))
    }

    // --- библиотека ---

    suspend fun home(): List<Shelf> {
        val shelves = get("/api/home").optJSONArray("shelves") ?: JSONArray()
        return (0 until shelves.length()).map { Shelf.from(shelves.getJSONObject(it)) }
    }

    suspend fun libraryStats(): JSONObject = get("/api/library")
    suspend fun albums(sort: String) = Item.list(get("/api/library/albums?sort=$sort&limit=300").optJSONArray("items"))
    suspend fun artists(sort: String) = Item.list(get("/api/library/artists?sort=$sort&limit=300").optJSONArray("items"))
    suspend fun playlists() = Item.list(get("/api/library/playlists").optJSONArray("items"))
    suspend fun liked() = Track.list(get("/api/library/liked").optJSONArray("tracks"))
    suspend fun recent() = Track.list(get("/api/library/recent").optJSONArray("tracks"))
    suspend fun page(type: String, id: String) = Page.from(get("/api/$type/${q(id)}"))
    suspend fun search(query: String) = SearchResult.from(get("/api/search?q=${q(query)}"))

    suspend fun mix(type: String, id: String, size: Int) = Mix.from(
        post("/api/mix", JSONObject().put("seed", JSONObject().put("type", type).put("id", id)).put("size", size)),
    )

    suspend fun createPlaylist(name: String, trackIds: List<String>): JSONObject =
        post("/api/playlists", JSONObject().put("name", name).put("track_ids", JSONArray(trackIds)))

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
