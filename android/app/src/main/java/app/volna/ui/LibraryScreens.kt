package app.volna.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.volna.data.Mix
import app.volna.data.Page
import app.volna.data.SearchResult
import app.volna.data.Track
import app.volna.player.PlayContext
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Fg,
    unfocusedBorderColor = Palette.Faint,
    cursorColor = Palette.Fg,
    focusedTextColor = Palette.Fg,
    unfocusedTextColor = Palette.Fg,
    focusedLabelColor = Palette.Fg,
    unfocusedLabelColor = Palette.Muted,
    focusedPlaceholderColor = Palette.Muted,
    unfocusedPlaceholderColor = Palette.Muted,
    focusedContainerColor = Palette.Veil,
    unfocusedContainerColor = Palette.Veil,
)

// --- поиск ---

@Composable
fun SearchScreen(nav: Navigator, onMore: (Track) -> Unit) {
    val playing by VolnaPlayer.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            result = null
            return@LaunchedEffect
        }
        delay(250)
        try {
            result = VolnaPlayer.api.search(query.trim())
            error = null
        } catch (e: java.io.IOException) {
            error = e.message
        }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Поиск") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Трек, альбом, артист или плейлист") },
                leadingIcon = { VIcon(VIcons.Search, null, tint = Palette.Muted) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = textFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val r = result
        when {
            error != null -> item { Empty(error.orEmpty()) }
            query.isBlank() -> item { Empty("Ищите по названию, артисту или альбому. С артиста можно сразу запустить волну.") }
            r == null -> item { Loading() }
            r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty() -> item { Empty("Ничего не нашлось.") }
            else -> {
                item { ItemShelf("Артисты", r.artists) { nav.go(Screen.Entity(it.type, it.id)) } }
                if (r.tracks.isNotEmpty()) item { SectionTitle("Треки") }
                itemsIndexed(r.tracks, key = { _, t -> "t" + t.id }) { i, track ->
                    TrackRow(track, playing.track?.id == track.id, onMore = onMore) {
                        VolnaPlayer.playList(r.tracks, i, PlayContext("search", query, "«$query»"))
                    }
                }
                item { ItemShelf("Альбомы", r.albums) { nav.go(Screen.Entity(it.type, it.id)) } }
                item { ItemShelf("Плейлисты", r.playlists) { nav.go(Screen.Entity(it.type, it.id)) } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// --- коллекция ---

@Composable
fun LibraryScreen(nav: Navigator) {
    val stats = load("library-stats") { VolnaPlayer.api.libraryStats() }
    val newest = load("library-newest") { VolnaPlayer.api.albums("newest").take(12) }
    val counts = (stats.value as? Load.Done)?.value
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Коллекция") }
        item {
            Column {
                Tile(VIcons.Heart, "Мне нравится", count = counts?.optInt("liked")?.toString().orEmpty()) { nav.go(Screen.LibraryList("liked")) }
                Tile(VIcons.Playlist, "Плейлисты", count = counts?.optInt("playlists")?.toString().orEmpty()) { nav.go(Screen.LibraryList("playlists")) }
                Tile(VIcons.Album, "Альбомы", count = counts?.optInt("albums")?.toString().orEmpty()) { nav.go(Screen.LibraryList("albums")) }
                Tile(VIcons.Artist, "Артисты", count = counts?.optInt("artists")?.toString().orEmpty()) { nav.go(Screen.LibraryList("artists")) }
                Tile(VIcons.Clock, "Недавно слушали") { nav.go(Screen.LibraryList("recent")) }
                Tile(VIcons.Playlist, "Импорт из Spotify", "Плейлист или альбом по ссылке") { nav.go(Screen.Import()) }
            }
        }
        item { SectionTitle("Недавно добавлены") }
        item {
            when (val s = newest.value) {
                Load.Loading -> Loading()
                is Load.Failed -> Empty("Библиотека недоступна: ${s.message}")
                is Load.Done -> ItemGrid(s.value, columns = 3) { nav.go(Screen.Entity(it.type, it.id)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private val ALBUM_SORTS = listOf("alpha" to "А–Я", "newest" to "Новые", "recent" to "Недавно слушали", "frequent" to "Часто", "year" to "По году", "liked" to "Любимые")
private val ARTIST_SORTS = listOf("alpha" to "А–Я", "frequent" to "Часто слушаете", "liked" to "Любимые")

@Composable
fun LibraryListScreen(kind: String, nav: Navigator, onMore: (Track) -> Unit) {
    when (kind) {
        "liked", "recent" -> TrackListScreen(kind, nav, onMore)
        else -> GridListScreen(kind, nav)
    }
}

@Composable
private fun TrackListScreen(kind: String, nav: Navigator, onMore: (Track) -> Unit) {
    val title = if (kind == "liked") "Мне нравится" else "Недавно слушали"
    val playing by VolnaPlayer.state.collectAsState()
    val tracks = load(kind) { if (kind == "liked") VolnaPlayer.api.liked() else VolnaPlayer.api.recent() }
    var query by rememberSaveable(kind) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader(title) { nav.back() } }
        item { FilterField(query, "Найти в «$title»") { query = it } }
        when (val s = tracks.value) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { Empty(s.message) }
            is Load.Done -> {
                val list = s.value.filter { matches(query, it.title, it.artist, it.album) }
                val context = PlayContext("library", kind, title)
                if (s.value.isEmpty()) item { Empty(if (kind == "liked") "Отмечайте треки сердцем — они соберутся здесь." else "История пока пуста.") }
                else if (list.isEmpty()) item { Empty("Ничего не нашлось.") }
                else item { PlayActions(list, context) }
                itemsIndexed(list, key = { _, t -> t.id }) { i, track ->
                    TrackRow(track, playing.track?.id == track.id, onMore = onMore) { VolnaPlayer.playList(list, i, context) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GridListScreen(kind: String, nav: Navigator) {
    val title = mapOf("albums" to "Альбомы", "artists" to "Артисты", "playlists" to "Плейлисты")[kind] ?: kind
    val sorts = when (kind) {
        "albums" -> ALBUM_SORTS
        "artists" -> ARTIST_SORTS
        else -> emptyList()
    }
    var sort by rememberSaveable(kind) { mutableStateOf("alpha") }
    var query by rememberSaveable(kind) { mutableStateOf("") }
    val items = load(kind, sort) {
        when (kind) {
            "albums" -> VolnaPlayer.api.albums(sort)
            "artists" -> VolnaPlayer.api.artists(sort)
            else -> VolnaPlayer.api.playlists()
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(140.dp),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageHeader(title) { nav.back() } }
        if (kind == "playlists") {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(bottom = 12.dp)) { Pill("Импорт из Spotify", VIcons.Playlist) { nav.go(Screen.Import()) } }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { FilterField(query, "Найти в «$title»") { query = it } }
        if (sorts.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Chips(sorts, sort) { sort = it } }
        when (val s = items.value) {
            Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Empty(s.message) }
            is Load.Done -> {
                val found = s.value.filter { matches(query, it.title, it.subtitle) }
                if (s.value.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Empty("Здесь пока пусто.") }
                else if (found.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Empty("Ничего не нашлось.") }
                items(found, key = { it.type + it.id }) { item -> ItemCard(item) { nav.go(Screen.Entity(item.type, item.id)) } }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayActions(tracks: List<Track>, context: PlayContext, extra: @Composable () -> Unit = {}) {
    FlowRow(
        Modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Pill("Слушать", VIcons.Play, primary = true) { VolnaPlayer.playList(tracks, 0, context) }
        Pill("Перемешать", VIcons.Shuffle) { VolnaPlayer.shuffle(tracks, context) }
        extra()
    }
}

// --- альбом, артист, плейлист ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityScreen(type: String, id: String, nav: Navigator, onMore: (Track) -> Unit) {
    val playing by VolnaPlayer.state.collectAsState()
    val page = load(type, id) { VolnaPlayer.api.page(type, id) }
    var liked by remember(type, id) { mutableStateOf<Boolean?>(null) }
    var allTracks by rememberSaveable(type, id) { mutableStateOf(false) }
    var allAlbums by rememberSaveable(type, id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("") { nav.back() } }
        when (val s = page.value) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { Empty(s.message) }
            is Load.Done -> {
                val p: Page = s.value
                val isLiked = liked ?: p.item.liked
                item { EntityHeader(p, type) }
                val context = PlayContext(type, id, p.item.title)
                // у артиста — все треки по популярности: сначала пять, остальные по кнопке
                val tracks = if (type == "artist") p.tracks.ifEmpty { p.top } else p.tracks
                item {
                    if (type == "artist") {
                        FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Волна по артисту", VIcons.Wave, primary = true) { VolnaPlayer.startWave("artist", id); nav.go(Screen.Home) }
                            Pill("Популярное", VIcons.Play) { VolnaPlayer.playList(tracks, 0, context) }
                            Pill("Плейлист", VIcons.Mix) { nav.go(Screen.Mix("artist", id)) }
                            LikePill(isLiked) {
                                liked = !isLiked
                                scope.launch { runCatching { VolnaPlayer.api.like(type, id, !isLiked) }.onFailure { liked = isLiked } }
                            }
                        }
                    } else {
                        PlayActions(tracks, context) {
                            Pill("Волна", VIcons.Wave) { VolnaPlayer.startWave(type, id); nav.go(Screen.Home) }
                            if (type == "album") {
                                LikePill(isLiked) {
                                    liked = !isLiked
                                    scope.launch { runCatching { VolnaPlayer.api.like(type, id, !isLiked) }.onFailure { liked = isLiked } }
                                }
                            }
                        }
                    }
                }
                if (type == "artist" && tracks.isNotEmpty()) item { SectionTitle("Популярные треки") }
                val shown = if (type == "artist" && !allTracks) tracks.take(ARTIST_TRACKS) else tracks
                itemsIndexed(shown, key = { _, t -> t.id }) { i, track ->
                    TrackRow(
                        track,
                        playing.track?.id == track.id,
                        number = if (type == "album") (track.trackNo.takeIf { it > 0 } ?: (i + 1)) else null,
                        showCover = type != "album",
                        onMore = onMore,
                    ) { VolnaPlayer.playList(tracks, i, context) }
                }
                if (type == "artist" && tracks.size > ARTIST_TRACKS) {
                    item { ShowMore(allTracks, "Все треки · ${tracks.size}") { allTracks = !allTracks } }
                }
                if (type == "artist" && p.albums.isNotEmpty()) {
                    item { SectionTitle("Альбомы") }
                    item {
                        ItemGrid(if (allAlbums) p.albums else p.albums.take(ARTIST_ALBUMS), columns = 2) { nav.go(Screen.Entity(it.type, it.id)) }
                    }
                    if (p.albums.size > ARTIST_ALBUMS) item { ShowMore(allAlbums, "Все альбомы · ${p.albums.size}") { allAlbums = !allAlbums } }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private const val ARTIST_TRACKS = 5
private const val ARTIST_ALBUMS = 4

/** «Показать все · N» / «Свернуть» под длинным списком. */
@Composable
private fun ShowMore(expanded: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Pill(if (expanded) "Свернуть" else label, onClick = onClick)
    }
}

/** Поиск внутри раздела коллекции: фильтрует то, что уже загружено, сразу при вводе. */
@Composable
private fun FilterField(query: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { VIcon(VIcons.Search, null, 20.dp, Palette.Muted) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButtonV(VIcons.Close, "Очистить", 36.dp) { onChange("") } }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        colors = textFieldColors(),
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

private fun matches(query: String, vararg fields: String): Boolean {
    val q = query.trim().lowercase().replace('ё', 'е')
    return q.isEmpty() || fields.any { it.lowercase().replace('ё', 'е').contains(q) }
}

@Composable
private fun LikePill(liked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, if (liked) Palette.Fg else Palette.Faint, RoundedCornerShape(50)),
    ) { IconButtonV(if (liked) VIcons.HeartFilled else VIcons.Heart, "Нравится", 42.dp, Palette.Fg, onClick) }
}

@Composable
private fun EntityHeader(p: Page, type: String) {
    val kind = mapOf("album" to "Альбом", "artist" to "Артист", "playlist" to "Плейлист")[type].orEmpty()
    val meta = when (type) {
        "artist" -> listOfNotNull(p.item.subtitle.ifEmpty { null }, p.plays.takeIf { it > 0 }?.let { "$it ${plural(it, "прослушивание", "прослушивания", "прослушиваний")}" })
        else -> listOfNotNull(
            p.item.year.takeIf { it > 0 }?.toString(),
            "${p.tracks.size} ${plural(p.tracks.size, "трек", "трека", "треков")}",
            longDuration(p.duration),
        )
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(p.item.cover, Modifier.widthIn(max = 240.dp).fillMaxWidth(0.6f), round = type == "artist")
        Spacer(Modifier.height(16.dp))
        Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Text(p.item.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        if (type == "album" && p.item.subtitle.isNotEmpty()) {
            val nav = LocalNav.current
            Text(
                p.item.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp).then(
                    if (nav != null && p.item.artistId.isNotEmpty()) Modifier.clickable { nav.go(Screen.Entity("artist", p.item.artistId)) } else Modifier,
                ),
            )
        }
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

// --- плейлист по треку или артисту ---

@Composable
fun MixScreen(type: String, id: String, nav: Navigator, onMore: (Track) -> Unit) {
    val playing by VolnaPlayer.state.collectAsState()
    var size by rememberSaveable { mutableIntStateOf(25) }
    var rebuild by remember { mutableIntStateOf(0) }
    val mix = load(type, id, size, rebuild) { VolnaPlayer.api.mix(type, id, size) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("") { nav.back() } }
        when (val s = mix.value) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { Empty(s.message) }
            is Load.Done -> {
                val m: Mix = s.value
                val context = PlayContext("other", "mix:$type:$id", m.title)
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(m.tracks.firstOrNull()?.cover.orEmpty(), Modifier.widthIn(max = 240.dp).fillMaxWidth(0.6f))
                        Spacer(Modifier.height(16.dp))
                        Text(if (type == "artist") "ПЛЕЙЛИСТ ПО АРТИСТУ" else "ПЛЕЙЛИСТ ПО ТРЕКУ", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                        Text(m.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            "${m.tracks.size} ${plural(m.tracks.size, "трек", "трека", "треков")} · ${longDuration(m.tracks.sumOf { it.duration })}",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp),
                        )
                        Hint("Похожее на источник с поправкой на ваш вкус: без нелюбимого и пропускаемого.")
                    }
                }
                item {
                    PlayActions(m.tracks, context) {
                        Pill("Сохранить", VIcons.Mix) {
                            if (name.isEmpty()) name = m.title
                            saving = !saving
                        }
                        Pill("Пересобрать", VIcons.Sync) { rebuild++ }
                    }
                }
                if (saving) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it }, singleLine = true,
                                label = { Text("Название") }, colors = textFieldColors(), modifier = Modifier.weight(1f),
                            )
                            Pill("Сохранить", primary = true) {
                                scope.launch {
                                    try {
                                        val saved = VolnaPlayer.api.createPlaylist(name.trim(), m.tracks.map { it.id })
                                        VolnaPlayer.say("Плейлист «${saved.optString("name")}» сохранён в Navidrome")
                                        saving = false
                                        saved.optString("id").takeIf { it.isNotEmpty() }?.let { nav.go(Screen.Entity("playlist", it)) }
                                    } catch (e: java.io.IOException) {
                                        VolnaPlayer.say(e.message ?: "Не удалось сохранить")
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        Chips(listOf("15" to "15 треков", "25" to "25", "50" to "50"), size.toString()) { size = it.toInt() }
                    }
                }
                itemsIndexed(m.tracks, key = { _, t -> t.id }) { i, track ->
                    TrackRow(track, playing.track?.id == track.id, onMore = onMore) { VolnaPlayer.playList(m.tracks, i, context) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
