package app.volna

import android.app.Application
import app.volna.data.Prefs
import app.volna.data.VolnaApi
import app.volna.player.VolnaPlayer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

class VolnaApp : Application(), SingletonImageLoader.Factory {
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

    /** Обложки идут через сервис Volna, поэтому Coil ходит тем же клиентом, с токеном входа. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { api.client })) }.build()
}
