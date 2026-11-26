package dev.veristaller.root

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
            var lastError = "No su binary was available"
            val candidates = suCommands()

            for (command in candidates) {
                try {
                    val sh = buildShell(command)
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
                        return@withLock RootCheck(uid = uid)
                    }

                    val detail = out.combined().ifBlank { "no output from id -u" }
                    lastError = "su ($command) returned uid ${uid ?: "unknown"} instead of 0: $detail"
                    runCatching { sh.waitAndClose(1, TimeUnit.SECONDS) }
                } catch (t: Throwable) {
                    lastError = describeFailure(t, command)
                    // A pending su prompt needs user action, not another path.
                    if (lastError.contains("timeout", ignoreCase = true)) break
                }
            }

            RootCheck(
                error = lastError + ". " + suDiagnostics() +
                    ". Allow ${context.packageName} in KernelSU, then tap the root chip to re-check.",
            )
        }
    }

    suspend fun isRoot(): Boolean = checkRoot().ok

    suspend fun exec(script: ShellScript): ShellOutput = withContext(Dispatchers.IO) {
        val rendered = script.render()
        val started = System.currentTimeMillis()
        mutex.withLock {
            try {
                val sh = shell ?: buildShell(suCommands().first()).also { shell = it }
                executeOn(sh, rendered, started)
            } catch (t: Throwable) {
                // A dead su process would poison every later command; drop the
                // cached shell so the next attempt can re-acquire root.
                shell = null
                val output = ShellOutput(
                    code = -1,
                    stdout = "",
                    stderr = describeFailure(t, suCommands().first()),
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
     * Build libsu's shell around a Process WE start, so we control the PATH.
     * `build("su")` alone uses the app's inherited PATH, which often omits
     * KernelSU's `/data/adb/ksu/bin`. Passing the process also bypasses
     * libsu's default builder, which would fall back to a non-root `sh`.
     */
    private fun buildShell(command: String): Shell {
        val process = ProcessBuilder(command)
            .apply { environment()["PATH"] = rootPath() }
            .start()
        return Shell.Builder.create()
            .setContext(context)
            .setTimeout(SHELL_TIMEOUT_SECONDS)
            .build(process)
    }

    /** Standard Android dirs plus the su locations used by root managers. */
    private fun rootPath(): String = buildList {
        add("/system/bin")
        add("/system/xbin")
        add("/data/adb/ksu/bin")
        add("/data/adb/ap/bin")
        add("/sbin")
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { add(it) }
    }.distinct().joinToString(File.pathSeparator)

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

    /**
     * KernelSU/APatch expose su at non-standard paths, and a normal app's PATH
     * may not include them. Try the usual locations after plain `su`.
     */
    private fun suCommands(): List<String> = buildList {
        add("su")
        for (path in KNOWN_SU_PATHS) {
            if (File(path).canExecute()) add(path)
        }
    }

    private fun suDiagnostics(): String {
        val path = System.getenv("PATH").orEmpty()
        val onPath = path.split(File.pathSeparator)
            .map { if (it.isBlank()) "." else it }
            .filter { File(it, "su").canExecute() }
        val present = KNOWN_SU_PATHS.filter { File(it).canExecute() }
        return "app PATH=$path; su on app PATH=${onPath.ifEmpty { "none" }}; " +
            "known su paths present=${present.ifEmpty { "none" }}; root PATH used=${rootPath()}"
    }

    private fun describeFailure(t: Throwable, command: String): String {
        val cause = generateSequence(t.cause) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
        val detail = listOfNotNull(t.message, cause?.let { "cause: $it" })
            .distinct()
            .joinToString(" · ")
            .ifBlank { t.javaClass.simpleName }
        return "Could not start $command: $detail"
    }

    companion object {
        // A first KernelSU grant is a notification the user has to tap, so the
        // default 20s shell-check timeout is often too short.
        private const val SHELL_TIMEOUT_SECONDS = 60L
        private val KNOWN_SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
        )
    }
}
