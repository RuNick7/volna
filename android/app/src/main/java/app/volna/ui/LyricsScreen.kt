package app.volna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.volna.data.Lyrics
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay

/** Текст песни на весь экран: открыт или нет (поверх всего приложения, как в Spotify). */
object LyricsFull {
    var show by mutableStateOf(false)
}

/** Какая строка поётся сейчас; -1 — текст без времени или ещё до первой строки. */
fun currentLine(lyrics: Lyrics, positionMs: Long): Int =
    if (lyrics.synced) lyrics.lines.indexOfLast { (it.time ?: 0.0) <= positionMs / 1000.0 + 0.25 } else -1

/**
 * Текст на весь экран, как в Spotify: крупные строки, текущая белая, спетые гаснут. Список сам
 * держит текущую строку в верхней трети; если листать пальцем, он уступает и через несколько
 * секунд возвращается к песне. Нажатие на строку перематывает к ней.
 */
@Composable
fun LyricsScreen(onClose: () -> Unit) {
    val state by VolnaPlayer.state.collectAsState()
    val track = state.track
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Topo(Modifier.fillMaxSize(), mode = UiPrefs.background, dim = true, playing = state.playing)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButtonV(VIcons.ChevronDown, "Свернуть", 44.dp, tint = Palette.Fg, onClick = onClose)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.padding(22.dp))
            }
            Box(Modifier.weight(1f)) {
                if (track == null) {
                    Empty("Сейчас ничего не играет")
                } else {
                    val lyrics = load("lyrics", track.id) { app.volna.player.Offline.lyrics(track.id) }.value
                    when {
                        lyrics is Load.Loading -> Loading()
                        lyrics is Load.Done && lyrics.value.lines.isNotEmpty() -> FullLyrics(lyrics.value, state.positionMs, state.durationMs)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("У этого трека нет текста", style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
                        }
                    }
                }
            }
            if (track != null) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Progress(state)
                    Row(
                        Modifier.fillMaxWidth(0.82f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatDuration(state.positionMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        Text(formatDuration(state.durationMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Control(VIcons.Prev, "Назад") { VolnaPlayer.previous() }
                        Control(if (state.playing) VIcons.Pause else VIcons.Play, if (state.playing) "Пауза" else "Играть", primary = true) {
                            VolnaPlayer.toggle()
                        }
                        Control(VIcons.Next, "Дальше") { VolnaPlayer.next("skip") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullLyrics(lyrics: Lyrics, positionMs: Long, durationMs: Long) {
    val list = rememberLazyListState()
    val current = currentLine(lyrics, positionMs)
    var follow by remember { mutableStateOf(true) } // false — человек листает сам
    var autoScrolling by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val third = with(LocalDensity.current) { (maxHeight / 3).roundToPx() }
        LaunchedEffect(current, follow) {
            if (follow && current >= 0) {
                autoScrolling = true
                list.animateScrollToItem(current, -third)
                autoScrolling = false
            }
        }
        LaunchedEffect(list.isScrollInProgress) {
            if (list.isScrollInProgress && !autoScrolling) {
                follow = false
            } else if (!list.isScrollInProgress && !follow) {
                delay(4000) // полистали и отпустили — через пару секунд снова за песней
                follow = true
            }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = maxHeight / 2)) {
            if (!lyrics.synced) {
                item { Hint("Текст без разметки по времени", Modifier.padding(bottom = 12.dp)) }
            }
            itemsIndexed(lyrics.lines) { i, line ->
                val active = i == current
                val seekable = lyrics.synced && line.time != null && durationMs > 0
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp),
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !lyrics.synced || active -> Palette.Fg
                        i < current -> Palette.Muted.copy(alpha = 0.35f)
                        else -> Palette.Muted
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = seekable) {
                            VolnaPlayer.seek((line.time!! * 1000 / durationMs).toFloat())
                            follow = true
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
