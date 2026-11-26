package dev.veristaller.root

import android.content.Context
import com.topjohnwu.superuser.NoShellException
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
 * One persistent `su` shell for the whole process, serialized with a mutex.
 *
 * libsu's [Shell.getShell] is deliberately NOT used here: its default builder
 * silently falls back to a non-root `sh` if the first `su` does not report
 * uid 0, and then caches that shell for the process. That makes a root grant
 * which arrives later (KernelSU prompt answered after the first probe) look
 * like a permanent denial. We build `su` explicitly and verify the uid
 * ourselves, so a grant can always be picked up by resetting this shell.
 */
class RootShell(private val context: Context) {

    data class RootCheck(
        val uid: String? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = uid == "0"
    }

    private val mutex = Mutex()

    @Volatile
    private var shell: Shell? = null

    /** Set by the job engine so every privileged command lands in the job log. */
    @Volatile
    var commandListener: ((ShellOutput) -> Unit)? = null

    /** Acquire a fresh `su` shell and prove it is really uid 0. */
    suspend fun checkRoot(): RootCheck = withContext(Dispatchers.IO) {
        mutex.withLock {
            resetLocked()
            val started = System.currentTimeMillis()
            try {
                val sh = acquireLocked()
                val out = executeOn(sh, "id -u", started)
                val lines = out.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                // Some su implementations print a banner before the command
                // output, so accept any line that proves uid 0.
                val uid = lines.firstOrNull { it == "0" }
                    ?: if (lines.any { it.contains("uid=0") }) "0" else lines.firstOrNull()

                if (uid == "0") {
                    shell = sh
                    RootCheck(uid = uid)
                } else {
                    val detail = out.combined().ifBlank { "no output from id -u" }
                    runCatching { sh.waitAndClose(1, TimeUnit.SECONDS) }
                    RootCheck(
                        uid = uid,
                        error = "su returned uid ${uid ?: "unknown"} instead of 0 ($detail). " +
                            "In KernelSU, check the App Profile uses UID 0 and that " +
                            "${context.packageName} is in the allow list.",
                    )
                }
            } catch (t: Throwable) {
                val message = if (t is NoShellException) {
                    "Could not start su: ${t.message ?: "su was not found or did not respond"}. " +
                        "Allow ${context.packageName} in KernelSU, then tap the root chip to re-check."
                } else {
                    t.message ?: t.javaClass.simpleName
                }
                RootCheck(error = message)
            }
        }
    }

    suspend fun isRoot(): Boolean = checkRoot().ok

    suspend fun exec(script: ShellScript): ShellOutput = withContext(Dispatchers.IO) {
        val rendered = script.render()
        val started = System.currentTimeMillis()
        mutex.withLock {
            try {
                val sh = shell ?: acquireLocked().also { shell = it }
                executeOn(sh, rendered, started)
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
        resetLocked()
    }

    private fun resetLocked() {
        shell?.let { runCatching { it.waitAndClose(1, TimeUnit.SECONDS) } }
        shell = null
    }

    /**
     * `build("su")` deliberately bypasses libsu's default builder, which
     * falls back to `sh` and caches it. A failed su raises NoShellException
     * instead of quietly giving us an unprivileged shell.
     */
    private fun acquireLocked(): Shell = Shell.Builder.create()
        .setContext(context)
        .setTimeout(SHELL_TIMEOUT_SECONDS)
        .build("su")

    private fun executeOn(sh: Shell, rendered: String, started: Long): ShellOutput {
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
        return output
    }

    companion object {
        private const val SHELL_TIMEOUT_SECONDS = 20L
    }
}
