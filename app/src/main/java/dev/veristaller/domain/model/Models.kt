package dev.veristaller.domain.model

import java.io.File

enum class BundleFormat(val label: String) {
    APKS("bundletool"),
    APKM("APKMirror"),
    XAPK("XAPK"),
    SINGLE_APK("single APK"),
    UNKNOWN("unknown"),
}

data class SplitApk(
    val entryName: String,
    val fileName: String,
    val sizeBytes: Long,
    val abi: String? = null,
    val density: String? = null,
    val language: String? = null,
    val isMaster: Boolean = false,
) {
    val qualifier: String
        get() = when {
            isMaster -> "master"
            abi != null -> abi
            density != null -> density
            language != null -> language
            else -> "extra"
        }
}

data class BundleInfo(
    val file: File,
    val format: BundleFormat,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val splits: List<SplitApk>,
    val ignoredEntries: List<String> = emptyList(),
) {
    val totalBytes: Long get() = splits.sumOf { it.sizeBytes }
}

data class ApkInfo(
    val file: File,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val certSha256: String,
    val sizeBytes: Long,
) {
    val certShort: String get() = certSha256.take(16)
}

data class InstalledAppInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val label: String,
    val codePath: String,
    val baseApk: String,
    val certSha256: String,
    val isSystem: Boolean,
)

data class ResolvedPath(
    val directory: String,
    val baseApk: String,
)

enum class StepId(val label: String) {
    STAGE("Stage"),
    INSTALL("Install"),
    LOCATE("Locate"),
    BACKUP("Backup"),
    SWAP("Swap"),
    FIXUP("Fix"),
    VERIFY("Verify"),
    SMOKE("Smoke test"),
}

enum class SplitMode(val label: String) {
    SMART("Smart"),
    ABI_ONLY("ABI only"),
    ALL("All"),
}

data class JobOptions(
    val installerPackage: String = "com.android.vending",
    val clearOat: Boolean = true,
    val dryRun: Boolean = false,
    val keepBackups: Int = 3,
    val splitMode: SplitMode = SplitMode.SMART,
    val verboseLogs: Boolean = false,
    val smokeTest: Boolean = false,
)

data class JobSpec(
    val jobId: String,
    val bundle: BundleInfo,
    val patch: ApkInfo,
    val installed: InstalledAppInfo,
    val options: JobOptions,
    val deviceAbis: List<String>,
    val densityBucket: String,
    val packageLabel: String,
    val deviceLocales: Set<String> = emptySet(),
    val acknowledgedWarnings: Set<String> = emptySet(),
    val requiredWarnings: Set<String> = emptySet(),
)

data class CheckResult(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val requiresAck: Boolean = severity == Severity.WARN,
) {
    enum class Severity { PASS, WARN, FAIL }
}

data class SwapDetails(
    val basePath: String,
    val owner: String,
    val mode: String,
    val selinux: String,
    val sizeBytes: Long,
    val backupPath: String?,
)

data class JobError(
    val code: ErrorCode,
    val message: String,
    val raw: String? = null,
    val step: String? = null,
    val exitCode: Int? = null,
    val jobId: String? = null,
    val hint: String = code.hint,
)

data class JobLogLine(
    val text: String,
    val kind: Kind = Kind.NORMAL,
    val time: Long = System.currentTimeMillis(),
) {
    enum class Kind { NORMAL, DIM, OK, WARN, ERR }
}
