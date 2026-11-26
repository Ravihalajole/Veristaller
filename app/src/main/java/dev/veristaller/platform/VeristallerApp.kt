package dev.veristaller.platform

import android.app.Application
import com.topjohnwu.superuser.Shell

class VeristallerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Always ask for `su` explicitly. libsu's default builder falls back to
        // a non-root `sh` after one denial and remembers that state for the
        // process, which makes KernelSU grants show up only after a restart.
        Shell.setDefaultBuilder(Shell.Builder.create().setCommands("su").setTimeout(20))
        container = AppContainer(this)
        container.staging.cleanupOld()
    }
}
