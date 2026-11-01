package app.volna.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.volna.data.Item
import app.volna.data.Prefs
import app.volna.data.SearchResult
import app.volna.data.Track
import org.json.JSONArray
import org.json.JSONObject

/** Запись истории поиска: запрос, открытый из поиска артист, альбом, плейлист или сыгранный трек. */
data class SearchEntry(val query: String = "", val item: Item? = null, val track: Track? = null) {
    val key: String
        get() = when {
            track != null -> "track:${track.id}"
            item != null -> "${item.type}:${item.id}"
            else -> "query:${query.trim().lowercase()}"
        }

    fun json(): JSONObject = when {
        track != null -> JSONObject().put("track", track.json())
        item != null -> JSONObject().put("item", item.json())
        else -> JSONObject().put("query", query)
    }

    companion object {
        fun from(o: JSONObject): SearchEntry = when {
            o.has("track") -> SearchEntry(track = Track.from(o.getJSONObject("track")))
            o.has("item") -> SearchEntry(item = Item.from(o.getJSONObject("item")))
            else -> SearchEntry(query = o.optString("query"))
        }
    }
}

/**
 * История поиска, как в Spotify: что вы открывали из результатов (артисты, альбомы, плейлисты,
 * треки) и запросы, по которым нажали «Найти». Новое — сверху, повтор поднимается наверх.
 * Хранится на телефоне, при выходе из аккаунта стирается.
 */
object SearchHistory {
    private const val LIMIT = 25
    private var prefs: Prefs? = null
    val entries = mutableStateListOf<SearchEntry>()

    fun init(prefs: Prefs) {
        if (this.prefs != null) return
        this.prefs = prefs
        val saved = runCatching { JSONArray(prefs.searchHistory) }.getOrNull() ?: return
        for (i in 0 until saved.length()) saved.optJSONObject(i)?.let { o -> runCatching { SearchEntry.from(o) }.getOrNull()?.let(entries::add) }
    }

    fun add(item: Item) = put(SearchEntry(item = item))

    fun add(track: Track) = put(SearchEntry(track = track.copy(reason = "")))

    fun addQuery(query: String) {
        if (query.isNotBlank()) put(SearchEntry(query = query.trim()))
    }

    fun remove(entry: SearchEntry) {
        entries.removeAll { it.key == entry.key }
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    private fun put(entry: SearchEntry) {
        entries.removeAll { it.key == entry.key }
        entries.add(0, entry)
        while (entries.size > LIMIT) entries.removeAt(entries.lastIndex)
        save()
    }

    private fun save() {
        prefs?.searchHistory = JSONArray(entries.map { it.json() }).toString()
    }
}

/**
 * Запрос и найденное живут дольше экрана поиска: вернулись из альбома или с другой вкладки —
 * результаты на месте, искать заново не нужно.
 */
object SearchMemory {
    var query by mutableStateOf("")
    var result: SearchResult? = null
    var resultFor = "" // для какого запроса result

    fun clear() {
        query = ""
        result = null
        resultFor = ""
    }
}
