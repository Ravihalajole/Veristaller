package dev.veristaller.domain

import dev.veristaller.domain.error.VeristallerException
import dev.veristaller.domain.model.BundleFormat
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.ErrorCode
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.domain.model.SplitMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads a bundle container without extracting it. Only the base/master split
 * is extracted, temporarily, to read its manifest for package + version.
 */
class BundleInspector(
    private val apkInspector: ApkInspector,
    private val tempDir: File,
) {

    fun inspect(file: File, abis: List<String>, densityBucket: String): BundleInfo {
        if (!file.exists() || file.length() == 0L) {
            throw VeristallerException(ErrorCode.E_BUNDLE_CORRUPT, "The bundle file is empty")
        }
        val zip = try {
            ZipFile(file)
        } catch (t: Throwable) {
            throw VeristallerException(
                ErrorCode.E_BUNDLE_CORRUPT,
                "Not a zip container: ${file.name}",
                cause = t,
            )
        }

        return zip.use { z ->
            val names = ArrayList<String>()
            val sizes = HashMap<String, Long>()
            var total = 0L
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                if (entry.isDirectory) continue
                names.add(entry.name)
                val size = entry.size.coerceAtLeast(0L)
                sizes[entry.name] = size
                total += size
                if (total > MAX_UNCOMPRESSED) {
                    throw VeristallerException(
                        ErrorCode.E_BUNDLE_CORRUPT,
                        "Archive expands to more than 4 GB",
                    )
                }
            }

            val apkEntries = names.filter { it.endsWith(".apk", ignoreCase = true) }
            val format = detect(names, apkEntries)

            if (format == BundleFormat.SINGLE_APK) {
                val info = apkInspector.inspect(file)
                val split = SplitApk(
                    entryName = "",
                    fileName = file.name,
                    sizeBytes = file.length(),
                    isMaster = true,
                )
                return@use BundleInfo(
                    file = file,
                    format = format,
                    packageName = info.packageName,
                    versionCode = info.versionCode,
                    versionName = info.versionName,
                    splits = listOf(split),
                )
            }

            if (format == BundleFormat.UNKNOWN) {
                throw VeristallerException(
                    ErrorCode.E_BUNDLE_UNSUPPORTED,
                    "No toc.pb, meta.json or manifest.json and no APKs inside",
                )
            }

            val splitEntries = splitEntries(format, apkEntries)
            if (splitEntries.isEmpty()) {
                throw VeristallerException(
                    ErrorCode.E_BUNDLE_CORRUPT,
                    "This bundle contains no APK splits",
                )
            }

            val primary = primaryEntry(splitEntries, sizes)
            val splits = splitEntries.map { entry ->
                splitOf(entry, sizes[entry] ?: 0L, entry == primary)
            }
            val meta = primary?.let { readMeta(z, it) }

            val ignored = apkEntries.filter { it !in splitEntries } +
                names.filter { it == "toc.pb" || it == "meta.json" || it == "manifest.json" || it == "icon.png" }

            BundleInfo(
                file = file,
                format = format,
                packageName = meta?.packageName,
                versionCode = meta?.versionCode,
                versionName = meta?.versionName,
                splits = splits,
                ignoredEntries = ignored.distinct().take(20),
            )
        }
    }

    fun select(
        bundle: BundleInfo,
        abis: List<String>,
        densityBucket: String,
        mode: SplitMode,
        locales: Set<String> = emptySet(),
    ): List<SplitApk> {
        val wantedLocales = locales.map { it.lowercase() }.toSet() + "en"
        return bundle.splits.filter { split ->
            when {
                split.isMaster -> true
                mode == SplitMode.ALL -> true
                split.abi != null -> split.abi in abis
                mode == SplitMode.ABI_ONLY -> true
                split.density != null -> densityMatches(split.density, densityBucket)
                split.language != null -> split.language.lowercase() in wantedLocales
                else -> true
            }
        }
    }

    private fun densityMatches(splitDensity: String, deviceBucket: String): Boolean {
        if (splitDensity == "nodpi" || splitDensity == "anydpi") return true
        val a = DENSITY_ORDER.indexOf(splitDensity)
        val b = DENSITY_ORDER.indexOf(deviceBucket)
        if (a < 0 || b < 0) return splitDensity == deviceBucket
        return kotlin.math.abs(a - b) <= 1
    }

    private fun detect(names: List<String>, apkEntries: List<String>): BundleFormat = when {
        names.contains("toc.pb") -> BundleFormat.APKS
        names.contains("meta.json") -> BundleFormat.APKM
        names.contains("manifest.json") -> BundleFormat.XAPK
        names.contains("AndroidManifest.xml") -> BundleFormat.SINGLE_APK
        apkEntries.isNotEmpty() -> BundleFormat.XAPK
        else -> BundleFormat.UNKNOWN
    }

    private fun splitEntries(format: BundleFormat, apkEntries: List<String>): List<String> {
        val fromSplits = apkEntries.filter { it.startsWith("splits/") }
        if (fromSplits.isNotEmpty()) return fromSplits
        return when (format) {
            BundleFormat.APKS -> when {
                apkEntries.any { it == "universal.apk" } ->
                    apkEntries.filter { it == "universal.apk" }
                else -> apkEntries.filter { it.startsWith("standalones/") }
            }
            else -> {
                // Never mix standalone full APKs with a real split set.
                val regular = apkEntries.filterNot { it.startsWith("standalones/") }
                regular.ifEmpty { apkEntries.filter { it.startsWith("standalones/") } }
            }
        }
    }

    private fun primaryEntry(entries: List<String>, sizes: Map<String, Long>): String? =
        entries.firstOrNull { it.endsWith("base-master.apk") }
            ?: entries.firstOrNull { it == "splits/base.apk" }
            ?: entries.firstOrNull { it.substringAfterLast('/') == "base.apk" }
            ?: entries.firstOrNull { it.contains("master", ignoreCase = true) }
            ?: entries.maxByOrNull { sizes[it] ?: 0L }

    private fun readMeta(zip: ZipFile, entryName: String) = runCatching {
        tempDir.mkdirs()
        val temp = File(tempDir, "meta-${System.nanoTime()}.apk")
        try {
            zip.getInputStream(zip.getEntry(entryName)).use { input ->
                temp.outputStream().use { output -> input.copyTo(output, BUFFER) }
            }
            apkInspector.inspect(temp)
        } finally {
            temp.delete()
        }
    }.getOrNull()

    private fun splitOf(entryName: String, size: Long, primary: Boolean): SplitApk {
        val fileName = entryName.substringAfterLast('/')
        val stem = fileName.removeSuffix(".apk").lowercase()
        val abi = ABI_TOKENS.firstOrNull { stem.contains(it.first) }?.second
        val density = DENSITY_TOKENS.firstOrNull { stem.contains(it) }
        val language = if (abi == null && density == null) languageOf(stem) else null
        val master = primary || stem.contains("master") || stem == "base"
        return SplitApk(
            entryName = entryName,
            fileName = fileName,
            sizeBytes = size,
            abi = abi,
            density = density,
            language = language,
            isMaster = master,
        )
    }

    private fun languageOf(stem: String): String? {
        if (stem.contains("b+")) {
            return stem.substringAfter("b+").substringBefore('+').takeIf { it.length in 2..3 }
        }
        val last = stem.split('-', '.').lastOrNull() ?: return null
        return last.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
    }

    companion object {
        private const val BUFFER = 1 shl 16
        private const val MAX_UNCOMPRESSED = 4L * 1024 * 1024 * 1024

        // Archive token -> canonical ABI name as reported by ro.product.cpu.abilist.
        // Keep the longer tokens first so "x86_64" is not read as "x86".
        private val ABI_TOKENS = listOf(
            "arm64_v8a" to "arm64-v8a",
            "armeabi_v7a" to "armeabi-v7a",
            "x86_64" to "x86_64",
            "riscv64" to "riscv64",
            "mips64" to "mips64",
            "armeabi" to "armeabi",
            "x86" to "x86",
            "mips" to "mips",
        )

        private val DENSITY_TOKENS = listOf(
            "xxxhdpi",
            "xxhdpi",
            "xhdpi",
            "tvdpi",
            "hdpi",
            "mdpi",
            "ldpi",
            "nodpi",
            "anydpi",
        )

        // Ordered from least to greatest density; tvdpi sits between hdpi and xhdpi.
        private val DENSITY_ORDER = listOf(
            "ldpi", "mdpi", "hdpi", "tvdpi", "xhdpi", "xxhdpi", "xxxhdpi",
        )

        fun densityBucket(dpi: Int): String = when {
            dpi >= 560 -> "xxxhdpi"
            dpi >= 400 -> "xxhdpi"
            dpi >= 280 -> "xhdpi"
            dpi >= 200 -> "hdpi"
            dpi >= 140 -> "mdpi"
            else -> "ldpi"
        }
    }
}
