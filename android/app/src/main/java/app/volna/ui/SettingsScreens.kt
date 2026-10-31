package app.volna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.volna.BuildConfig
import app.volna.VolnaApp
import app.volna.data.ServerInfo
import app.volna.player.VolnaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

@Composable
fun SettingsScreen(nav: Navigator) {
    val app = LocalContext.current.applicationContext as VolnaApp
    val server = load("settings-server") { VolnaPlayer.api.server() }
    val info = (server.value as? Load.Done)?.value
    val serverSubtitle = when {
        server.value is Load.Failed -> "Сервис Volna недоступен"
        info == null -> "Navidrome и AudioMuse"
        info.mode == "demo" -> "Демо-библиотека · подключите свой Navidrome"
        else -> "Подключено: ${info.navidromeUrl.substringAfter("://")}"
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader("Настройки") }
        item {
            Column {
                Tile(VIcons.Phone, "Аккаунт", accountSubtitle(app)) { nav.go(Screen.SettingsPage("service")) }
                Tile(VIcons.Server, "Сервер", serverSubtitle) { nav.go(Screen.SettingsPage("server")) }
                Tile(VIcons.Wave, "Моя волна", "Характер, настроение, смелость подбора") { nav.go(Screen.SettingsPage("wave")) }
                Tile(VIcons.Playback, "Воспроизведение", "Качество потока, что играть после альбома") { nav.go(Screen.SettingsPage("playback")) }
                Tile(VIcons.Palette, "Интерфейс", "Фон, обложки") { nav.go(Screen.SettingsPage("interface")) }
                Tile(
                    VIcons.Database,
                    "Библиотека и вкус",
                    info?.takeIf { it.tracks > 0 }?.let { "${it.tracks} ${plural(it.tracks, "трек", "трека", "треков")}" } ?: "Синхронизация и что система о вас знает",
                ) { nav.go(Screen.SettingsPage("library")) }
                Tile(VIcons.Info, "О приложении", "Версия ${BuildConfig.VERSION_NAME}") { nav.go(Screen.SettingsPage("about")) }
            }
        }
    }
}

@Composable
fun SettingsPage(key: String, nav: Navigator) {
    val title = mapOf(
        "service" to "Аккаунт", "server" to "Сервер", "wave" to "Моя волна", "playback" to "Воспроизведение",
        "interface" to "Интерфейс", "library" to "Библиотека и вкус", "about" to "О приложении",
    )[key].orEmpty()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { PageHeader(title) { nav.back() } }
        item {
            Column {
                when (key) {
                    "service" -> ServicePage()
                    "server" -> ServerPage()
                    "wave" -> {
                        Hint("Те же настройки открываются кнопкой под волной на главной. Меняются на ходу: текущий трек доигрывает, пересобирается очередь.")
                        Spacer(Modifier.height(16.dp))
                        WaveSettingsPanel(onShake = { VolnaPlayer.shake() })
                        Spacer(Modifier.height(16.dp))
                        Pill("Настроить вкус заново", VIcons.Artist) { Onboarding.show = true }
                        Hint("Снова выбрать любимых артистов и то, как вам слушается.")
                    }
                    "playback" -> PlaybackPage()
                    "interface" -> InterfacePage()
                    "library" -> LibraryPage()
                    "about" -> AboutPage()
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ServicePage() {
    val app = LocalContext.current.applicationContext as VolnaApp
    val scope = rememberCoroutineScope()
    GroupLabel("Подключение")
    Hint(buildString {
        if (app.prefs.user.isNotEmpty()) append("Вход: ${app.prefs.user}\n")
        append("Сервис Volna: ${app.prefs.serviceUrl}")
    })
    Spacer(Modifier.height(16.dp))
    Pill("Выйти") {
        scope.launch {
            VolnaPlayer.reset()
            app.api.logout()
        }
    }
    Hint("Чтобы подключиться к другому серверу, выйдите и введите его адрес.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerPage() {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val fields = remember {
        linkedMapOf(
            "navidrome_url" to mutableStateOf(""), "navidrome_user" to mutableStateOf(""), "navidrome_password" to mutableStateOf(""),
            "audiomuse_url" to mutableStateOf(""), "audiomuse_token" to mutableStateOf(""),
            "audiomuse_user" to mutableStateOf(""), "audiomuse_password" to mutableStateOf(""),
        )
    }
    LaunchedEffect(Unit) {
        while (true) { // пока идёт синхронизация или прогрев кэша — обновляем статус
            try {
                val s = VolnaPlayer.api.server()
                if (info == null) {
                    fields.getValue("navidrome_url").value = s.navidromeUrl
                    fields.getValue("navidrome_user").value = s.navidromeUser
                    fields.getValue("audiomuse_url").value = s.audiomuseUrl
                    fields.getValue("audiomuse_user").value = s.audiomuseUser
                }
                info = s
                error = null
            } catch (e: IOException) {
                error = e.message
            }
            delay(3000)
        }
    }
    val i = info
    GroupLabel("Состояние")
    when {
        error != null -> Hint("Сервис Volna недоступен: $error")
        i == null -> Loading()
        i.mode == "demo" -> Hint("Сейчас играет демо-библиотека. Введите адрес своего Navidrome, логин и пароль.")
        else -> {
            Text("Navidrome: ${i.navidromeUrl}", style = MaterialTheme.typography.bodyMedium)
            Hint(
                buildString {
                    append("Библиотека: ${i.tracks} треков, ${i.albums} альбомов, ${i.artists} артистов")
                    if (i.syncing) append(" · синхронизация…")
                    if (i.warmTotal > 0 && i.warmRunning) append("\nПодготовка волны: ${i.warmDone} из ${i.warmTotal}")
                },
            )
        }
    }
    if (i != null && !i.canManage) {
        Hint("Адреса Navidrome и AudioMuse настраивает администратор Navidrome. Ваши волна, история и настройки — только ваши.")
        return
    }
    Spacer(Modifier.height(20.dp))
    GroupLabel("Navidrome")
    ServerField(fields, "navidrome_url", "Адрес сервера", KeyboardType.Uri)
    ServerField(fields, "navidrome_user", "Логин")
    ServerField(fields, "navidrome_password", "Пароль", KeyboardType.Password, secret = true,
        placeholder = if (i?.hasPassword == true) "сохранён — оставьте пустым" else "")
    Spacer(Modifier.height(12.dp))
    GroupLabel("AudioMuse-AI (для волны)")
    ServerField(fields, "audiomuse_url", "Адрес AudioMuse", KeyboardType.Uri)
    Hint("Вход: API-токен или логин и пароль его веб-интерфейса — достаточно одного.")
    ServerField(fields, "audiomuse_token", "API-токен", KeyboardType.Password, secret = true,
        placeholder = if (i?.hasToken == true) "сохранён — оставьте пустым" else "")
    ServerField(fields, "audiomuse_user", "Логин AudioMuse")
    ServerField(fields, "audiomuse_password", "Пароль AudioMuse", KeyboardType.Password, secret = true,
        placeholder = if (i?.hasAudiomusePassword == true) "сохранён — оставьте пустым" else "")
    Spacer(Modifier.height(12.dp))
    fun form() = JSONObject().apply { fields.forEach { (k, v) -> put(k, v.value.trim()) } }
    fun describe(r: JSONObject): String {
        fun line(name: String, o: JSONObject?) = when (o?.opt("ok")) {
            true -> "$name: подключено ${o.optString("version")}".trim()
            false -> "$name: ${o.optString("error")}"
            else -> "$name: ${o?.optString("error").orEmpty()}"
        }
        return line("Navidrome", r.optJSONObject("navidrome")) + "\n" + line("AudioMuse", r.optJSONObject("audiomuse"))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Подключиться", primary = true) {
            result = "Подключаю…"
            scope.launch {
                result = try {
                    describe(VolnaPlayer.api.saveServer(form())) + "\nБиблиотека синхронизируется в фоне."
                } catch (e: IOException) {
                    e.message
                }
            }
        }
        Pill("Проверить") {
            result = "Проверяю…"
            scope.launch {
                result = try { describe(VolnaPlayer.api.testServer(form())) } catch (e: IOException) { e.message }
            }
        }
    }
    result?.let { Hint(it) }
    Hint("Пароли и токен хранятся только на сервере, где работает Volna, и не показываются здесь.")
}

@Composable
private fun ServerField(
    fields: Map<String, androidx.compose.runtime.MutableState<String>>,
    key: String,
    label: String,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    placeholder: String = "",
) {
    val state = fields.getValue(key)
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = textFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun PlaybackPage() {
    val prefs = load("preferences") { VolnaPlayer.api.preferences() }
    val scope = rememberCoroutineScope()
    when (val p = prefs.value) {
        Load.Loading -> Loading()
        is Load.Failed -> Empty(p.message)
        is Load.Done -> {
            var quality by remember { mutableStateOf(p.value.optString("quality", "original")) }
            var continueAfter by remember { mutableStateOf(p.value.optBoolean("continue_after_end", true)) }
            fun save(patch: JSONObject) = scope.launch {
                runCatching { VolnaPlayer.api.putPreferences(patch) }.onFailure { VolnaPlayer.say(it.message ?: "Не сохранилось") }
            }
            GroupLabel("Качество потока")
            Chips(listOf("original" to "оригинал", "320" to "320 кбит/с", "192" to "192 кбит/с", "128" to "128 кбит/с"), quality) {
                quality = it
                save(JSONObject().put("quality", it))
            }
            Hint("Меньше битрейт — меньше мобильного трафика. Navidrome перекодирует в MP3 на лету.")
            Spacer(Modifier.height(16.dp))
            ToggleRow("Когда альбом или плейлист закончился, продолжать похожим", continueAfter) {
                continueAfter = it
                save(JSONObject().put("continue_after_end", it))
            }
            Hint("Продолжение — ваша волна, смещённая ко всему списку целиком, а не к последней песне.")
        }
    }
}

@Composable
private fun InterfacePage() {
    val app = LocalContext.current.applicationContext as VolnaApp
    var background by remember { mutableStateOf(app.prefs.background) }
    var color by remember { mutableStateOf(app.prefs.colorCovers) }
    var react by remember { mutableStateOf(app.prefs.react) }
    GroupLabel("Фон")
    Chips(listOf("live" to "Живые линии", "calm" to "Спокойные", "off" to "Без линий"), background) {
        background = it
        app.prefs.background = it
        UiPrefs.background = it
    }
    Hint("Топографические линии текут волной. «Спокойные» — медленнее и тише, меньше нагружают телефон.")
    Spacer(Modifier.height(12.dp))
    ToggleRow("Линии реагируют на музыку", react) {
        react = it
        app.prefs.react = it
        UiPrefs.react = it
    }
    Hint("Чем громче играет, тем выше и быстрее волна на фоне.")
    Spacer(Modifier.height(12.dp))
    ToggleRow("Чёрно-белые обложки", !color) {
        color = !it
        app.prefs.colorCovers = !it
        CoverStyle.color = !it
    }
}

@Composable
private fun LibraryPage() {
    val scope = rememberCoroutineScope()
    val server = load("library-page") { VolnaPlayer.api.server() }
    val taste = load("taste") { VolnaPlayer.api.taste() }
    GroupLabel("Библиотека")
    when (val s = server.value) {
        Load.Loading -> Loading()
        is Load.Failed -> Empty(s.message)
        is Load.Done -> Text(
            "${s.value.tracks} треков, ${s.value.albums} альбомов, ${s.value.artists} артистов, ${s.value.playlists} плейлистов",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Hint("Синхронизируется сама раз в 6 часов.")
    Spacer(Modifier.height(12.dp))
    Pill("Синхронизировать сейчас", VIcons.Sync) {
        scope.launch {
            runCatching { VolnaPlayer.api.syncLibrary() }
                .onSuccess { VolnaPlayer.say("Синхронизирую библиотеку…") }
                .onFailure { VolnaPlayer.say(it.message ?: "Не получилось") }
        }
    }
    Spacer(Modifier.height(24.dp))
    GroupLabel("Ваш вкус")
    when (val t = taste.value) {
        Load.Loading -> Loading()
        is Load.Failed -> Empty(t.message)
        is Load.Done -> {
            val loved = t.value.optJSONArray("loved_artists")
            val skipped = t.value.optJSONArray("skipped_artists")
            Text("Дослушиваете", style = MaterialTheme.typography.bodyMedium)
            if (loved == null || loved.length() == 0) Hint("пока мало данных")
            for (n in 0 until (loved?.length() ?: 0).coerceAtMost(6)) {
                val a = loved!!.getJSONObject(n)
                Hint("${n + 1}. ${a.optString("artist")} · ${(a.optDouble("rate") * 100).toInt()}%")
            }
            Spacer(Modifier.height(12.dp))
            Text("Чаще пропускаете", style = MaterialTheme.typography.bodyMedium)
            if (skipped == null || skipped.length() == 0) Hint("пока мало данных")
            for (n in 0 until (skipped?.length() ?: 0).coerceAtMost(6)) {
                val a = skipped!!.getJSONObject(n)
                Hint("${n + 1}. ${a.optString("artist")} · ${a.optDouble("skips").toInt()} проп.")
            }
        }
    }
}

@Composable
private fun AboutPage() {
    Text("Volna ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
    Hint("Клиент Navidrome с бесконечной «Моей волной», которая учится на ваших пропусках. Звучание треков анализирует AudioMuse-AI.")
    Hint("Исходники: github.com/RuNick7/volna")
}

/** Настройки интерфейса, которые читает App: фон. Обложки — в CoverStyle. */
object UiPrefs {
    var background by mutableStateOf("live")
    var react by mutableStateOf(true)
    var lyrics by mutableStateOf(false)
}

private fun accountSubtitle(app: VolnaApp): String {
    val host = app.prefs.serviceUrl.substringAfter("://")
    return if (app.prefs.user.isEmpty()) host else "${app.prefs.user} · $host"
}
