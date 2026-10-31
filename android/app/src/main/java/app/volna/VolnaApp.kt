package app.volna

import android.app.Application
import app.volna.data.Prefs
import app.volna.data.VolnaApi
import app.volna.player.VolnaPlayer

class VolnaApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var api: VolnaApi
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = VolnaApi(prefs)
        VolnaPlayer.init(this, api)
    }
}
