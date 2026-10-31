package app.volna.data

import android.content.Context

/** Настройки самого приложения. Всё про Navidrome и волну хранит сервис Volna. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("volna", Context.MODE_PRIVATE)

    /** Адрес сервиса Volna. 10.0.2.2 — это компьютер, на котором запущен эмулятор Android. */
    var serviceUrl: String
        get() = prefs.getString("service_url", DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString("service_url", value.trim().trimEnd('/')).apply()

    /** Фон: live — живые линии, calm — спокойные, off — без линий. */
    var background: String
        get() = prefs.getString("background", "live") ?: "live"
        set(value) = prefs.edit().putString("background", value).apply()

    var colorCovers: Boolean
        get() = prefs.getBoolean("color_covers", false)
        set(value) = prefs.edit().putBoolean("color_covers", value).apply()

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8765"
    }
}
