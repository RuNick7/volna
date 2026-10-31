package app.volna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.volna.data.Item
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/** Показывать ли первый вход: сам при первом входе в Volna или по кнопке «Настроить вкус заново». */
object Onboarding {
    var show by mutableStateOf(false)
}

private const val ENOUGH = 3 // как в Яндексе: хотя бы три артиста

/**
 * Первый вход, как в Яндекс Музыке: «Кого вы любите?» — артисты кружками, рядом с отмеченным
 * появляются похожие по звучанию; потом «Как вам слушается?» — характер волны и настроение.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var artists by remember { mutableStateOf<List<Item>>(emptyList()) }
    val selected = remember { mutableStateListOf<String>() }
    var settings by remember { mutableStateOf(JSONObject()) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Item>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val data = VolnaPlayer.api.onboarding()
            artists = data.artists
            selected.addAll(data.artists.filter { it.liked }.map { it.id })
            settings = data.settings
        } catch (e: IOException) {
            error = e.message
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        delay(250)
        found = runCatching { VolnaPlayer.api.search(query.trim()).artists }.getOrNull()
    }

    fun toggle(item: Item) {
        if (item.id in selected) {
            selected.remove(item.id)
            return
        }
        selected.add(item.id)
        if (artists.none { it.id == item.id }) artists = listOf(item) + artists
        scope.launch { // похожие встают сразу за выбранным: новые и те, что были ниже по списку
            val similar = runCatching { VolnaPlayer.api.similarArtists(item.id) }.getOrDefault(emptyList())
            val bring = similar.filter { it.id !in selected }.map { s -> artists.firstOrNull { it.id == s.id } ?: s }
            if (bring.isEmpty()) return@launch
            val rest = artists.filter { a -> bring.none { it.id == a.id } }.toMutableList()
            rest.addAll(rest.indexOfFirst { it.id == item.id } + 1, bring)
            artists = rest
        }
    }

    fun finish(chosen: List<String>) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                VolnaPlayer.api.finishOnboarding(chosen, settings)
                VolnaPlayer.loadSettings()
                if (chosen.isNotEmpty()) VolnaPlayer.say("Волна начнёт с ваших артистов")
                onDone()
            } catch (e: IOException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = step == 1) { step = 0 }
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Topo(Modifier.fillMaxSize(), mode = UiPrefs.background, dim = true)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (step == 0) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Кого вы любите?", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
                    Hint("Отметьте хотя бы трёх — первая волна начнётся с них. Похожие появятся рядом.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Найти артиста") },
                        leadingIcon = { VIcon(VIcons.Search, null, 20.dp, Palette.Muted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Hint(it) }
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(104.dp),
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(found ?: artists, key = { it.id }) { artist ->
                        ArtistBubble(artist, artist.id in selected, Modifier.animateItem()) { toggle(artist) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Пропустить", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                        modifier = Modifier.clickable { finish(emptyList()) }.padding(8.dp))
                    Spacer(Modifier.weight(1f))
                    val n = selected.size
                    Pill(if (n >= ENOUGH) "Дальше · $n" else "Ещё ${ENOUGH - n}", primary = n >= ENOUGH) {
                        if (n >= ENOUGH) step = 1 else VolnaPlayer.say("Отметьте хотя бы трёх артистов")
                    }
                }
            } else {
                Preferences(settings, saving, onChange = { settings = it }, onBack = { step = 0 }) { finish(selected.toList()) }
            }
        }
    }
}

@Composable
private fun ArtistBubble(artist: Item, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, label = "bubble")
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Cover(
                artist.cover,
                Modifier
                    .size(92.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(if (selected) 2.dp else 0.dp, Palette.Fg, CircleShape),
                round = true,
            )
            if (selected) {
                Box(Modifier.size(26.dp).background(Palette.Fg, CircleShape), contentAlignment = Alignment.Center) {
                    VIcon(VIcons.Check, "Выбран", 16.dp, Palette.Bg)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            artist.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.Fg else Palette.Muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Preferences(settings: JSONObject, saving: Boolean, onChange: (JSONObject) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    fun set(key: String, value: Any) = onChange(JSONObject(settings.toString()).put(key, value))
    val character = settings.optString("character", "balanced")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Как вам слушается?", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
        Hint("Это можно поменять в любой момент кнопкой под волной.")
        Spacer(Modifier.height(20.dp))
        GroupLabel("Что чаще ставить")
        Chips(CHARACTERS, character) { set("character", it) }
        Hint(CHARACTER_HINTS[character].orEmpty())
        Spacer(Modifier.height(20.dp))
        GroupLabel("Настроение")
        Chips(MOODS, settings.optString("mood", "any")) { set("mood", it) }
        Spacer(Modifier.height(12.dp))
        ToggleRow("Без слов", settings.optBoolean("no_vocals")) { set("no_vocals", it) }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Назад", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Pill(if (saving) "Сохраняю…" else "Готово", primary = true, onClick = onDone)
        }
        Spacer(Modifier.height(24.dp))
    }
}
