package dev.veristaller.data

import android.content.Context
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
data class JobRecord(
    val id: String,
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val action: String = ACTION_SWAP,
    val status: String = STATUS_SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val basePath: String? = null,
    val backupPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val logPath: String? = null,
) {
    companion object {
        const val ACTION_SWAP = "install+swap"
        const val ACTION_INSTALL = "install only"
        const val ACTION_RESTORE = "restore"
        const val STATUS_SUCCESS = "success"
        const val STATUS_ROLLED_BACK = "rolled_back"
        const val STATUS_FAILED = "failed"
        const val STATUS_DRY_RUN = "dry_run"
        const val STATUS_RESTORED = "restored"
    }
}

class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _records = MutableStateFlow<List<JobRecord>>(emptyList())
    val records: StateFlow<List<JobRecord>> = _records.asStateFlow()

    init {
        scope.launch { load() }
    }

    suspend fun add(record: JobRecord) {
        lock.withLock {
            _records.value = (listOf(record) + _records.value).take(MAX_RECORDS)
            persist()
        }
    }

    suspend fun clear() {
        lock.withLock {
            _records.value = emptyList()
            runCatching { file.delete() }
        }
    }

    suspend fun delete(record: JobRecord) {
        lock.withLock {
            _records.value = _records.value.filterNot { it.id == record.id }
            persist()
        }
    }

    private suspend fun load() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!file.exists()) return@withLock
                runCatching {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        _records.value = json.decodeFromString<List<JobRecord>>(text)
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
        private const val MAX_RECORDS = 200
    }
}
