package dev.veristaller.platform

import android.app.Application

class VeristallerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.staging.cleanupOld()
    }
}
