package dev.veristaller.root

import android.content.Context

/** A root manager app Veristaller can name in its diagnostics. */
data class RootManager(val name: String, val packageName: String)

/**
 * Which root manager is installed.
 *
 * This matters because KernelSU does not ship a real `su` binary: its
 * sucompat hook intercepts `execve("/system/bin/su")` and, when the calling
 * UID is not in the allowlist, skips the hook. The real exec then fails with
 * ENOENT - the exact same error as a device with no root at all. Naming the
 * installed manager is the only way the app can tell the user "you are rooted,
 * this app is just not allowed" instead of "no su binary".
 *
 * `QUERY_ALL_PACKAGES` is declared in the manifest, so this works on Android 11+.
 */
object RootManagers {

    private val KNOWN = listOf(
        RootManager("KernelSU", "me.weishu.kernelsu"),
        RootManager("KernelSU Next", "com.rifsxd.ksunext"),
        RootManager("SukiSU Ultra", "com.sukisu.ultra"),
        RootManager("Magisk", "com.topjohnwu.magisk"),
        RootManager("Magisk Delta", "io.github.huskydg.magisk"),
        RootManager("APatch", "me.bmax.apatch"),
    )

    fun installed(context: Context): List<RootManager> {
        val pm = context.packageManager
        return KNOWN.filter { manager ->
            runCatching { pm.getPackageInfo(manager.packageName, 0) }.isSuccess
        }
    }

    fun names(context: Context): List<String> = installed(context).map { it.name }
}
