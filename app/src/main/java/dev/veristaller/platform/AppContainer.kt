package dev.veristaller.platform

import android.content.Context
import dev.veristaller.data.SettingsRepository
import dev.veristaller.data.StagingStore
import dev.veristaller.domain.ApkInspector
import dev.veristaller.domain.BundleInspector
import dev.veristaller.domain.InstalledAppReader
import dev.veristaller.job.JobRunner
import dev.veristaller.root.RootProbe
import dev.veristaller.root.RootShell
import java.io.File

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    val context: Context get() = appContext

    val rootShell = RootShell(appContext)
    val probe = RootProbe(appContext, rootShell)
    val apkInspector = ApkInspector(appContext.packageManager)
    val installedReader = InstalledAppReader(appContext.packageManager, apkInspector)
    val bundleInspector = BundleInspector(apkInspector, File(appContext.cacheDir, "inspect"))
    val staging = StagingStore(appContext)
    val settings = SettingsRepository(appContext)

    val jobRunner = JobRunner(
        context = appContext,
        shell = rootShell,
        staging = staging,
        bundleInspector = bundleInspector,
    )

    val densityBucket: String =
        BundleInspector.densityBucket(appContext.resources.displayMetrics.densityDpi)

    val deviceLocales: Set<String> = buildSet {
        val locales = appContext.resources.configuration.locales
        for (i in 0 until locales.size()) add(locales[i].language.lowercase())
        add("en")
    }
}
