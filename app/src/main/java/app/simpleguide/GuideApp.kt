package app.simpleguide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.simpleguide.data.Repository
import app.simpleguide.data.SettingsStore
import app.simpleguide.tour.TourService
import app.simpleguide.tts.ModelManager
import app.simpleguide.tts.TtsManager
import app.simpleguide.util.AppLanguage

/** Einfache Abhängigkeits-Sammlung (kein DI-Framework nötig). */
class GuideApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var models: ModelManager
        private set
    lateinit var tts: TtsManager
        private set
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        models = ModelManager(this)
        tts = TtsManager(models)
        repo = Repository(this)
        createChannels(this)
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "download"

        /** Legt die Kanäle an oder benennt sie nach einem Sprachwechsel um. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val res = AppLanguage.wrap(context)
            nm.createNotificationChannel(NotificationChannel(TourService.CHANNEL, res.getString(R.string.channel_tour), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOAD, res.getString(R.string.channel_download), NotificationManager.IMPORTANCE_LOW))
        }
    }
}
