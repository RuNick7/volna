package app.volna

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Версия из релизов на GitHub и её APK. */
data class Release(val version: String, val notes: String, val apk: String, val size: Long)

sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val progress: Float) : UpdateStep
    data class Ready(val file: File) : UpdateStep // скачано, ждёт установки
    data class Failed(val message: String) : UpdateStep
}

/**
 * Обновления приложения из релизов на GitHub. Раз в 6 часов (и при запуске, если давно не
 * проверяли) смотрим последний релиз с APK; если он новее установленного — уведомление и
 * шторка «Доступна VaginaWave X». APK скачивается в кэш приложения, проверяется (тот же
 * пакет, версия выше) и отдаётся системному установщику; подпись сверяет сам Android.
 */
object Updates {
    const val ACTION = "app.volna.UPDATE"
    private const val REPO = "RuNick7/VaginaWave"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 3
    private const val CHECK_EVERY = 6 * 3600_000L

    private val scope = MainScope()
    private lateinit var app: Context
    private lateinit var client: OkHttpClient
    private val prefs by lazy { app.getSharedPreferences("updates", Context.MODE_PRIVATE) }

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available
    private val _step = MutableStateFlow<UpdateStep>(UpdateStep.Idle)
    val step: StateFlow<UpdateStep> = _step

    /** Шторка с обновлением открыта (из уведомления, при запуске или из «О приложении»). */
    var sheet by mutableStateOf(false)
    private var waitingForPermission = false // ушли в настройки разрешить установку

    fun init(context: Context, http: OkHttpClient) {
        if (::app.isInitialized) return
        app = context.applicationContext
        client = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        // что нашли в прошлый раз — сразу, без сети; уже установленное и старые APK забываем
        _available.value = prefs.getString("release", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            ?.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
        val keep = _available.value?.let { "vaginawave-${it.version}.apk" }
        File(app.cacheDir, "updates").listFiles()?.forEach { if (it.name != keep) it.delete() }
        scope.launch {
            while (true) {
                if (System.currentTimeMillis() - prefs.getLong("checked", 0) >= CHECK_EVERY) check()
                delay(CHECK_EVERY / 6)
            }
        }
    }

    /** Найти новую версию. true — она есть; null — проверить не удалось (нет сети или GitHub). */
    suspend fun check(): Boolean? {
        val release = try {
            withContext(Dispatchers.IO) { latest() }
        } catch (e: Exception) {
            return null
        }
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        val fresh = release?.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
        _available.value = fresh
        prefs.edit().putString("release", fresh?.let(::json)?.toString()).apply()
        if (fresh != null && prefs.getString("notified", null) != fresh.version) {
            notify(fresh)
            prefs.edit().putString("notified", fresh.version).apply()
        }
        return fresh != null
    }

    /** Показать шторку при запуске — один раз на версию, дальше она ждёт в «О приложении». */
    fun offer() {
        val release = _available.value ?: return
        if (prefs.getString("offered", null) == release.version) return
        prefs.edit().putString("offered", release.version).apply()
        sheet = true
    }

    fun download() {
        val release = _available.value ?: return
        if (_step.value is UpdateStep.Downloading) return
        _step.value = UpdateStep.Downloading(0f)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetch(release) }
                _step.value = UpdateStep.Ready(file)
                install(file)
            } catch (e: IOException) {
                _step.value = UpdateStep.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Не удалось скачать обновление")
            }
        }
    }

    /** Отдать APK системному установщику; сначала — разрешение ставить приложения из VaginaWave. */
    fun install(file: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        waitingForPermission = false
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Вернулись из настроек с разрешением — продолжаем установку сами. */
    fun resume() {
        val ready = _step.value as? UpdateStep.Ready ?: return
        if (waitingForPermission && app.packageManager.canRequestPackageInstalls()) install(ready.file)
    }

    // --- внутреннее ---

    private fun latest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VaginaWave/${BuildConfig.VERSION_NAME}")
            .build()
        val releases = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub ответил ${response.code}")
            JSONArray(response.body.string())
        }
        return (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                    ?: return@mapNotNull null
                Release(
                    version = r.optString("tag_name").removePrefix("v"),
                    notes = r.optString("body").replace("**", "").trim(),
                    apk = apk.optString("browser_download_url"),
                    size = apk.optLong("size"),
                )
            }
            .maxWithOrNull { a, b -> compare(a.version, b.version) }
    }

    private fun fetch(release: Release): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "vaginawave-${release.version}.apk")
        if (file.exists() && file.length() == release.size && verified(file)) return file
        val tmp = File(dir, file.name + ".part")
        client.newCall(Request.Builder().url(release.apk).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Не удалось скачать обновление: ошибка ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.size
            response.body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) _step.value = UpdateStep.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (!tmp.renameTo(file) || !verified(file)) {
            file.delete()
            throw IOException("Скачанный файл не похож на обновление VaginaWave")
        }
        return file
    }

    /** Тот же пакет и версия выше установленной; подпись проверит установщик. */
    private fun verified(file: File): Boolean {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        return info.packageName == app.packageName && PackageInfoCompat.getLongVersionCode(info) > PackageInfoCompat.getLongVersionCode(installed)
    }

    private fun notify(release: Release) {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java).setAction(ACTION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Доступна VaginaWave ${release.version}")
            .setContentText("Нажмите, чтобы скачать и установить")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun json(r: Release) = JSONObject().put("version", r.version).put("notes", r.notes).put("apk", r.apk).put("size", r.size)

    private fun parse(o: JSONObject) = Release(o.getString("version"), o.optString("notes"), o.getString("apk"), o.optLong("size"))

    fun newer(a: String, b: String) = compare(a, b) > 0

    /** «0.10.0» > «0.9.1»: сравниваем по числам, а не строкой. */
    private fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
