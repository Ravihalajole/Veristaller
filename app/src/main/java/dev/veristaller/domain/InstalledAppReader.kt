package dev.veristaller.domain

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.veristaller.domain.model.InstalledAppInfo

class InstalledAppReader(
    private val pm: PackageManager,
    private val apkInspector: ApkInspector,
) {

    fun read(packageName: String): InstalledAppInfo? {
        val info = try {
            pm.getPackageInfo(packageName, apkInspector.flags())
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val app = info.applicationInfo ?: return null
        val label = try {
            pm.getApplicationLabel(app).toString()
        } catch (t: Throwable) {
            packageName
        }
        val source = app.sourceDir ?: return null
        return InstalledAppInfo(
            packageName = info.packageName,
            versionCode = apkInspector.versionCode(info),
            versionName = info.versionName,
            label = label,
            codePath = source.substringBeforeLast('/'),
            baseApk = source,
            certSha256 = apkInspector.certSha256(info),
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    fun launchIntent(packageName: String) = pm.getLaunchIntentForPackage(packageName)
}
