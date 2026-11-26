package dev.veristaller.data

import android.content.Context
import android.os.Build
import dev.veristaller.domain.error.VeristallerException
import dev.veristaller.domain.model.ErrorCode
import dev.veristaller.domain.model.InstalledAppInfo
import dev.veristaller.root.RootOps
import dev.veristaller.root.RootShell
import dev.veristaller.root.ShellParsing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BackupRecord(
    val packageName: String,
    val versionCode: Long,
    val versionName: String? = null,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val originalPath: String = "",
    val sdk: Int = 0,
    val patchesApplied: List<String> = emptyList(),
    val owner: String = "1000:1000",
    val mode: String = "0644",
    val selinux: String = "u:object_r:apk_data_file:s0",
    val createdAt: Long = System.currentTimeMillis(),
)

class BackupRepository(
    context: Context,
    private val shell: RootShell,
) {

    private val file = File(context.filesDir, "backups.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _records = MutableStateFlow<List<BackupRecord>>(emptyList())
    val records: StateFlow<List<BackupRecord>> = _records.asStateFlow()

    init {
        scope.launch { load() }
    }

    suspend fun create(app: InstalledAppInfo, versionName: String?): BackupRecord {
        val stamp = System.currentTimeMillis()
        val dir = "$ROOT/${app.packageName}/${app.versionCode}-$stamp"
        val dest = "$dir/base.apk"

        val mkdir = shell.exec(RootOps.mkdirs(dir))
        if (!mkdir.ok) {
            throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Could not create the backup directory",
                mkdir.combined(),
            )
        }

        val copy = shell.exec(RootOps.copy(app.baseApk, dest))
        if (!copy.ok) {
            throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Could not copy base.apk to the backup",
                copy.combined(),
            )
        }

        val hashOut = shell.exec(RootOps.sha256Pair(app.baseApk, dest))
        val hashes = ShellParsing.sha256Pair(hashOut.stdout)
            ?: throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Could not read the backup checksum",
                hashOut.combined(),
            )
        if (hashes.first != hashes.second) {
            throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Backup checksum does not match the original",
                "expected ${hashes.first}\nactual   ${hashes.second}",
            )
        }

        val statOut = shell.exec(RootOps.stat(dest))
        val stat = ShellParsing.stat(statOut.stdout)
            ?: throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Could not read the backup size",
                statOut.combined(),
            )

        val record = BackupRecord(
            packageName = app.packageName,
            versionCode = app.versionCode,
            versionName = versionName ?: app.versionName,
            path = dest,
            sizeBytes = stat.size,
            sha256 = hashes.second,
            originalPath = app.baseApk,
            sdk = Build.VERSION.SDK_INT,
            patchesApplied = listOf("base.apk"),
            owner = stat.owner,
            mode = stat.mode,
            selinux = shell.exec(RootOps.context(app.baseApk)).stdout.trim()
                .ifBlank { "u:object_r:apk_data_file:s0" },
        )

        val meta = json.encodeToString(record)
        val metaWrite = shell.exec(RootOps.writeText("$dir/meta.json", meta))
        if (!metaWrite.ok) {
            throw VeristallerException(
                ErrorCode.E_BACKUP_FAILED,
                "Could not write backup metadata",
                metaWrite.combined(),
            )
        }

        lock.withLock {
            _records.value = listOf(record) + _records.value
            persist()
        }
        return record
    }

    suspend fun prune(packageName: String, keep: Int) {
        // 0 means "keep everything"; deletion is explicit in the Backups tab.
        if (keep <= 0) return
        val victims: List<BackupRecord> = lock.withLock {
            val forPackage = _records.value
                .filter { it.packageName == packageName }
                .sortedByDescending { it.createdAt }
            val drop = forPackage.drop(keep)
            if (drop.isEmpty()) return@withLock emptyList()
            _records.value = _records.value - drop.toSet()
            persist()
            drop
        }
        victims.forEach { victim ->
            runCatching {
                shell.exec(RootOps.removeTree(victim.path.substringBeforeLast('/')))
            }
        }
    }

    suspend fun delete(record: BackupRecord) {
        lock.withLock {
            _records.value = _records.value - record
            persist()
        }
        runCatching { shell.exec(RootOps.removeTree(record.path.substringBeforeLast('/'))) }
    }

    private suspend fun load() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!file.exists()) return@withLock
                runCatching {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        _records.value = json.decodeFromString<List<BackupRecord>>(text)
                    }
                }
                Unit
            }
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(_records.value))
        }
    }

    companion object {
        const val ROOT = "/data/local/tmp/veristaller/backups"
    }
}
