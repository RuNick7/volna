package app.volna

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.volna.data.Track
import app.volna.player.PlaybackService
import app.volna.player.VolnaPlayer
import app.volna.ui.ConnectScreen
import app.volna.ui.CoverStyle
import app.volna.ui.EntityScreen
import app.volna.ui.HomeScreen
import app.volna.ui.ImportScreen
import app.volna.ui.LibraryListScreen
import app.volna.ui.LibraryScreen
import app.volna.ui.LocalNav
import app.volna.ui.MiniPlayer
import app.volna.ui.MixScreen
import app.volna.ui.Navigator
import app.volna.ui.Palette
import app.volna.ui.QueueScreen
import app.volna.ui.Screen
import app.volna.ui.SearchScreen
import app.volna.ui.SettingsPage
import app.volna.ui.SettingsScreen
import app.volna.ui.Sheet
import app.volna.ui.TabBar
import app.volna.ui.Topo
import app.volna.ui.TrackActions
import app.volna.ui.UiPrefs
import app.volna.ui.VolnaTheme
import app.volna.ui.WaveSettingsPanel
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as VolnaApp
        CoverStyle.color = app.prefs.colorCovers
        UiPrefs.background = app.prefs.background
        UiPrefs.react = app.prefs.react
        UiPrefs.lyrics = app.prefs.lyrics
        takeShared(intent)
        setContent {
            VolnaTheme {
                val needLogin by app.api.needLogin.collectAsState()
                if (needLogin) ConnectScreen() else VolnaRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** «Поделиться → Volna» из Spotify или Apple Music: откроем импорт с этой ссылкой. */
    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        Shared.url = Regex("""https://\S+""").find(text)?.value
    }

    override fun onStart() {
        super.onStart()
        // подключаемся к сервису воспроизведения: так музыка продолжит играть в фоне и в уведомлении
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}

@Composable
private fun VolnaRoot() {
    val nav = remember { Navigator() }
    val state by VolnaPlayer.state.collectAsState()
    var actionsFor by remember { mutableStateOf<Track?>(null) }
    var tuneOpen by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    LaunchedEffect(Shared.url) {
        Shared.url?.let { nav.go(Screen.Import(it)) }
        Shared.url = null
    }

    // «назад» на корне главной сворачивает приложение, а не закрывает: музыка продолжает играть
    BackHandler { if (!nav.back()) activity?.moveTaskToBack(true) }
    AskNotifications()

    CompositionLocalProvider(LocalNav provides nav) { Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Topo(Modifier.fillMaxSize(), mode = UiPrefs.background, dim = nav.current != Screen.Home, playing = state.playing)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (val screen = nav.current) {
                    Screen.Home -> HomeScreen(nav, onMore = { actionsFor = it }, onTune = { tuneOpen = true })
                    Screen.Search -> SearchScreen(nav) { actionsFor = it }
                    Screen.Library -> LibraryScreen(nav)
                    Screen.Settings -> SettingsScreen(nav)
                    is Screen.LibraryList -> LibraryListScreen(screen.kind, nav) { actionsFor = it }
                    is Screen.Entity -> EntityScreen(screen.type, screen.id, nav) { actionsFor = it }
                    is Screen.Mix -> MixScreen(screen.type, screen.id, nav) { actionsFor = it }
                    Screen.Queue -> QueueScreen(nav) { actionsFor = it }
                    is Screen.Import -> ImportScreen(screen.url, nav)
                    is Screen.SettingsPage -> SettingsPage(screen.key, nav)
                }
            }
            if (nav.current != Screen.Home && state.track != null) MiniPlayer { nav.go(Screen.Home) }
            TabBar(nav.tab) { nav.tab(it) }
        }
        Toast(state.message, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
    } }

    actionsFor?.let { track -> TrackActions(track, onDismiss = { actionsFor = null }, navigate = nav::go) }
    if (tuneOpen) {
        Sheet(onDismiss = { tuneOpen = false }) {
            Text("Настроить волну", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            WaveSettingsPanel(onShake = {
                tuneOpen = false
                VolnaPlayer.shake()
            })
        }
    }
}

/** Ссылка, которой поделились с Volna: её подхватит экран импорта. */
object Shared {
    var url by mutableStateOf<String?>(null)
}

@Composable
private fun Toast(message: String?, modifier: Modifier) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(3500)
        VolnaPlayer.consumeMessage()
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Palette.Bg,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Fg)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** Android 13+: без разрешения на уведомления не будет управления музыкой в шторке. */
@Composable
private fun AskNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
