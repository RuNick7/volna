package app.volna.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.volna.MainActivity
import app.volna.R

/**
 * Держит загрузку живой в фоне: уведомление «Скачиваю N треков». Сами загрузки ведёт
 * Downloads.manager; без планировщика — если Wi‑Fi пропал, продолжим, когда приложение снова
 * откроют или сеть вернётся, пока оно живо.
 */
class VolnaDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL,
    R.string.downloads_channel,
    0,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager = Downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message = if (active > 0) "Скачиваю для прослушивания без сети: осталось $active" else null
        return notifications.buildProgressNotification(this, R.drawable.ic_download, open, message, downloads, notMetRequirements)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL = "downloads"
    }
}
