package app.volna.data

import android.content.Context

/** Настройки самого приложения. Всё про Navidrome и волну хранит сервис Volna. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("volna", Context.MODE_PRIVATE)

    /** Адрес сервиса Volna, который нашёлся при входе, например https://music.example.com/volna. */
    var serviceUrl: String
        get() = prefs.getString("service_url", DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString("service_url", value.trim().trimEnd('/')).apply()

    /** Адрес так, как его ввёл человек: подставляем в форму входа. */
    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(value) = prefs.edit().putString("address", value.trim()).apply()

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(value) = prefs.edit().putString("user", value).apply()

    /** Токен входа: сервис выдаёт его в обмен на логин и пароль Navidrome. */
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    /** Вход уже был: иначе при запуске показываем экран входа. */
    var connected: Boolean
        get() = prefs.getBoolean("connected", false)
        set(value) = prefs.edit().putBoolean("connected", value).apply()

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
