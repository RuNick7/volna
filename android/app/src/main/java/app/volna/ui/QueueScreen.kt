package app.volna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.volna.data.Track
import app.volna.player.VolnaPlayer

/**
 * Очередь, как в Spotify: что играет, что добавлено вручную (можно перетаскивать за «≡»,
 * убирать и запускать с любого места) и что пойдёт потом — волна или остаток альбома.
 */
@Composable
fun QueueScreen(nav: Navigator, onMore: (Track) -> Unit) {
    val state by VolnaPlayer.state.collectAsState()
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Очередь") { nav.back() } }
        val track = state.track
        if (track == null) {
            item { Empty("Сейчас ничего не играет. Смахните трек вправо в любом списке — он встанет в очередь.") }
            return@LazyColumn
        }
        item { GroupLabel("Сейчас играет") }
        item { TrackRow(track, current = true, onMore = onMore, queueable = false) {} }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { GroupLabel("В очереди") }
                if (state.queue.isNotEmpty()) {
                    Text("Очистить", style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                        modifier = Modifier.clickable { VolnaPlayer.clearQueue() }.padding(bottom = 12.dp))
                }
            }
        }
        if (state.queue.isEmpty()) {
            item { Hint("Смахните трек вправо в любом списке или выберите «В очередь» в меню «⋯». Очередь играет раньше волны.") }
        }
        // ключ — трек и номер его повтора: при перестановке строка остаётся той же и жест не обрывается
        val keys = state.queue.runningFold(emptyMap<String, Int>() to "") { (seen, _), t ->
            val n = seen[t.id] ?: 0
            (seen + (t.id to n + 1)) to "${t.id}#$n"
        }.drop(1).map { it.second }
        itemsIndexed(state.queue, key = { i, _ -> keys[i] }) { i, queued ->
            val position by rememberUpdatedState(i)
            val lifted = i == dragging
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) offset else 0f }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (lifted) Palette.Hover else androidx.compose.ui.graphics.Color.Transparent)
                    .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TrackRow(queued, current = false, onMore = onMore, queueable = false) { VolnaPlayer.playFromQueue(i) }
                }
                IconButtonV(VIcons.Close, "Убрать из очереди", 36.dp) { VolnaPlayer.removeFromQueue(i) }
                // ручка: потянуть вверх или вниз — трек меняется местами с соседями
                Box(
                    Modifier
                        .size(40.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = position; offset = 0f },
                                onDragEnd = { dragging = -1; offset = 0f },
                                onDragCancel = { dragging = -1; offset = 0f },
                            ) { change, dy ->
                                change.consume()
                                offset += dy
                                val from = dragging
                                val last = VolnaPlayer.state.value.queue.lastIndex
                                val step = when {
                                    offset > rowHeight / 2f && from < last -> 1
                                    offset < -rowHeight / 2f && from > 0 -> -1
                                    else -> 0
                                }
                                if (step != 0) {
                                    VolnaPlayer.moveInQueue(from, from + step)
                                    dragging = from + step
                                    offset -= step * rowHeight
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { VIcon(VIcons.Grip, "Перетащить", 20.dp, Palette.Muted) }
            }
        }

        if (state.upNext.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 20.dp)) {
                    GroupLabel(if (state.source.isNotEmpty()) "Дальше: ${state.source}" else "Дальше")
                }
            }
            itemsIndexed(state.upNext, key = { i, t -> "n$i-${t.id}" }) { _, upcoming ->
                TrackRow(upcoming, current = false, onMore = onMore) {}
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
