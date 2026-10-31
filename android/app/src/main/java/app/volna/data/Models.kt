package app.volna.data

import org.json.JSONArray
import org.json.JSONObject

/** Строка из JSON: null и отсутствие поля — пустая строка (а не "null", как у optString). */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { transform(getJSONObject(it)) }
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val duration: Double,
    val liked: Boolean,
    val stream: String,
    val cover: String,
    val reason: String,
    val trackNo: Int,
) {
    companion object {
        fun from(o: JSONObject) = Track(
            id = o.getString("id"),
            title = o.str("title"),
            artist = o.str("artist"),
            album = o.str("album"),
            albumId = o.str("album_id"),
            artistId = o.str("artist_id"),
            duration = o.optDouble("duration", 0.0),
            liked = o.optBoolean("liked"),
            stream = o.str("stream"),
            cover = o.str("cover"),
            reason = o.str("reason"),
            trackNo = o.optInt("track_no"),
        )

        fun list(a: JSONArray?) = a.mapObjects(::from)
    }
}

/** Карточка альбома, артиста или плейлиста. */
data class Item(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val cover: String,
    val liked: Boolean,
    val count: Int,
    val artistId: String,
    val year: Int,
) {
    companion object {
        fun from(o: JSONObject) = Item(
            type = o.str("type"),
            id = o.getString("id"),
            title = o.str("title"),
            subtitle = o.str("subtitle"),
            cover = o.str("cover"),
            liked = o.optBoolean("liked"),
            count = o.optInt("count"),
            artistId = o.str("artist_id"),
            year = o.optInt("year"),
        )

        fun list(a: JSONArray?) = a.mapObjects(::from)
    }
}

/** Полка главной: карточки или треки (как «Давно не слушали»). */
data class Shelf(val id: String, val title: String, val items: List<Item>, val tracks: List<Track>) {
    companion object {
        fun from(o: JSONObject): Shelf {
            val raw = o.optJSONArray("items")
            val isTracks = raw != null && raw.length() > 0 && raw.getJSONObject(0).has("stream")
            return Shelf(
                id = o.str("id"),
                title = o.str("title"),
                items = if (isTracks) emptyList() else Item.list(raw),
                tracks = if (isTracks) Track.list(raw) else emptyList(),
            )
        }
    }
}

/** Ответ волны: что играет сейчас и что дальше. */
data class WaveState(
    val session: String,
    val kind: String,
    val title: String,
    val settings: JSONObject?,
    val track: Track?,
    val upcoming: List<Track>,
) {
    companion object {
        fun from(o: JSONObject) = WaveState(
            session = o.getString("session"),
            kind = o.str("kind"),
            title = o.str("title"),
            settings = o.optJSONObject("settings"),
            track = o.optJSONObject("track")?.let(Track::from),
            upcoming = Track.list(o.optJSONArray("upcoming")),
        )
    }
}

/** Страница альбома, артиста, плейлиста или подборки по треку. */
data class Page(
    val item: Item,
    val tracks: List<Track>,
    val top: List<Track>,
    val albums: List<Item>,
    val duration: Double,
    val plays: Int,
) {
    companion object {
        fun from(o: JSONObject) = Page(
            item = Item.from(o),
            tracks = Track.list(o.optJSONArray("tracks")),
            top = Track.list(o.optJSONArray("top")),
            albums = Item.list(o.optJSONArray("albums")),
            duration = o.optDouble("duration", 0.0),
            plays = o.optInt("plays"),
        )
    }
}

data class SearchResult(val tracks: List<Track>, val albums: List<Item>, val artists: List<Item>, val playlists: List<Item>) {
    companion object {
        fun from(o: JSONObject) = SearchResult(
            tracks = Track.list(o.optJSONArray("tracks")),
            albums = Item.list(o.optJSONArray("albums")),
            artists = Item.list(o.optJSONArray("artists")),
            playlists = Item.list(o.optJSONArray("playlists")),
        )
    }
}

data class Mix(val title: String, val tracks: List<Track>) {
    companion object {
        fun from(o: JSONObject) = Mix(o.str("title"), Track.list(o.optJSONArray("tracks")))
    }
}

/** Состояние подключения сервиса Volna к Navidrome и AudioMuse. */
data class ServerInfo(
    val mode: String,
    val navidromeUrl: String,
    val navidromeUser: String,
    val audiomuseUrl: String,
    val audiomuseUser: String,
    val hasPassword: Boolean,
    val hasToken: Boolean,
    val hasAudiomusePassword: Boolean,
    val syncing: Boolean,
    val tracks: Int,
    val albums: Int,
    val artists: Int,
    val playlists: Int,
    val warmDone: Int,
    val warmTotal: Int,
    val warmRunning: Boolean,
) {
    companion object {
        fun from(o: JSONObject): ServerInfo {
            val server = o.optJSONObject("server") ?: JSONObject()
            val library = o.optJSONObject("library") ?: JSONObject()
            val warm = o.optJSONObject("warm") ?: JSONObject()
            return ServerInfo(
                mode = o.str("mode"),
                navidromeUrl = server.str("navidrome_url"),
                navidromeUser = server.str("navidrome_user"),
                audiomuseUrl = server.str("audiomuse_url"),
                audiomuseUser = server.str("audiomuse_user"),
                hasPassword = server.optBoolean("has_password"),
                hasToken = server.optBoolean("has_token"),
                hasAudiomusePassword = server.optBoolean("has_audiomuse_password"),
                syncing = o.optBoolean("syncing"),
                tracks = library.optInt("tracks"),
                albums = library.optInt("albums"),
                artists = library.optInt("artists"),
                playlists = library.optInt("playlists"),
                warmDone = warm.optInt("done"),
                warmTotal = warm.optInt("total"),
                warmRunning = warm.optBoolean("running"),
            )
        }
    }
}

/** Строка текста песни; time — секунда начала, если текст синхронизирован. */
data class LyricLine(val time: Double?, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    companion object {
        fun from(o: JSONObject): Lyrics {
            val lines = o.optJSONArray("lines").mapObjects { line ->
                LyricLine(if (line.isNull("time")) null else line.optDouble("time"), line.str("text"))
            }
            return Lyrics(o.optBoolean("synced") && lines.all { it.time != null }, lines)
        }
    }
}
