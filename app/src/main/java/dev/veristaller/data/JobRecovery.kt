package dev.veristaller.data

import android.content.Context
import java.io.File

/**
 * Small app-private marker written immediately before a base.apk swap and
 * removed once verification or rollback succeeds. If the process dies in that
 * window, Home can offer a verified restore on the next launch (FR-13.4).
 */
data class IncompleteJob(
    val jobId: String,
    val packageName: String,
    val basePath: String,
    val backupPath: String,
    val startedAt: Long,
)

class JobRecovery(context: Context) {
    private val file = File(context.filesDir, "job.inprogress")

    fun write(job: IncompleteJob) {
        runCatching {
            file.writeText(
                listOf(
                    "jobId" to job.jobId,
                    "packageName" to job.packageName,
                    "basePath" to job.basePath,
                    "backupPath" to job.backupPath,
                    "startedAt" to job.startedAt.toString(),
                ).joinToString("\n") { (k, v) -> "$k=$v" },
            )
        }
    }

    fun read(): IncompleteJob? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val values = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) values[line.substring(0, i)] = line.substring(i + 1)
        }
        val jobId = values["jobId"] ?: return null
        val pkg = values["packageName"] ?: return null
        val base = values["basePath"] ?: return null
        val backup = values["backupPath"] ?: return null
        return IncompleteJob(
            jobId = jobId,
            packageName = pkg,
            basePath = base,
            backupPath = backup,
            startedAt = values["startedAt"]?.toLongOrNull() ?: 0L,
        )
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }
}
