package dev.veristaller.domain

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.veristaller.domain.error.VeristallerException
import dev.veristaller.domain.model.ApkInfo
import dev.veristaller.domain.model.ErrorCode
import java.io.File
import java.security.MessageDigest

/**
 * Reads package metadata straight out of an APK file, no root and no aapt.
 */
class ApkInspector(private val pm: PackageManager) {

    fun inspect(file: File): ApkInfo {
        if (!file.exists() || file.length() == 0L) {
            throw VeristallerException(ErrorCode.E_BUNDLE_CORRUPT, "The selected file is empty")
        }
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags())
            ?: throw VeristallerException(
                ErrorCode.E_BUNDLE_CORRUPT,
                "Not a readable APK: ${file.name}",
            )
        val app = info.applicationInfo
        return ApkInfo(
            file = file,
            packageName = info.packageName,
            versionCode = versionCode(info),
            versionName = info.versionName,
            minSdk = app?.minSdkVersion ?: 0,
            targetSdk = app?.targetSdkVersion ?: 0,
            abis = abisFromApk(file),
            certSha256 = certSha256(info),
            sizeBytes = file.length(),
        )
    }

    fun flags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    fun certSha256(info: PackageInfo): String {
        val bytes = signerBytes(info) ?: return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { String.format("%02x", it) }
    }

    private fun abisFromApk(file: File): List<String> {
        val found = linkedSetOf<String>()
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.startsWith("lib/")) continue
                    val abi = name.removePrefix("lib/").substringBefore('/', missingDelimiterValue = "")
                    if (abi.isNotEmpty()) found += abi
                }
            }
            found.toList()
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun signerBytes(info: PackageInfo): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            return signers.firstOrNull()?.toByteArray()
        }
        return info.signatures?.firstOrNull()?.toByteArray()
    }
}
