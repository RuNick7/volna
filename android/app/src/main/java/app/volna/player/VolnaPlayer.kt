package app.volna.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.volna.data.ApiException
import app.volna.data.Track
import app.volna.data.VolnaApi
import app.volna.data.WaveState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/** Откуда играет список: альбом, плейлист, поиск… (для событий и подписи «почему этот трек»). */
data class PlayContext(val type: String, val id: String = "", val title: String = "")

data class PlayerState(
    val track: Track? = null,
    val nextUp: Track? = null,
    val mode: String? = null, // wave | list
    val kind: String = "wave", // wave | steer
    val title: String = "", // источник смещения волны: трек, альбом, плейлист
    val reason: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val settings: JSONObject? = null,
    val message: String? = null,
)

/**
 * Плеер приложения. Два режима, как в веб-версии:
 * * wave — очередь держит сервис Volna: на каждое окончание трека сообщаем, чем всё кончилось
 *   (дослушал, пропустил на N-й секунде, «не нравится»), и получаем следующий;
 * * list — альбом, плейлист, поиск. Прослушивания всё равно уходят в /api/events, а когда список
 *   закончился, сервис продолжает его волной, смещённой ко всему списку.
 *
 * В ExoPlayer всегда лежат текущий трек и следующий, чтобы переход был без паузы и работал
 * в фоне с выключенным экраном. «Назад» в волне ведёт по уже сыгранным трекам (history), а
 * «дальше» потом возвращает обратно (forward), не трогая сервер.
 */
object VolnaPlayer {
    private val scope = MainScope()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    lateinit var api: VolnaApi
        private set
    lateinit var exo: ExoPlayer
        private set

    private var mode: String? = null
    private var session: String? = null
    private var kind = "wave"
    private var title = ""
    private var settings: JSONObject? = null
    private var upcoming: List<Track> = emptyList()
    private var list: List<Track> = emptyList()
    private var index = 0
    private var context: PlayContext? = null
    private val history = ArrayDeque<Track>()
    private val forward = ArrayDeque<Track>()
    private var current: Track? = null
    private var busy = false
    private var loading = false
    private var message: String? = null

    private val replaying get() = mode == "wave" && forward.isNotEmpty()

    fun init(context: Context, api: VolnaApi) {
        if (::exo.isInitialized) return
        this.api = api
        // звук проходит через «тройник»: ExoPlayer играет его как обычно, а AudioLevel меряет громкость для фона
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(AudioLevel))).build()
        }
        exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.client))) // звук тоже с токеном
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true, // аудиофокус: звонок и навигатор ставят на паузу
            )
            .setHandleAudioBecomingNoisy(true) // выдернули наушники — пауза
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) next("complete")
                publish()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) onAutoAdvance(mediaItem)
            }

            override fun onPlayerError(error: PlaybackException) {
                say("Трек не воспроизводится, беру следующий")
                next("error")
            }
        })
        scope.launch {
            while (true) {
                if (exo.isPlaying) publish()
                delay(500)
            }
        }
    }

    // --- запуск ---

    /** Вышли из аккаунта: останавливаем музыку и забываем очередь. */
    fun reset() {
        exo.stop()
        exo.clearMediaItems()
        mode = null
        session = null
        upcoming = emptyList()
        list = emptyList()
        index = 0
        context = null
        history.clear()
        forward.clear()
        current = null
        settings = null
        _state.value = PlayerState()
    }

    fun startWave(seedType: String? = null, seedId: String? = null) {
        history.clear()
        forward.clear()
        loading = true
        publish()
        scope.launch {
            try {
                applyWave(api.startWave(seedType, seedId))
            } catch (e: IOException) {
                say(problem(e))
            } finally {
                loading = false
                publish()
            }
        }
    }

    fun playList(tracks: List<Track>, start: Int, context: PlayContext) {
        if (tracks.isEmpty()) return
        mode = "list"
        session = null
        history.clear()
        forward.clear()
        list = tracks
        index = start.coerceIn(0, tracks.lastIndex)
        this.context = context
        load(list[index])
    }

    fun shuffle(tracks: List<Track>, context: PlayContext) = playList(tracks.shuffled(), 0, context)

    // --- переключение ---

    /** Трек закончился, пропущен, «не нравится» или не играет (error — без обучения). */
    fun next(outcome: String = "skip") {
        val track = current ?: return
        if (busy) return
        busy = true
        val position = exo.currentPosition / 1000.0
        val duration = durationSeconds(track)
        scope.launch {
            try {
                when {
                    replaying -> {
                        report(track, outcome, position, duration, PlayContext("other"))
                        remember(track)
                        load(forward.removeLast())
                    }
                    mode == "wave" -> {
                        val data = api.advance(session ?: return@launch, track.id, outcome, position, duration)
                        remember(track)
                        applyWave(data)
                    }
                    else -> {
                        report(track, outcome, position, duration, context)
                        if (index + 1 < list.size) {
                            index += 1
                            load(list[index])
                        } else {
                            continueAfterList()
                        }
                    }
                }
            } catch (e: ApiException) {
                if (e.code == 404 && mode == "wave") startWave() else say(problem(e))
            } catch (e: IOException) {
                say(problem(e))
            } finally {
                busy = false
            }
        }
    }

    /** «Назад»: в начало трека, если он играет дольше 3 секунд, иначе — к предыдущему. */
    /** «Назад»: после первых трёх секунд кнопка сначала перематывает трек в начало, свайп — нет. */
    fun previous(restartFirst: Boolean = true) {
        val track = current ?: return
        if (restartFirst && exo.currentPosition > 3000) {
            exo.seekTo(0)
            return
        }
        if (mode == "list" && !replaying) {
            if (index == 0) {
                exo.seekTo(0)
                return
            }
            index -= 1
            load(list[index])
            return
        }
        val back = history.removeLastOrNull()
        if (back == null) {
            exo.seekTo(0)
            return
        }
        forward.addLast(track)
        load(back)
    }

    fun toggle() {
        when {
            current == null -> startWave()
            exo.isPlaying -> exo.pause()
            else -> exo.play()
        }
    }

    fun seek(fraction: Float) {
        val duration = exo.duration
        if (duration > 0) exo.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    // --- реакции и настройки ---

    fun like() {
        val track = current ?: return
        val liked = !track.liked
        current = track.copy(liked = liked)
        publish()
        scope.launch {
            try {
                val session = session
                if (mode == "wave" && !replaying && session != null) {
                    applyWave(api.likeInWave(session, track.id, liked))
                } else {
                    api.like("track", track.id, liked)
                }
            } catch (e: IOException) {
                current = current?.copy(liked = !liked)
                say(problem(e))
            }
        }
    }

    fun shake() {
        val session = session
        if (mode != "wave" || session == null) return startWave()
        scope.launch {
            try {
                applyWave(api.shake(session))
                say("Волна встряхнута: дальше будет иначе")
            } catch (e: IOException) {
                say(problem(e))
            }
        }
    }

    /** Поменять настройки волны: на ходу в текущей сессии или просто сохранить. */
    fun changeSettings(patch: JSONObject) {
        scope.launch {
            try {
                val session = session
                if (mode == "wave" && session != null) {
                    applyWave(api.waveSettings(session, patch))
                } else {
                    settings = api.putSettings(patch)
                    publish()
                }
            } catch (e: ApiException) {
                if (e.code == 404) {
                    settings = api.putSettings(patch)
                    publish()
                } else {
                    say(problem(e))
                }
            } catch (e: IOException) {
                say(problem(e))
            }
        }
    }

    fun loadSettings() {
        scope.launch {
            runCatching { api.settings() }.onSuccess {
                settings = it
                publish()
            }
        }
    }

    fun say(text: String) {
        message = text
        publish()
    }

    fun consumeMessage() {
        message = null
        publish()
    }

    // --- внутреннее ---

    private suspend fun continueAfterList() {
        val keepGoing = runCatching { api.preferences().optBoolean("continue_after_end", true) }.getOrDefault(true)
        if (!keepGoing) {
            exo.pause()
            exo.seekTo(0)
            return
        }
        val title = context?.title.orEmpty()
        loading = true
        publish()
        try {
            val data = api.continueList(list.map { it.id }, title)
            say(if (title.isNotEmpty()) "Список закончился — дальше похожее на «$title»" else "Список закончился — дальше похожее")
            applyWave(data)
        } catch (e: IOException) {
            say("Список закончился. ${problem(e)}")
            exo.pause()
        } finally {
            loading = false
            publish()
        }
    }

    private fun applyWave(data: WaveState) {
        mode = "wave"
        session = data.session
        kind = data.kind
        title = data.title
        upcoming = data.upcoming
        data.settings?.let { settings = it }
        if (replaying) { // переслушиваем прошлое: очередь сервера обновилась, но играет сейчас не она
            refreshTail()
            publish()
            return
        }
        val track = data.track
        if (track != null && track.id == current?.id) { // тот же трек: лайк, настройки, «встряхнуть»
            current = track
            refreshTail()
            publish()
        } else {
            load(track)
        }
    }

    /** ExoPlayer сам перешёл на заранее подгруженный следующий трек. */
    private fun onAutoAdvance(item: MediaItem?) {
        val finished = current ?: return
        val duration = durationSeconds(finished)
        when {
            replaying -> {
                report(finished, "complete", duration, duration, PlayContext("other"))
                remember(finished)
                current = forward.removeLast()
                refreshTail()
                publish()
            }
            mode == "wave" -> {
                remember(finished)
                val session = session ?: return
                scope.launch {
                    try {
                        val data = api.advance(session, finished.id, "complete", duration, duration)
                        if (data.track?.id == item?.mediaId) { // сервер ведёт ту же очередь — уже играет нужный трек
                            this@VolnaPlayer.session = data.session
                            upcoming = data.upcoming
                            current = data.track
                            refreshTail()
                            publish()
                        } else {
                            applyWave(data)
                        }
                    } catch (e: IOException) {
                        say(problem(e))
                    }
                }
            }
            else -> {
                report(finished, "complete", duration, duration, context)
                index += 1
                current = list.getOrNull(index)
                refreshTail()
                publish()
            }
        }
    }

    private fun load(track: Track?) {
        current = track
        if (track == null) {
            exo.clearMediaItems()
            publish()
            return
        }
        exo.setMediaItems(listOfNotNull(track, nextUp()).map(::mediaItem), 0, 0L)
        exo.prepare()
        exo.play()
        publish()
    }

    /** Заменить заранее подгруженный следующий трек, если он поменялся. */
    private fun refreshTail() {
        val next = nextUp()
        val currentIndex = exo.currentMediaItemIndex
        val queued = if (exo.mediaItemCount > currentIndex + 1) exo.getMediaItemAt(currentIndex + 1).mediaId else null
        if (queued == next?.id) return
        if (exo.mediaItemCount > currentIndex + 1) exo.removeMediaItems(currentIndex + 1, exo.mediaItemCount)
        if (next != null) exo.addMediaItem(mediaItem(next))
    }

    private fun nextUp(): Track? = when {
        replaying -> forward.lastOrNull()
        mode == "wave" -> upcoming.firstOrNull()
        else -> list.getOrNull(index + 1)
    }

    private fun remember(track: Track) {
        history.addLast(track)
        if (history.size > 50) history.removeFirst()
    }

    private fun report(track: Track, outcome: String, position: Double, duration: Double, context: PlayContext?) {
        if (outcome == "error") return
        scope.launch {
            runCatching { api.event(track.id, outcome, position, duration, context?.type ?: "other", context?.id.orEmpty()) }
        }
    }

    private fun durationSeconds(track: Track): Double {
        val duration = exo.duration
        return if (duration > 0 && exo.currentMediaItem?.mediaId == track.id) duration / 1000.0 else track.duration
    }

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(api.url(track.stream))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(api.url(it)) })
                .build(),
        )
        .build()

    private fun reason(): String {
        val context = context
        if (mode == "list" && !replaying && context != null && context.title.isNotEmpty()) {
            val label = mapOf("album" to "Альбом", "playlist" to "Плейлист", "artist" to "Артист", "search" to "Поиск", "library" to "Коллекция")
            return "${label[context.type] ?: "Сейчас"}: ${context.title}"
        }
        return current?.reason.orEmpty()
    }

    private fun publish() {
        if (!::exo.isInitialized) return
        _state.value = PlayerState(
            track = current,
            nextUp = nextUp(),
            mode = mode,
            kind = kind,
            title = title,
            reason = reason(),
            playing = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING),
            loading = loading,
            positionMs = exo.currentPosition,
            durationMs = exo.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0.0) * 1000).toLong(),
            settings = settings,
            message = message,
        )
    }

    private fun problem(e: IOException): String = when (e) {
        is ApiException -> e.message ?: "Ошибка сервиса"
        else -> "Сервис Volna недоступен: проверьте адрес в настройках"
    }
}
