package app.volna.player

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.volna.data.Item
import app.volna.data.Page
import app.volna.data.Prefs
import app.volna.data.SearchResult
import app.volna.data.Track
import app.volna.data.VolnaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/** Скачанный трек и зачем он скачан: track, liked, reserve, album:<id>, playlist:<id>. */
data class Saved(val track: Track, val reasons: Set<String>, val added: Long)

/** Скачанный альбом или плейлист: карточка и треки по порядку. */
data class SavedList(val item: Item, val trackIds: List<String>)

/**
 * Загрузки для прослушивания без сети, как в Spotify. Звук качает DownloadManager из Media3
 * в свой кэш (files/downloads), его же читает плеер: скачанный трек играет без сети, а с сетью
 * не тратит трафик. Обложки и тексты песен лежат рядом, в files/covers и files/lyrics.
 *
 * Трек держится скачанным, пока у него есть хоть одна причина: скачан вручную, вместе с альбомом
 * или плейлистом, лайкнут (если включено «Скачивать «Мне нравится»») или попал в запас.
 * Причин не осталось — файл удаляется.
 */
object Downloads {
    const val MANUAL = "track"
    const val LIKED = "liked"
    const val RESERVE = "reserve"

    private val scope = MainScope()
    lateinit var cache: SimpleCache
        private set
    lateinit var manager: DownloadManager
        private set
    private lateinit var context: Context
    private lateinit var api: VolnaApi
    private lateinit var prefs: Prefs
    private lateinit var catalog: File
    private lateinit var covers: File
    private lateinit var lyricsDir: File
    private val coverNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val coverBytes = AtomicLong(0)
    private val extras = Mutex() // обложки и тексты — по одному, чтобы не заваливать сервер
    private val writing = Mutex()

    private val _saved = MutableStateFlow<Map<String, Saved>>(emptyMap())
    val saved: StateFlow<Map<String, Saved>> = _saved
    private val _lists = MutableStateFlow<Map<String, SavedList>>(emptyMap())
    val lists: StateFlow<Map<String, SavedList>> = _lists

    /** Состояние загрузки каждого трека (Download.STATE_*): скачан, качается, ждёт Wi‑Fi. */
    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states

    /** Почему загрузки стоят: WAIT_WIFI, WAIT_NETWORK; null — качаются. */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked
    const val WAIT_WIFI = "ждут Wi‑Fi"
    const val WAIT_NETWORK = "ждут связи"
    private var online = true
    private var unmetered = true

    fun init(context: Context, api: VolnaApi, prefs: Prefs) {
        if (::manager.isInitialized) return
        this.context = context.applicationContext
        this.api = api
        this.prefs = prefs
        val files = context.filesDir
        catalog = File(files, "offline.json")
        covers = File(files, "covers").apply { mkdirs() }
        lyricsDir = File(files, "lyrics").apply { mkdirs() }
        covers.listFiles()?.filterNot { it.name.endsWith(".tmp") }?.forEach {
            coverNames += it.name
            coverBytes.addAndGet(it.length())
        }
        read()

        val database = StandaloneDatabaseProvider(context)
        cache = SimpleCache(File(files, "downloads"), NoOpCacheEvictor(), database)
        manager = DownloadManager(context, database, cache, OkHttpDataSource.Factory(api.client), Executors.newFixedThreadPool(2))
        manager.maxParallelDownloads = 2
        // когда качать, решаем сами (Offline сообщает о сети и Wi‑Fi): Media3 ждёт сеть с проверенным
        // выходом в интернет, а домашнему серверу в локальной сети он не нужен
        manager.requirements = Requirements(0)
        manager.pauseDownloads()
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = reloadStates()

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                _states.value = _states.value + (id to download.state)
                if (download.state == Download.STATE_COMPLETED) _saved.value[id]?.let { fetchExtras(it.track) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _states.value = _states.value - download.request.id
            }
        })
    }

    /** Ключ звука трека в кэше: один и тот же, с какого адреса и в каком качестве ни скачан. */
    fun key(id: String) = "track:$id"

    fun isDone(id: String) = _states.value[id] == Download.STATE_COMPLETED

    fun hasAny() = _states.value.values.any { it == Download.STATE_COMPLETED }

    fun isSaved(id: String) = id in _saved.value

    fun isSavedList(type: String, id: String) = "$type:$id" in _lists.value

    /** Сколько места занимают загрузки, в байтах. */
    fun bytes(): Long = cache.cacheSpace + coverBytes.get()

    /** Связь с сервисом и Wi‑Fi (сеть без оплаты за трафик) — от Offline. */
    fun network(online: Boolean, unmetered: Boolean) {
        this.online = online
        this.unmetered = unmetered
        applyNetwork()
    }

    fun setWifiOnly(on: Boolean) {
        prefs.wifiOnly = on
        applyNetwork()
    }

    private fun applyNetwork() {
        val blocked = when {
            !online -> WAIT_NETWORK
            prefs.wifiOnly && !unmetered -> WAIT_WIFI
            else -> null
        }
        _blocked.value = blocked
        if (blocked != null) return manager.pauseDownloads()
        manager.resumeDownloads()
        if (_states.value.values.any { it != Download.STATE_COMPLETED }) startService()
    }

    /** Подпись для «Скачать»: качается сразу или дождётся Wi‑Fi или связи. */
    fun queued(title: String): String = when (_blocked.value) {
        null -> "«$title» скачивается"
        WAIT_WIFI -> "«$title» скачается, когда будет Wi‑Fi"
        else -> "«$title» скачается, когда будет связь"
    }

    // --- что скачано и почему ---

    fun add(tracks: List<Track>, reason: String) {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        val map = _saved.value.toMutableMap()
        for (t in tracks) {
            val old = map[t.id]
            map[t.id] = old?.copy(track = t.copy(reason = ""), reasons = old.reasons + reason) ?: Saved(t.copy(reason = ""), setOf(reason), now)
        }
        _saved.value = map
        write()
        // новые и те, что не докачались, — в загрузчик
        enqueue(tracks.distinctBy { it.id }.filter { _states.value[it.id] == null || _states.value[it.id] == Download.STATE_FAILED })
    }

    /** Убрать причину (или все, если reason == null); трек без причин удаляется с телефона. */
    fun drop(ids: Collection<String>, reason: String? = null) {
        val map = _saved.value.toMutableMap()
        val gone = mutableListOf<String>()
        var changed = false
        for (id in ids) {
            val old = map[id] ?: continue
            val left = if (reason == null) emptySet() else old.reasons - reason
            if (left == old.reasons) continue
            changed = true
            if (left.isEmpty()) {
                map.remove(id)
                gone += id
            } else {
                map[id] = old.copy(reasons = left)
            }
        }
        if (!changed) return
        _saved.value = map
        write()
        for (id in gone) {
            manager.removeDownload(id)
            File(lyricsDir, "$id.json").delete()
        }
    }

    /** С этой причиной держать скачанными ровно эти треки: лишние теряют её, новые получают. */
    fun setReason(reason: String, tracks: List<Track>) {
        val keep = tracks.mapTo(HashSet()) { it.id }
        drop(_saved.value.values.filter { reason in it.reasons && it.track.id !in keep }.map { it.track.id }, reason)
        add(tracks, reason)
    }

    fun saveList(item: Item, tracks: List<Track>) {
        val key = "${item.type}:${item.id}"
        _lists.value = _lists.value + (key to SavedList(item, tracks.map { it.id }))
        setReason(key, tracks)
    }

    fun removeList(type: String, id: String) {
        val key = "$type:$id"
        _lists.value = _lists.value - key
        setReason(key, emptyList())
    }

    /** Лайк поменялся: обновить отметку у скачанного и, если качаем «Мне нравится», — сам трек. */
    fun liked(tracks: List<Track>, liked: Boolean) {
        val map = _saved.value.toMutableMap()
        var changed = false
        for (t in tracks) map[t.id]?.let {
            map[t.id] = it.copy(track = it.track.copy(liked = liked))
            changed = true
        }
        if (changed) {
            _saved.value = map
            write()
        }
        if (!prefs.offlineLiked) return
        if (liked) add(tracks.map { it.copy(liked = true) }, LIKED) else drop(tracks.map { it.id }, LIKED)
    }

    /** Удалить все загрузки (при выходе из аккаунта и по кнопке в настройках). */
    fun clear() {
        _saved.value = emptyMap()
        _lists.value = emptyMap()
        write()
        manager.removeAllDownloads()
        lyricsDir.listFiles()?.forEach { it.delete() }
        covers.listFiles()?.forEach { it.delete() }
        coverNames.clear()
        coverBytes.set(0)
    }

    // --- без сети: то, что приложение показывает вместо ответов сервиса ---

    private fun done(): List<Saved> = _saved.value.values.filter { isDone(it.track.id) }

    fun likedTracks(): List<Track> = done().filter { it.track.liked || LIKED in it.reasons }.sortedByDescending { it.added }.map { it.track }

    fun page(type: String, id: String): Page? {
        val list = _lists.value["$type:$id"]
        val tracks = when {
            list != null -> list.trackIds.mapNotNull { _saved.value[it]?.track }
            type == "album" -> done().map { it.track }.filter { it.albumId == id }.sortedBy { it.trackNo }
            type == "artist" -> done().map { it.track }.filter { it.artistId == id }
            else -> emptyList()
        }
        if (tracks.isEmpty()) return null
        val first = tracks.first()
        val item = list?.item ?: if (type == "artist") artistItem(first) else albumItem(first)
        val albums = if (type == "artist") tracks.distinctBy { it.albumId }.map(::albumItem) else emptyList()
        return Page(item, tracks, emptyList(), albums, tracks.sumOf { it.duration }, 0)
    }

    fun albums(): List<Item> = done().sortedByDescending { it.added }.map { it.track }.distinctBy { it.albumId }.map(::albumItem)

    fun search(query: String): SearchResult {
        val q = plain(query)
        if (q.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        val tracks = done().map { it.track }
        val found = tracks.filter { t -> listOf(t.title, t.artist, t.album).any { plain(it).contains(q) } }
        return SearchResult(
            tracks = found.take(50),
            albums = found.filter { plain(it.album).contains(q) || plain(it.artist).contains(q) }.distinctBy { it.albumId }.map(::albumItem).take(20),
            artists = tracks.filter { plain(it.artist).contains(q) }.distinctBy { it.artistId }.map(::artistItem).take(20),
            playlists = _lists.value.values.map { it.item }.filter { it.type == "playlist" && plain(it.title).contains(q) },
        )
    }

    /**
     * Волна без сети: всё скачанное вперемешку. Лайкнутое попадается вдвое чаще, запас — в полтора
     * раза, только что игравшее уходит в конец, и один артист не идёт два раза подряд.
     */
    fun wave(recent: Set<String>): List<Track> {
        val random = java.util.Random()
        // взвешенное перемешивание: ключ u^(1/вес) считаем один раз на трек, потом сортируем по нему
        val shuffled = done().map { s ->
            val weight = when {
                s.track.liked || LIKED in s.reasons -> 2.0
                RESERVE in s.reasons -> 1.5
                else -> 1.0
            } * if (s.track.id in recent) 0.05 else 1.0
            s.track to random.nextDouble().pow(1.0 / weight)
        }.sortedByDescending { it.second }.map { it.first }
        val rest = shuffled.toMutableList()
        val out = ArrayList<Track>(rest.size)
        while (rest.isNotEmpty()) {
            val other = rest.take(10).indexOfFirst { it.artist != out.lastOrNull()?.artist }
            out += rest.removeAt(other.coerceAtLeast(0))
        }
        return out
    }

    /** Обложка, сохранённая на телефоне, если есть. */
    fun cover(path: String): File? {
        if (path.isEmpty()) return null
        val name = coverName(path)
        return if (name in coverNames) File(covers, name) else null
    }

    fun lyrics(id: String): JSONObject? =
        File(lyricsDir, "$id.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun keepLyrics(id: String, json: JSONObject) {
        if (!isDone(id)) return
        scope.launch(Dispatchers.IO) { runCatching { File(lyricsDir, "$id.json").writeText(json.toString()) } }
    }

    // --- внутреннее ---

    private fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val quality = prefs.downloadQuality
        for (t in tracks) {
            manager.addDownload(DownloadRequest.Builder(t.id, Uri.parse(api.downloadUrl(t, quality))).setCustomCacheKey(key(t.id)).build())
        }
        if (_blocked.value == null) startService()
    }

    // сервис держит загрузку в фоне с уведомлением; из фона Android его запускать не даёт — тогда
    // загрузка идёт, пока живо приложение, и продолжится при следующем запуске
    private fun startService() {
        runCatching { DownloadService.start(context, VolnaDownloadService::class.java) }
    }

    private fun reloadStates() {
        val states = HashMap<String, Int>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) states[cursor.download.request.id] = cursor.download.state
        }
        _states.value = states
        // в каталоге есть, а у загрузчика нет (не докачалось или кэш стёрли) — поставить снова
        enqueue(_saved.value.values.map { it.track }.filter { states[it.id] == null || states[it.id] == Download.STATE_FAILED })
    }

    private fun fetchExtras(track: Track) {
        scope.launch(Dispatchers.IO) {
            extras.withLock {
                saveCover(track.cover)
                val file = File(lyricsDir, "${track.id}.json")
                if (!file.exists()) runCatching { api.lyricsJson(track.id) }.onSuccess { file.writeText(it.toString()) }
            }
        }
    }

    private fun saveCover(path: String) {
        if (path.isEmpty()) return
        val name = coverName(path)
        if (name in coverNames) return
        runCatching {
            api.client.newCall(Request.Builder().url(api.url(path)).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val tmp = File(covers, "$name.tmp")
                tmp.outputStream().use { response.body.byteStream().copyTo(it) }
                if (tmp.renameTo(File(covers, name))) {
                    coverNames += name
                    coverBytes.addAndGet(File(covers, name).length())
                }
            }
        }
    }

    private fun coverName(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun albumItem(t: Track) = Item("album", t.albumId, t.album, t.artist, t.cover, false, 0, t.artistId, 0)

    private fun artistItem(t: Track) = Item("artist", t.artistId, t.artist, "", t.cover, false, 0, t.artistId, 0)

    /** Как skeleton() на сервере: без регистра, знаков и с транслитом — «lun» находит «Лунный», «Zemfira» — «Земфиру». */
    private fun plain(text: String): String {
        val latin = text.lowercase().map { CYRILLIC[it] ?: it.toString() }.joinToString("")
        return java.text.Normalizer.normalize(latin, java.text.Normalizer.Form.NFKD).filter { it.isLetterOrDigit() }
            .replace("kh", "h").replace("x", "ks").replace("y", "i").replace("j", "i")
    }

    private val CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList().zip(
        listOf(
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
            "f", "h", "ts", "ch", "sh", "sch", "", "i", "", "e", "yu", "ya",
        ),
    ).toMap()

    private fun read() {
        val json = runCatching { JSONObject(catalog.readText()) }.getOrNull() ?: return
        val tracks = json.optJSONObject("tracks") ?: JSONObject()
        _saved.value = tracks.keys().asSequence().mapNotNull { id ->
            val o = tracks.optJSONObject(id) ?: return@mapNotNull null
            val reasons = o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
            runCatching { id to Saved(Track.from(o.getJSONObject("track")), reasons, o.optLong("added")) }.getOrNull()
        }.toMap()
        val lists = json.optJSONObject("lists") ?: JSONObject()
        _lists.value = lists.keys().asSequence().mapNotNull { key ->
            val o = lists.optJSONObject(key) ?: return@mapNotNull null
            val ids = o.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            runCatching { key to SavedList(Item.from(o.getJSONObject("item")), ids) }.getOrNull()
        }.toMap()
    }

    private fun write() {
        val tracks = JSONObject()
        _saved.value.forEach { (id, s) ->
            tracks.put(id, JSONObject().put("track", s.track.json()).put("reasons", JSONArray(s.reasons.toList())).put("added", s.added))
        }
        val lists = JSONObject()
        _lists.value.forEach { (key, l) -> lists.put(key, JSONObject().put("item", l.item.json()).put("tracks", JSONArray(l.trackIds))) }
        val text = JSONObject().put("tracks", tracks).put("lists", lists).toString()
        scope.launch(Dispatchers.IO) {
            writing.withLock {
                val tmp = File(catalog.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(catalog)
            }
        }
    }
}
