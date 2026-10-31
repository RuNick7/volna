package app.volna.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.volna.MainActivity

/**
 * Фоновое воспроизведение: уведомление, экран блокировки, наушники, Bluetooth.
 * Кнопки «дальше» и «назад» из системы идут в VolnaPlayer, чтобы волна знала о пропуске.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

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
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // приложение смахнули из недавних: если музыка не играет, сервис больше не нужен
        if (!VolnaPlayer.exo.playWhenReady || VolnaPlayer.exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release() // сам ExoPlayer живёт в VolnaPlayer, его не освобождаем
        session = null
        super.onDestroy()
    }
}
