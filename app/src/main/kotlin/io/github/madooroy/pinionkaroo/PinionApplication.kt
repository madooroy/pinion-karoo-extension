package io.github.madooroy.pinionkaroo

import android.app.Application
import timber.log.Timber

class PinionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Everything is logged under the single tag "Pinion": adb logcat -s Pinion
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, "Pinion", message, t)
            }
        })
    }
}
