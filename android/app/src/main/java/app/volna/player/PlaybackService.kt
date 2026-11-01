package app.volna.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.volna.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Фоновое воспроизведение: уведомление, экран блокировки, наушники, Bluetooth.
 * Кнопки «дальше» и «назад» из системы идут в VolnaPlayer, чтобы волна знала о пропуске,
 * а в уведомлении есть «Нравится».
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).build())
                .setMediaButtonPreferences(listOf(likeButton(VolnaPlayer.state.value.track?.liked == true)))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != LIKE.customAction) return super.onCustomCommand(session, controller, customCommand, args)
            VolnaPlayer.like()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = object : ForwardingPlayer(VolnaPlayer.exo) {
            override fun seekToNext() = VolnaPlayer.next("skip")
            override fun seekToNextMediaItem() = VolnaPlayer.next("skip")
            override fun seekToPrevious() = VolnaPlayer.previous()
            override fun seekToPreviousMediaItem() = VolnaPlayer.previous()

            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // обложку для шторки и экрана блокировки грузим тем же клиентом, что и всё остальное: с токеном,
        // а у скачанных треков — из файла на телефоне
        val covers = DataSourceBitmapLoader.Builder(this)
            .setDataSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(VolnaPlayer.api.client)))
            .build()
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).setBitmapLoader(covers).setCallback(callback).build()
        // сердечко в уведомлении следует за лайком, откуда бы его ни поставили
        scope.launch {
            VolnaPlayer.state.map { it.track?.liked == true }.distinctUntilChanged().collect { liked ->
                session?.setMediaButtonPreferences(listOf(likeButton(liked)))
            }
        }
    }

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Убрать из «Мне нравится»" else "Нравится")
            .setSessionCommand(LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // приложение смахнули из недавних: если музыка не играет, сервис больше не нужен
        if (!VolnaPlayer.exo.playWhenReady || VolnaPlayer.exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release() // сам ExoPlayer живёт в VolnaPlayer, его не освобождаем
        session = null
        super.onDestroy()
    }

    private companion object {
        val LIKE = SessionCommand("app.volna.LIKE", Bundle.EMPTY)
    }
}
