package app.volna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.volna.data.Track
import app.volna.player.PlayContext
import app.volna.player.PlayerState
import app.volna.player.VolnaPlayer
import coil3.compose.AsyncImage

/** Главная: одна большая кнопка «Моя волна» (она же «сейчас играет») и полки ниже. */
@Composable
fun HomeScreen(nav: Navigator, onMore: (Track) -> Unit, onTune: () -> Unit) {
    val state by VolnaPlayer.state.collectAsState()
    val shelves = load("home") { VolnaPlayer.api.home() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item { Hero(state, Modifier.height(heroHeight), onMore, onTune) }
            when (val s = shelves.value) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { Empty("Полки недоступны: ${s.message}") }
                is Load.Done -> items(s.value, key = { it.id }) { shelf ->
                    if (shelf.tracks.isNotEmpty()) {
                        SectionTitle(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(shelf.tracks, key = { it.id }) { track ->
                                TrackCard(track) {
                                    VolnaPlayer.playList(shelf.tracks, shelf.tracks.indexOf(track), PlayContext("library", shelf.id, shelf.title))
                                }
                            }
                        }
                    } else {
                        ItemShelf(shelf.title, shelf.items) { item ->
                            if (shelf.id == "playlists") VolnaPlayer.startWave("playlist", item.id)
                            else nav.go(Screen.Entity(item.type, item.id))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Hero(state: PlayerState, modifier: Modifier, onMore: (Track) -> Unit, onTune: () -> Unit) {
    val track = state.track
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("volna", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WaveButton(state)
            Spacer(Modifier.height(28.dp))
            Text(
                track?.title ?: "Моя волна",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    track != null -> track.artist
                    state.loading -> "Настраиваю волну под ваш вкус…"
                    else -> "Нажмите, и музыка пойдёт сама"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (track != null) state.reason else "",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted.copy(alpha = 0.47f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).height(18.dp),
            )
            if (track != null) {
                Spacer(Modifier.height(18.dp))
                Progress(state)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Control(VIcons.Dislike, "Не нравится") { VolnaPlayer.next("dislike") }
                    Control(VIcons.Prev, "Назад") { VolnaPlayer.previous() }
                    Control(if (track.liked) VIcons.HeartFilled else VIcons.Heart, "Нравится", pressed = track.liked) { VolnaPlayer.like() }
                    Control(VIcons.Next, "Дальше") { VolnaPlayer.next("skip") }
                    Control(VIcons.More, "Ещё: волна и плейлист по треку") { onMore(track) }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (track != null && state.mode == "list") {
                Pill("Включить Мою волну", VIcons.Wave) { VolnaPlayer.startWave() }
            } else {
                val steered = state.mode == "wave" && state.kind == "steer" && state.title.isNotEmpty()
                val summary = waveSummary(state.settings)
                Pill(if (steered) "По «${state.title}» · $summary" else summary, VIcons.Sliders, onClick = onTune)
            }
            state.nextUp?.let { next ->
                Text(
                    "Дальше: ${next.title} · ${next.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** Круг волны: обложка видна, пока играет; значок «играть» — только на паузе. */
@Composable
private fun WaveButton(state: PlayerState) {
    val track = state.track
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(252.dp)) {
        Box(Modifier.size(252.dp).border(1.dp, Palette.Fg.copy(alpha = 0.32f), CircleShape))
        Box(
            Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(Palette.Veil)
                .border(1.5.dp, Palette.Fg, CircleShape)
                .clickable { VolnaPlayer.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (track != null && track.cover.isNotEmpty()) {
                AsyncImage(
                    model = VolnaPlayer.api.url(track.cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (CoverStyle.color) null else CoverStyle.grayscale,
                    modifier = Modifier.size(200.dp).clip(CircleShape).alpha(if (state.playing) 0.9f else 0.42f),
                )
            }
            if (!state.playing) VIcon(VIcons.Play, if (track == null) "Запустить «Мою волну»" else "Играть", 64.dp)
        }
        if (state.loading) CircularProgressIndicator(color = Palette.Fg, strokeWidth = 1.dp, modifier = Modifier.size(252.dp))
    }
}

@Composable
private fun Progress(state: PlayerState) {
    val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth(0.82f)
            .height(18.dp)
            .pointerInput(Unit) { detectTapGestures { VolnaPlayer.seek(it.x / size.width) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.Faint))
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Palette.Fg))
    }
}

