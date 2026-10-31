package app.volna.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.volna.data.ImportItem
import app.volna.data.Imports
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Импорт по ссылке: плейлист или альбом Spotify, Apple Music. Explo на сервере докачивает
 * недостающее, а плейлист появляется в вашем Navidrome. Ссылку можно вставить или прислать
 * из Spotify через «Поделиться → Volna».
 */
@Composable
fun ImportScreen(sharedUrl: String, nav: Navigator) {
    var url by remember { mutableStateOf(sharedUrl) }
    var imports by remember { mutableStateOf<Imports?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            imports = VolnaPlayer.api.imports()
            error = null
        } catch (e: IOException) {
            error = e.message
        }
    }

    fun start() {
        if (sending || url.isBlank()) return
        sending = true
        scope.launch {
            try {
                VolnaPlayer.api.startImport(url.trim())
                url = ""
                refresh()
            } catch (e: IOException) {
                error = e.message
            } finally {
                sending = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) { // пока что-то импортируется — показываем, как идут дела
            refresh()
            delay(if (imports?.items?.any { it.active } == true) 3000 else 15000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Импорт") { nav.back() } }
        if (imports?.available == false) {
            item { Hint("Импорт не настроен: сервису Volna нужен доступ к Explo на сервере.") }
            return@LazyColumn
        }
        item {
            Hint("Ссылка на плейлист или альбом Spotify или Apple Music. Чего нет в библиотеке, Explo докачает, а плейлист появится у вас в Navidrome.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                placeholder = { Text("https://open.spotify.com/playlist/…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { start() }),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Pill(if (sending) "Отправляю…" else "Импортировать", VIcons.Playlist, primary = true) { start() }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
            Hint("«Любимые треки» Spotify по ссылке не отдаёт: выделите их в Spotify и добавьте в обычный плейлист. Быстрее всего — «Поделиться → Volna» прямо из Spotify.")
            Spacer(Modifier.height(20.dp))
        }
        val items = imports?.items.orEmpty()
        if (items.isNotEmpty()) item { GroupLabel("Импорты") }
        items(items, key = { it.id }) { job -> ImportRow(job) { nav.go(Screen.Entity("playlist", job.playlistId)) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ImportRow(job: ImportItem, open: () -> Unit) {
    val ready = job.status == "done" && job.playlistId.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = ready, onClick = open).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (job.cover.isNotEmpty()) Cover(job.cover, Modifier.size(48.dp), corner = 6.dp)
        else VIcon(VIcons.Playlist, null, 28.dp, Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(job.name.ifEmpty { job.url }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(job.message, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (job.status == "done" && job.missing.isNotEmpty()) {
                Text("Не нашлось: " + job.missing.take(3).joinToString("; ") + if (job.missing.size > 3) "…" else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            job.active -> CircularProgressIndicator(color = Palette.Fg, strokeWidth = 1.5.dp, modifier = Modifier.size(20.dp))
            ready -> VIcon(VIcons.Chevron, null, 18.dp, Palette.Muted)
        }
    }
    Divider()
}
