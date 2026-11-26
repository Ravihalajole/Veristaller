package dev.veristaller.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ShellOutput(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val rendered: String,
    val durationMs: Long,
) {
    val ok: Boolean get() = code == 0

    fun combined(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trim())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(stderr.trim())
        }
    }
}

/**
 * One persistent root shell for the whole process, serialized with a mutex.
 * libsu keeps the su grant alive, so a job produces a single prompt.
 */
class RootShell {

    private val mutex = Mutex()

    @Volatile
    private var shell: Shell? = null

    /** Set by the job engine so every privileged command lands in the job log. */
    @Volatile
    var commandListener: ((ShellOutput) -> Unit)? = null

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Do not trust isAppGrantedRoot() before a shell exists: it is
            // "undetermined" until the su prompt has actually been answered.
            val sh = shell ?: Shell.getShell()
            if (sh.isRoot) {
                shell = sh
                true
            } else {
                // A cached non-root shell would make every retry fail; drop it
                // so the next refresh can ask the root manager again.
                runCatching { sh.waitAndClose(1, TimeUnit.SECONDS) }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun exec(script: ShellScript): ShellOutput = withContext(Dispatchers.IO) {
        mutex.withLock {
            val started = System.currentTimeMillis()
            val rendered = script.render()
            try {
                val sh = shell ?: acquire()
                val job = sh.newJob()
                job.add(rendered)
                val result = job.exec()
                val (stdout, code) = ShellParsing.exitCode(
                    result.out.joinToString("\n"),
                    result.code,
                )
                val output = ShellOutput(
                    code = code,
                    stdout = stdout,
                    stderr = result.err.joinToString("\n"),
                    rendered = rendered,
                    durationMs = System.currentTimeMillis() - started,
                )
                commandListener?.invoke(output)
                output
            } catch (t: Throwable) {
                // A dead su process would poison every later command; drop the
                // cached shell so the next attempt can re-acquire root.
                shell = null
                val output = ShellOutput(
                    code = -1,
                    stdout = "",
                    stderr = t.message ?: t.javaClass.simpleName,
                    rendered = rendered,
                    durationMs = System.currentTimeMillis() - started,
                )
                commandListener?.invoke(output)
                output
            }
        }
    }

    /** Drop the cached shell so the next command re-acquires root. */
    fun reset() {
        shell?.let { runCatching { it.waitAndClose(1, TimeUnit.SECONDS) } }
        shell = null
    }

    private fun acquire(): Shell {
        val sh = Shell.getShell()
        if (!sh.isRoot) {
            throw IllegalStateException("Root was denied")
        }
        shell = sh
        return sh
    }
}
