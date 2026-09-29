package hk.eduhk.typeduck

import android.app.Application
import android.os.Process
import androidx.preference.PreferenceManager
import cat.ereza.customactivityoncrash.config.CaocConfig
import hk.eduhk.typeduck.data.AppPrefs
import hk.eduhk.typeduck.data.db.ClipboardHelper
import hk.eduhk.typeduck.data.db.CollectionHelper
import hk.eduhk.typeduck.data.db.DraftHelper
import hk.eduhk.typeduck.util.DiagnosticLogger
import timber.log.Timber

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid
    }

    override fun onCreate() {
        super.onCreate()
        CaocConfig.Builder
            .create()
            .enabled(!BuildConfig.DEBUG)
            .apply()
        instance = this
        DiagnosticLogger.init(this)
        DiagnosticLogger.event("TrimeApplication.onCreate started")
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            AppPrefs.initDefault(sharedPreferences).apply {
                initDefaultPreferences()
            }
            // record last pid for crash logs
            val appPrefs = AppPrefs.defaultInstance()
            val currentPid = Process.myPid()
            appPrefs.internal.pid.apply {
                lastPid = this
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            }
            appPrefs.internal.pid = currentPid
            ClipboardHelper.init(applicationContext)
            CollectionHelper.init(applicationContext)
            DraftHelper.init(applicationContext)
            DiagnosticLogger.event("TrimeApplication.onCreate completed")
        } catch (e: Exception) {
            Timber.e(e, "TrimeApplication initialization failed")
            DiagnosticLogger.event("TrimeApplication.onCreate failed: %s", e.javaClass.name)
            return
        }
    }
}
