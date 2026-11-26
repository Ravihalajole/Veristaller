package dev.veristaller.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.veristaller.domain.error.VeristallerException
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.ErrorCode
import dev.veristaller.domain.model.SplitApk
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies SAF selections into app-private staging (root can still read them),
 * and extracts the chosen split APKs for the pm session.
 */
class StagingStore(private val context: Context) {

    data class StagedFile(val file: File, val displayName: String)

    fun import(uri: Uri): StagedFile {
        val displayName = queryName(uri) ?: "picked-${System.currentTimeMillis()}.bin"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-$safeName")

        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        } ?: throw VeristallerException(
            ErrorCode.E_BUNDLE_CORRUPT,
            "Could not open the selected file",
        )

        try {
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, BUFFER) }
            }
        } catch (t: Throwable) {
            target.delete()
            throw VeristallerException(
                ErrorCode.E_BUNDLE_CORRUPT,
                "Could not copy the selected file",
                cause = t,
            )
        }

        if (target.length() == 0L) {
            target.delete()
            throw VeristallerException(ErrorCode.E_BUNDLE_CORRUPT, "The selected file is empty")
        }
        return StagedFile(target, displayName)
    }

    fun newJobDir(jobId: String): File {
        val dir = File(File(context.cacheDir, "stage"), jobId)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    fun extractSplits(
        bundle: BundleInfo,
        splits: List<SplitApk>,
        destination: File,
    ): List<File> {
        destination.mkdirs()
        val written = ArrayList<File>(splits.size)
        // A single APK is the container itself; there is no entry to read.
        if (splits.all { it.entryName.isEmpty() }) {
            splits.forEach { split ->
                val target = File(destination, split.fileName)
                bundle.file.copyTo(target, overwrite = true)
                written += target
            }
            return written
        }
        ZipFile(bundle.file).use { zip ->
            splits.forEach { split ->
                if (split.entryName.startsWith("/") || split.entryName.startsWith("\\") ||
                    split.entryName.contains("..") || split.entryName.contains('\\')
                ) {
                    throw VeristallerException(
                        ErrorCode.E_BUNDLE_CORRUPT,
                        "Unsafe archive entry: ${split.entryName}",
                    )
                }
                val entry = zip.getEntry(split.entryName)
                    ?: throw VeristallerException(
                        ErrorCode.E_BUNDLE_CORRUPT,
                        "Missing split in archive: ${split.fileName}",
                    )
                if (!SAFE_NAME.matches(split.fileName)) {
                    throw VeristallerException(
                        ErrorCode.E_BUNDLE_CORRUPT,
                        "Unsafe split name: ${split.fileName}",
                    )
                }
                val target = File(destination, split.fileName)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, BUFFER) }
                }
                if (entry.size > 0 && target.length() != entry.size) {
                    target.delete()
                    throw VeristallerException(
                        ErrorCode.E_BUNDLE_CORRUPT,
                        "Split ${split.fileName} was truncated",
                    )
                }
                written += target
            }
        }
        return written
    }

    fun deleteJobDir(jobId: String) {
        runCatching { File(File(context.cacheDir, "stage"), jobId).deleteRecursively() }
    }

    fun cleanupOld() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        runCatching {
            File(context.cacheDir, "imports").listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
            File(context.cacheDir, "stage").listFiles()?.forEach { dir ->
                if (dir.lastModified() < cutoff) dir.deleteRecursively()
            }
            File(context.cacheDir, "inspect").listFiles()?.forEach { it.delete() }
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        private const val BUFFER = 1 shl 16
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$", RegexOption.IGNORE_CASE)
    }
}
