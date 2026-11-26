package dev.veristaller.root

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * Root access for the whole process, serialized with a mutex.
 *
 * The shape follows the proven root apps: AdAway and Droidify run every
 * command as a job on one libsu shell (`Shell.cmd(...).exec()`) and ask
 * `Shell.isAppGrantedRoot()` whether the app is allowed to use it. They never
 * spawn `su` themselves.
 *
 * Two deliberate differences:
 *
 *  - libsu's default builder falls back to a plain `sh` when `su` is not
 *    usable, and `Shell.getShell()` caches that fallback for the whole
 *    process - a grant that arrives later would look like a permanent denial.
 *    We therefore never call `Shell.getShell()`: we build our own shell (a
 *    shell built outside `MainShell.get()` is never cached by libsu), we
 *    validate it, and we throw it away when it is not root.
 *  - the authoritative root check is `su -c "id -u"`, not an interactive
 *    shell. libsu verifies a shell by writing `echo SHELL_TEST` and reading a
 *    single line back: when the root manager prints a prompt or a banner
 *    first, or the grant is still pending, that check fails with "Unable to
 *    create a shell!" even though the grant is perfectly valid. `su -c` has
 *    no prompt, returns a real exit code and behaves the same on Magisk,
 *    KernelSU and APatch.
 *
 * If libsu cannot drive the root manager, every command falls back to its own
 * `su -c` invocation: slower, but it works.
 */
class RootShell(private val context: Context) {

    data class RootCheck(
        val uid: String? = null,
        val error: String? = null,
        val mode: String = "",
    ) {
        val ok: Boolean get() = uid == "0"
    }

    private data class SuResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val unusable: Boolean,
        val detail: String,
    )

    private val mutex = Mutex()

    @Volatile
    private var shell: Shell? = null

    /** The su binary that answered uid 0; null until a probe succeeded. */
    @Volatile
    private var suBinary: String? = null

    /** Set by the job engine so every privileged command lands in the job log. */
    @Volatile
    var commandListener: ((ShellOutput) -> Unit)? = null

    /** Acquire root and prove it. */
    suspend fun checkRoot(): RootCheck = withContext(Dispatchers.IO) {
        mutex.withLock {
            resetLocked()
            val probe = probeWithSu()
            if (!probe.ok) return@withLock probe
            RootCheck(uid = probe.uid, mode = attachShellLocked())
        }
    }

    suspend fun isRoot(): Boolean = checkRoot().ok

    suspend fun exec(script: ShellScript): ShellOutput = withContext(Dispatchers.IO) {
        val rendered = script.render()
        val started = System.currentTimeMillis()
        mutex.withLock {
            val output = try {
                if (shell == null && suBinary == null) {
                    // A command can be asked for before the first probe
                    // finished, so make sure root has been proven first.
                    val probe = probeWithSu()
                    if (!probe.ok) {
                        ShellOutput(
                            code = -1,
                            stdout = "",
                            stderr = probe.error ?: "Root is not available",
                            rendered = rendered,
                            durationMs = System.currentTimeMillis() - started,
                        )
                    } else {
                        attachShellLocked()
                        runLocked(rendered, started)
                    }
                } else {
                    runLocked(rendered, started)
                }
            } catch (t: Throwable) {
                // A dead su process would poison every later command; drop the
                // cached shell so the next attempt can re-acquire root.
                shell = null
                ShellOutput(
                    code = -1,
                    stdout = "",
                    stderr = "Could not run the root shell: ${describe(t)}",
                    rendered = rendered,
                    durationMs = System.currentTimeMillis() - started,
                )
            }
            commandListener?.invoke(output)
            output
        }
    }

    /** Drop the cached shell so the next command re-acquires root. */
    fun reset() {
        resetLocked()
    }

    // ------------------------------------------------------------------
    // Root acquisition
    // ------------------------------------------------------------------

    /**
     * Ask every known su binary for its uid with a one-shot command. This is
     * the only check the app trusts: it cannot be confused by a prompt, a
     * banner or a pending grant.
     */
    private fun probeWithSu(): RootCheck {
        var lastError: String? = null

        for (candidate in suCandidates()) {
            val result = runSu(candidate, "id -u", PROBE_TIMEOUT_MS)

            if (result.timedOut) {
                return RootCheck(
                    error = "su (${candidate}) is still waiting for a decision. Grant root to " +
                        "${context.packageName} in the KernelSU / Magisk / APatch manager, " +
                        "then tap the root chip to check again.",
                )
            }
            if (result.unusable) {
                lastError = "su (${candidate}) could not be started: ${result.detail}"
                continue
            }

            val lines = result.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            // Some su implementations print a banner before the command
            // output, so accept any line that proves uid 0.
            val uid = lines.firstOrNull { it == "0" }
                ?: if (lines.any { it.contains("uid=0") }) "0" else null

            if (uid == "0") {
                suBinary = candidate
                return RootCheck(uid = "0")
            }

            val said = result.stdout.trim()
                .ifBlank { result.stderr.trim() }
                .ifBlank { "no output" }
            lastError = "su (${candidate}) said '$said' instead of uid 0"
        }

        // The same verdict AdAway asks libsu for: FALSE means there is no su
        // binary on the app PATH, TRUE means a root shell was confirmed, null
        // means su exists but the grant was never confirmed.
        val hint = when (Shell.isAppGrantedRoot()) {
            false -> " libsu finds no su binary on the app PATH either: the device may not be " +
                "rooted, or su is only reachable outside it."
            true -> " libsu confirms root access, but no su answered the uid check."
            else -> " Grant root to ${context.packageName} in the KernelSU / Magisk / APatch manager."
        }
        return RootCheck(
            error = (lastError ?: "No su binary was found") + ". " + suDiagnostics() + "." + hint,
        )
    }

    /**
     * Best effort: a persistent shell is faster than one `su -c` per command,
     * but it is never required. libsu can refuse to drive a root manager that
     * prints a prompt, and the one-shot path still works then.
     */
    private fun attachShellLocked(): String {
        val persistent = buildPersistentShell()
        return if (persistent != null) {
            shell = persistent
            MODE_PERSISTENT
        } else {
            shell = null
            MODE_ONESHOT
        }
    }

    /**
     * Best effort: a persistent shell is faster than one `su -c` per command,
     * but it is never required.
     *
     * This is the call AdAway and Droidify end up with: libsu starts `su`
     * itself and verifies the shell. `build("su")` never falls back to a
     * plain `sh` the way the no-argument builder does, so a failed attempt
     * can never be mistaken for a working shell.
     */
    private fun buildPersistentShell(): Shell? {
        val su = suBinary ?: return null
        repeat(PERSISTENT_ATTEMPTS) {
            try {
                val sh = Shell.Builder.create()
                    .setContext(context)
                    .setTimeout(SHELL_TIMEOUT_SECONDS)
                    .build(su)
                if (sh.isRoot()) return sh
                runCatching { sh.waitAndClose(1, TimeUnit.SECONDS) }
            } catch (t: Throwable) {
                // libsu could not use this su as an interactive shell. The
                // one-shot path is enough, so try again and then give up.
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Running commands
    // ------------------------------------------------------------------

    private fun runLocked(rendered: String, started: Long): ShellOutput {
        val sh = shell
        if (sh != null && sh.isAlive()) {
            return try {
                executeOn(sh, rendered, started)
            } catch (t: Throwable) {
                // The persistent shell died mid-job; a fresh `su -c` may work.
                shell = null
                executeOneShot(rendered, started)
            }
        }
        return executeOneShot(rendered, started)
    }

    private fun executeOn(sh: Shell, rendered: String, started: Long): ShellOutput {
        val job = sh.newJob()
        job.add(rendered)
        val result = job.exec()
        val (stdout, code) = ShellParsing.exitCode(
            result.out.joinToString("\n"),
            result.code,
        )
        return ShellOutput(
            code = code,
            stdout = stdout,
            stderr = result.err.joinToString("\n"),
            rendered = rendered,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /** Run the whole script through a single `su -c` invocation. */
    private fun executeOneShot(rendered: String, started: Long): ShellOutput {
        val su = suBinary ?: throw IllegalStateException("No su binary answered uid 0")
        val result = runSu(su, rendered, COMMAND_TIMEOUT_MS)
        val (stdout, code) = ShellParsing.exitCode(
            result.stdout,
            if (result.timedOut) -1 else result.code,
        )
        return ShellOutput(
            code = code,
            stdout = stdout,
            stderr = if (result.timedOut) {
                "su did not answer within ${COMMAND_TIMEOUT_MS} ms"
            } else {
                result.stderr
            },
            rendered = rendered,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * Run `command` through `su -c` and capture both streams. The command is
     * passed as one argument, so the root shell - not this process - does the
     * parsing, exactly like the interactive path would.
     */
    private fun runSu(su: String, command: String, timeoutMs: Long): SuResult {
        val process = try {
            ProcessBuilder(su, "-c", command)
                .apply { environment()["PATH"] = rootPath() }
                .start()
        } catch (t: Throwable) {
            return SuResult(-1, "", "", false, true, describe(t))
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = thread(name = "vst-su-out") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') }
            }
        }
        val errThread = thread(name = "vst-su-err") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            return SuResult(
                code = -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = true,
                unusable = false,
                detail = "no answer within ${timeoutMs} ms",
            )
        }

        outThread.join(2000)
        errThread.join(2000)
        return SuResult(
            code = process.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = false,
            unusable = false,
            detail = "",
        )
    }

    // ------------------------------------------------------------------
    // su discovery
    // ------------------------------------------------------------------

    /**
     * Absolute paths first: the winner is then handed to libsu as-is, which
     * spawns that exact binary instead of resolving `su` through the app's
     * PATH. The bare `su` is the last resort.
     */
    private fun suCandidates(): List<String> = buildList {
        addAll(KNOWN_SU_PATHS)
        add("su")
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

    private fun suDiagnostics(): String {
        val path = System.getenv("PATH").orEmpty()
        val onPath = path.split(File.pathSeparator)
            .map { if (it.isBlank()) "." else it }
            .filter { File(it, "su").canExecute() }
        val present = KNOWN_SU_PATHS.filter { File(it).canExecute() }
        return "app PATH=$path; su on app PATH=${onPath.ifEmpty { "none" }}; " +
            "known su paths present=${present.ifEmpty { "none" }}; root PATH used=${rootPath()}"
    }

    private fun describe(t: Throwable): String {
        val cause = generateSequence(t.cause) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
        return listOfNotNull(t.message, cause?.let { "cause: $it" })
            .distinct()
            .joinToString(" · ")
            .ifBlank { t.javaClass.simpleName }
    }

    private fun resetLocked() {
        shell?.let { runCatching { it.waitAndClose(1, TimeUnit.SECONDS) } }
        shell = null
        suBinary = null
    }

    companion object {
        const val MODE_PERSISTENT = "persistent"
        const val MODE_ONESHOT = "one-shot"

        /** How long a root check may wait for a pending grant. */
        private const val PROBE_TIMEOUT_MS = 15_000L

        /** Upper bound for a single privileged command. */
        private const val COMMAND_TIMEOUT_MS = 300_000L

        private const val SHELL_TIMEOUT_SECONDS = 20L
        private const val PERSISTENT_ATTEMPTS = 2

        private val KNOWN_SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
        )
    }
}
