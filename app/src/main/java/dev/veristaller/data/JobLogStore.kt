package dev.veristaller.data

import android.content.Context
import java.io.File

class JobLogStore(context: Context, jobId: String) {

    private val file = File(
        File(context.filesDir, "logs").apply { mkdirs() },
        "$jobId.log",
    )

    fun append(line: String, time: Long = System.currentTimeMillis()) {
        runCatching { file.appendText("$time  $line\n") }
    }

    fun path(): String = file.absolutePath
}
