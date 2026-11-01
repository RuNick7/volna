package app.volna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import app.volna.data.Track
import app.volna.player.Downloads
import app.volna.player.PlayContext
import app.volna.player.Saved
import app.volna.player.VolnaPlayer

private val FILTERS = listOf("all" to "Все", "liked" to "Мне нравится", "lists" to "Альбомы и плейлисты", "manual" to "Треки", "reserve" to "Запас")

/**
 * «Коллекция → Скачанное»: всё, что играет без сети. Запас и «Мне нравится» скачиваются сами
 * (настраивается в «Настройки → Загрузки и офлайн»), остальное — кнопкой «Скачать».
 */
@Composable
fun DownloadedScreen(nav: Navigator, onMore: (Track) -> Unit) {
    val saved by Downloads.saved.collectAsState()
    val states by Downloads.states.collectAsState()
    val lists by Downloads.lists.collectAsState()
    val blocked by Downloads.blocked.collectAsState() // почему стоят: ждут Wi‑Fi или связи
    val playing by VolnaPlayer.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val all = saved.values.sortedByDescending { it.added }
    val tracks = all.filter { it.matches(filter) && matches(query, it.track.title, it.track.artist, it.track.album) }.map { it.track }
    val done = all.count { states[it.track.id] == Download.STATE_COMPLETED }
    val context = PlayContext("library", "downloads", "Скачанное")

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Скачанное") { nav.back() } }
        if (all.isEmpty()) {
            item {
                Empty(
                    "Здесь будет музыка для прослушивания без сети. Скачивайте треки через «⋯», альбомы и плейлисты — " +
                        "кнопкой «Скачать». «Мне нравится» и запас из того, что вы часто слушаете, скачиваются сами: " +
                        "«Настройки → Загрузки и офлайн».",
                )
            }
            item { Box(Modifier.padding(top = 8.dp)) { Pill("Настроить", VIcons.Settings) { nav.go(Screen.SettingsPage("offline")) } } }
            return@LazyColumn
        }
        item {
            val waiting = all.size - done
            Hint(
                buildString {
                    append("$done ${plural(done, "трек", "трека", "треков")} · ${formatBytes(Downloads.bytes())}")
                    if (waiting > 0) append(" · ещё $waiting ${blocked ?: "качается"}")
                },
                Modifier.padding(bottom = 12.dp),
            )
        }
        item { FilterField(query, "Найти в скачанном") { query = it } }
        item { Chips(FILTERS, filter) { filter = it } }
        if (tracks.isNotEmpty()) item { PlayActions(tracks, context) }
        if (filter in setOf("all", "lists") && query.isBlank() && lists.isNotEmpty()) {
            item { SectionTitle("Альбомы и плейлисты") }
            item { ItemGrid(lists.values.map { it.item }, columns = 3) { nav.go(Screen.Entity(it.type, it.id)) } }
            item { SectionTitle("Треки") }
        }
        if (tracks.isEmpty()) item { Empty("Ничего не нашлось.") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
            TrackRow(track, playing.track?.id == track.id, onMore = onMore) { VolnaPlayer.playList(tracks, i, context) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun Saved.matches(filter: String): Boolean = when (filter) {
    "liked" -> track.liked || Downloads.LIKED in reasons
    "lists" -> reasons.any { it.startsWith("album:") || it.startsWith("playlist:") }
    "manual" -> Downloads.MANUAL in reasons
    "reserve" -> Downloads.RESERVE in reasons
    else -> true
}
