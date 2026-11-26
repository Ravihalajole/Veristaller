package dev.veristaller.domain

import dev.veristaller.BuildConfig
import dev.veristaller.domain.model.ApkInfo
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.CheckResult
import dev.veristaller.domain.model.CheckResult.Severity
import dev.veristaller.domain.model.InstalledAppInfo
import dev.veristaller.domain.model.JobOptions
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.root.DeviceFacts

/**
 * Pure verification pass. No side effects: everything here is derived from
 * facts that were already collected, so it is safe to run on every keystroke.
 */
object Preflight {

    private const val HEADROOM = 200L * 1024 * 1024

    private val blockedPackages = setOf(
        "android",
        "com.android.settings",
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "me.bmax.apatch",
    )

    fun evaluate(
        bundle: BundleInfo?,
        patch: ApkInfo?,
        installed: InstalledAppInfo?,
        facts: DeviceFacts?,
        isRunning: Boolean,
        options: JobOptions,
        splits: List<SplitApk>,
    ): List<CheckResult> {
        val results = ArrayList<CheckResult>(12)

        val root = facts?.isRoot == true
        results += check(
            id = "root",
            ok = root,
            title = if (root) "Root granted" else "Root not granted",
            detail = if (root) "uid 0 · ${facts?.manager.orEmpty()}" else "Veristaller needs root to touch /data/app",
        )

        val missing = facts?.missingTools.orEmpty()
        results += check(
            id = "tools",
            ok = missing.isEmpty(),
            title = if (missing.isEmpty()) "Required tools present" else "Missing tools",
            detail = if (missing.isEmpty()) "pm · stat · chcon · restorecon" else missing.joinToString(", "),
        )

        results += check(
            id = "bundle",
            ok = bundle != null && bundle.packageName != null && bundle.splits.isNotEmpty(),
            title = if (bundle == null) "No bundle selected" else "Bundle parsed",
            detail = bundle?.let { "${it.format.label} · ${it.splits.size} splits · ${it.totalBytes / 1024 / 1024} MB" }
                ?: "Pick an .apks, .apkm, .xapk or .apk",
        )

        results += check(
            id = "patch",
            ok = patch != null,
            title = if (patch == null) "No patched APK selected" else "Patched APK parsed",
            detail = patch?.let { "${it.versionName ?: "?"} (${it.versionCode}) · ${it.sizeBytes / 1024 / 1024} MB" }
                ?: "Pick the replacement base.apk",
        )

        val namesMatch = bundle?.packageName != null && patch != null &&
            bundle.packageName == patch.packageName
        results += check(
            id = "pkg",
            ok = namesMatch,
            title = if (namesMatch) "Package names match" else "Package names differ",
            detail = "${bundle?.packageName ?: "?"} vs ${patch?.packageName ?: "?"}",
        )

        val bundleVersionCode = bundle?.versionCode
        val patchVersionCode = patch?.versionCode
        val versions = bundleVersionCode != null && patchVersionCode != null
        val sameVersion = versions && bundleVersionCode == patchVersionCode
        if (versions && !sameVersion) {
            val downgrade = (bundleVersionCode ?: 0L) > (patchVersionCode ?: 0L)
            results += CheckResult(
                id = "version",
                severity = Severity.WARN,
                title = if (downgrade) "Patch is an older version" else "Patch is a newer version",
                detail = "bundle $bundleVersionCode · patch $patchVersionCode",
            )
        } else {
            results += check(
                id = "version",
                ok = sameVersion,
                title = if (sameVersion) "Version codes match" else "Version unknown",
                detail = if (sameVersion) "${patch?.versionCode}" else "Could not read both versions",
            )
        }

        results += check(
            id = "installed",
            ok = installed != null,
            title = if (installed == null) "App is not installed" else "App is installed",
            detail = installed?.let { "${it.versionName ?: "?"} (${it.versionCode})" }
                ?: "Install the bundle first, then swap",
        )

        // PackageManager rejects a lower versionCode, so stop before touching anything.
        if (installed != null && bundleVersionCode != null &&
            installed.versionCode > bundleVersionCode
        ) {
            results += CheckResult(
                id = "downgrade",
                severity = Severity.FAIL,
                title = "Installed app is newer than the bundle",
                detail = "installed ${installed.versionCode} · bundle $bundleVersionCode — uninstall first",
                requiresAck = false,
            )
        }

        val certsKnown = installed != null && patch != null &&
            installed.certSha256.isNotEmpty() && patch.certSha256.isNotEmpty()
        val sameCert = certsKnown && installed?.certSha256 == patch?.certSha256
        if (certsKnown && !sameCert) {
            results += CheckResult(
                id = "signature",
                severity = Severity.WARN,
                title = "Signing key differs",
                detail = "Installed ${installed?.certSha256?.take(8)} · patch ${patch?.certSha256?.take(8)}. " +
                    "The installed splits keep their own signing key; a differently signed base " +
                    "may be rejected on reboot.",
            )
        } else {
            results += check(
                id = "signature",
                ok = sameCert,
                title = if (sameCert) "Signing key matches" else "Signing key unknown",
                detail = if (sameCert) "base.apk is signed by the installed key" else "No certificate data",
            )
        }

        val supportedAbis = facts?.abiList.orEmpty()
        val abiOk = patch == null || patch.abis.isEmpty() ||
            patch.abis.any { it in supportedAbis }
        if (!abiOk) {
            results += CheckResult(
                id = "abi",
                severity = Severity.WARN,
                title = "Patch targets another CPU",
                detail = "${patch?.abis?.joinToString()} vs ${supportedAbis.joinToString()}",
            )
        } else {
            results += check(
                id = "abi",
                ok = true,
                title = "ABI compatible",
                detail = patch?.abis?.firstOrNull() ?: supportedAbis.firstOrNull() ?: "universal",
            )
        }

        val needed = (bundle?.totalBytes ?: 0L) + (patch?.sizeBytes ?: 0L) + HEADROOM
        val free = facts?.freeBytes ?: 0L
        results += check(
            id = "space",
            ok = free >= needed,
            title = if (free >= needed) "Enough free space" else "Not enough free space",
            detail = "${formatGb(free)} free · ${formatGb(needed)} needed",
        )

        val pkg = patch?.packageName.orEmpty()
        val hardBlocked = pkg == BuildConfig.APPLICATION_ID ||
            pkg in blockedPackages ||
            (installed?.isSystem == true && installed.baseApk.startsWith("/system"))
        if (hardBlocked) {
            results += CheckResult(
                id = "safe",
                severity = Severity.FAIL,
                title = "Refusing this target",
                detail = "System, self or root-manager package — $pkg",
                requiresAck = false,
            )
        } else if (pkg.startsWith("com.android.")) {
            // Non-core system components are allowed only with an explicit ack.
            results += CheckResult(
                id = "safe",
                severity = Severity.WARN,
                title = "System component selected",
                detail = "$pkg is part of the system image. Only continue if you know what it does.",
            )
        } else {
            results += check(
                id = "safe",
                ok = true,
                title = "Target is safe to modify",
                detail = pkg,
            )
        }

        results += check(
            id = "running",
            ok = true,
            title = if (isRunning) "App is running" else "App is stopped",
            detail = if (isRunning) "It will be force-stopped before the swap" else "No process found",
        )

        val splitsOk = splits.isNotEmpty()
        results += check(
            id = "splits",
            ok = splitsOk,
            title = if (splitsOk) "${splits.size} splits selected" else "No compatible splits",
            detail = if (splitsOk) {
                "${splits.count { it.abi != null }} ABI · ${splits.count { it.density != null }} density · mode ${options.splitMode.label}"
            } else {
                "The bundle has no split this device can install"
            },
        )

        return results
    }

    private fun check(id: String, ok: Boolean, title: String, detail: String): CheckResult =
        CheckResult(
            id = id,
            severity = if (ok) Severity.PASS else Severity.FAIL,
            title = title,
            detail = detail,
            requiresAck = false,
        )

    private fun formatGb(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.1f GB", gb) else "${bytes / 1024 / 1024} MB"
    }
}
