package app.volna.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.volna.data.FoundArtist
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * «Добавить исполнителя»: найти в MusicBrainz и поставить на докачку через Lidarr — в том
 * числе тех, кого в библиотеке ещё нет. Soularr скачает студийные альбомы и новые релизы.
 */
@Composable
fun AddArtistScreen(nav: Navigator) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<FoundArtist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    val following = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { available = runCatching { VolnaPlayer.api.lidarrAvailable() }.getOrDefault(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        delay(400) // поиск идёт в MusicBrainz — не на каждую букву
        try {
            found = VolnaPlayer.api.findArtists(query.trim())
            error = null
        } catch (e: IOException) {
            error = e.message
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Добавить исполнителя") { nav.back() } }
        if (available == false) {
            item { Hint("Докачка дискографий не настроена: сервису VaginaWave нужен доступ к Lidarr на сервере.") }
            return@LazyColumn
        }
        item {
            Hint("Найдите исполнителя — Soularr скачает его студийные альбомы и будет докачивать новые релизы.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Имя исполнителя") },
                leadingIcon = { VIcon(VIcons.Search, null, 20.dp, Palette.Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                colors = textFieldColors(),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            error?.let { Hint(it) }
        }
        val results = found
        when {
            query.isBlank() -> {}
            results == null -> item { Loading() }
            results.isEmpty() -> item { Empty("В MusicBrainz никого не нашлось") }
            else -> items(results, key = { it.mbid }) { artist ->
                FoundArtistRow(artist, artist.following || artist.mbid in following) {
                    following.add(artist.mbid)
                    scope.launch {
                        runCatching { VolnaPlayer.api.followArtist(artist.mbid) }
                            .onSuccess { VolnaPlayer.say("«${artist.name}» — на докачке") }
                            .onFailure { following.remove(artist.mbid); VolnaPlayer.say(it.message ?: "Lidarr не принял исполнителя") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FoundArtistRow(artist: FoundArtist, following: Boolean, onFollow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(artist.image, Modifier.size(56.dp), round = true)
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.note.isNotEmpty()) {
                Text(artist.note, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (artist.overview.isNotEmpty()) {
                Text(artist.overview, style = MaterialTheme.typography.bodySmall, color = Palette.Muted.copy(alpha = 0.6f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (following) {
            Text("Качается", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        } else {
            Pill("Скачать", onClick = onFollow)
        }
    }
    Divider()
}
