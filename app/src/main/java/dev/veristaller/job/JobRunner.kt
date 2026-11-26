package dev.veristaller.job

import android.content.Context
import dev.veristaller.data.BackupRecord
import dev.veristaller.data.BackupRepository
import dev.veristaller.data.HistoryRepository
import dev.veristaller.data.IncompleteJob
import dev.veristaller.data.JobLogStore
import dev.veristaller.data.JobRecord
import dev.veristaller.data.JobRecovery
import dev.veristaller.data.StagingStore
import dev.veristaller.domain.BundleInspector
import dev.veristaller.domain.InstalledAppReader
import dev.veristaller.domain.JobPlan
import dev.veristaller.domain.error.VeristallerException
import dev.veristaller.domain.model.ErrorCode
import dev.veristaller.domain.model.InstalledAppInfo
import dev.veristaller.domain.model.JobError
import dev.veristaller.domain.model.JobLogLine
import dev.veristaller.domain.model.JobSpec
import dev.veristaller.domain.model.JobUiState
import dev.veristaller.domain.model.Outcome
import dev.veristaller.domain.model.ResolvedPath
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.domain.model.SplitMode
import dev.veristaller.domain.model.StepId
import dev.veristaller.domain.model.SwapDetails
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
import java.io.File

/**
 * The whole privileged pipeline: stage -> install -> locate -> backup -> swap
 * -> fixup -> verify, with automatic rollback and a dry-run mode.
 */
class JobRunner(
    private val context: Context,
    private val shell: RootShell,
    private val staging: StagingStore,
    private val bundleInspector: BundleInspector,
    private val installedReader: InstalledAppReader,
    private val backups: BackupRepository,
    private val history: HistoryRepository,
    private val recovery: JobRecovery,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    private val _state = MutableStateFlow(JobUiState())
    val state: StateFlow<JobUiState> = _state.asStateFlow()

    private var logStore: JobLogStore? = null

    fun start(spec: JobSpec): Boolean = launch { runJob(spec) }

    fun restore(record: BackupRecord, app: InstalledAppInfo): Boolean =
        launch { runRestore(record, app) }

    fun clearOutcome() {
        if (!_state.value.running) _state.value = JobUiState()
    }

    // ------------------------------------------------------------------

    private fun launch(block: suspend () -> Unit): Boolean {
        if (!gate.tryLock()) return false
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    outcome = Outcome.Failed(toJobError(t)),
                )
            } finally {
                gate.unlock()
            }
        }
        return true
    }

    private fun log(text: String, kind: JobLogLine.Kind = JobLogLine.Kind.NORMAL) {
        val line = JobLogLine(text, kind)
        _state.value = _state.value.copy(
            log = (_state.value.log + line).takeLast(MAX_LOG_LINES),
        )
        logStore?.append(line.text, line.time)
    }

    /** Every privileged command, with its raw output, lands in the job log. */
    private fun attachCommandLogging(verbose: Boolean = false) {
        shell.commandListener = { out ->
            log("\$ ${out.rendered}", if (out.ok) JobLogLine.Kind.DIM else JobLogLine.Kind.ERR)
            log(
                "[event] cmd exit=${out.code} ms=${out.durationMs} ok=${out.ok}",
                JobLogLine.Kind.DIM,
            )
            if (verbose || !out.ok) {
                if (out.stdout.isNotBlank()) log(out.stdout, JobLogLine.Kind.NORMAL)
                if (out.stderr.isNotBlank()) log(out.stderr, JobLogLine.Kind.ERR)
            }
        }
    }

    private fun detachCommandLogging() {
        shell.commandListener = null
    }

    private suspend fun <T> step(id: StepId, title: String, block: suspend () -> T): T {
        _state.value = _state.value.copy(step = id, title = title, detail = "", progress = null)
        log("› $title", JobLogLine.Kind.DIM)
        log("[event] step=${id.name} status=start", JobLogLine.Kind.DIM)
        val started = System.currentTimeMillis()
        val result = block()
        val elapsed = System.currentTimeMillis() - started
        _state.value = _state.value.copy(completed = _state.value.completed + id)
        log("[event] step=${id.name} status=ok ms=$elapsed", JobLogLine.Kind.DIM)
        log("✓ $title ($elapsed ms)", JobLogLine.Kind.OK)
        return result
    }

    // ------------------------------------------------------------------

    private suspend fun runJob(spec: JobSpec) {
        val startedAt = System.currentTimeMillis()
        logStore = JobLogStore(context, spec.jobId)
        _state.value = JobUiState(
            running = true,
            jobId = spec.jobId,
            packageName = spec.patch.packageName,
            title = "Preparing",
        )
        JobService.start(context, spec.patch.packageName)
        attachCommandLogging(spec.options.verboseLogs)

        var backup: BackupRecord? = null
        var swapped = false
        lastResolved = null

        try {
            if (!shell.isRoot()) {
                throw VeristallerException(
                    ErrorCode.E_ROOT_DENIED,
                    "Root access was lost before the job started",
                )
            }

            // Re-check the warning gate here, not only in the UI, so no other
            // start path can bypass an explicit acknowledgement.
            val missingAcks = spec.requiredWarnings - spec.acknowledgedWarnings
            if (missingAcks.isNotEmpty()) {
                throw VeristallerException(
                    ErrorCode.E_ACK_REQUIRED,
                    "Acknowledge the warnings before starting: ${missingAcks.joinToString()}",
                )
            }

            log("${spec.patch.packageName} ${spec.patch.versionName ?: ""} · job ${spec.jobId}", JobLogLine.Kind.OK)
            val selected = bundleInspector.select(
                spec.bundle,
                spec.deviceAbis,
                spec.densityBucket,
                spec.options.splitMode,
                spec.deviceLocales,
            )
            log(
                "splits ${selected.size}/${spec.bundle.splits.size} · ${spec.options.splitMode.label}",
                JobLogLine.Kind.DIM,
            )
            if (selected.isEmpty()) {
                throw VeristallerException(
                    ErrorCode.E_BUNDLE_UNSUPPORTED,
                    "No split in this bundle can be installed on this device",
                )
            }

            if (spec.options.dryRun) {
                val plan = JobPlan.preview(spec, selected)
                plan.forEach { log(it, JobLogLine.Kind.DIM) }
                saveRecord(spec, JobRecord.STATUS_DRY_RUN, null, spec.installed.baseApk, null, startedAt)
                _state.value = _state.value.copy(
                    running = false,
                    outcome = Outcome.DryRun(plan),
                    completed = StepId.entries.toSet(),
                )
                return
            }

            val jobDir = step(StepId.STAGE, "Staging ${selected.size} splits") {
                val dir = staging.newJobDir(spec.jobId)
                staging.extractSplits(spec.bundle, selected, dir)
                dir
            }

            step(StepId.INSTALL, "Installing bundle") {
                install(spec, jobDir)
            }

            val resolved = step(StepId.LOCATE, "Resolving code path") {
                resolve(spec.patch.packageName)
            }
            log("base.apk ${resolved.baseApk}", JobLogLine.Kind.DIM)

            val record = step(StepId.BACKUP, "Backing up base.apk") {
                // The install may have changed the version, so re-read the app.
                val current = installedReader.read(spec.patch.packageName)
                val created = backups.create(
                    spec.installed.copy(
                        baseApk = resolved.baseApk,
                        codePath = resolved.directory,
                        versionCode = current?.versionCode ?: spec.installed.versionCode,
                        versionName = current?.versionName ?: spec.installed.versionName,
                        label = current?.label ?: spec.installed.label,
                    ),
                    current?.versionName ?: spec.patch.versionName,
                )
                log("sha256 ${created.sha256.take(16)}… verified", JobLogLine.Kind.DIM)
                created
            }
            backup = record
            recovery.write(
                IncompleteJob(
                    jobId = spec.jobId,
                    packageName = spec.patch.packageName,
                    basePath = resolved.baseApk,
                    backupPath = record.path,
                    startedAt = startedAt,
                ),
            )

            // Hash the exact file that will be swapped in, so verify can prove
            // the bytes on disk are the patched APK and not a partial copy.
            val patchHash = ShellParsing.sha256(
                shell.exec(RootOps.sha256(spec.patch.file.absolutePath)).stdout,
            ) ?: throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "Could not checksum the patched APK",
            )

            step(StepId.SWAP, "Replacing base.apk") {
                // From here the original file may be removed, so any failure
                // (even before the move) must restore the verified backup.
                swapped = true
                swap(spec, resolved)
            }

            step(StepId.FIXUP, "Restoring ownership and label") {
                val fix = shell.exec(RootOps.fixupApk(resolved.baseApk))
                if (!fix.ok) {
                    throw VeristallerException(
                        ErrorCode.E_SELINUX_DENIED,
                        "Failed to fix the APK owner or security label",
                        fix.combined(),
                    )
                }
                if (spec.options.clearOat) {
                    shell.exec(RootOps.clearOat(resolved.directory))
                }
            }

            val details = step(StepId.VERIFY, "Verifying the swap") {
                verify(spec, resolved, record.path, patchHash)
            }

            if (spec.options.smokeTest) {
                step(StepId.SMOKE, "Smoke testing the patched app") {
                    val out = shell.exec(RootOps.smokeTest(spec.patch.packageName))
                    val crashed = out.stdout.lineSequence().any { it.contains("FATAL EXCEPTION") }
                    val launched = out.stdout.contains("MONKEY=0")
                    when {
                        crashed -> log(
                            "smoke test: crash detected — see logcat output above",
                            JobLogLine.Kind.WARN,
                        )
                        launched -> log("smoke test: launched without a fatal crash", JobLogLine.Kind.OK)
                        else -> log("smoke test: inconclusive", JobLogLine.Kind.WARN)
                    }
                }
            }

            val recordId = saveRecord(
                spec,
                JobRecord.STATUS_SUCCESS,
                null,
                resolved.baseApk,
                record.path,
                startedAt,
            )
            backups.prune(spec.patch.packageName, spec.options.keepBackups)
            _state.value = _state.value.copy(
                running = false,
                progress = 1f,
                completed = StepId.entries.toSet(),
                outcome = Outcome.Success(details, recordId),
            )
            log("done in ${System.currentTimeMillis() - startedAt} ms", JobLogLine.Kind.OK)
        } catch (t: Throwable) {
            val error = toJobError(t)
            log("failed: ${error.message}", JobLogLine.Kind.ERR)

            val backupRef = backup
            val resolvedRef = lastResolved
            var rolledBack = false
            if (swapped && backupRef != null && resolvedRef != null) {
                rolledBack = rollback(backupRef, resolvedRef, spec.patch.packageName)
            }

            saveRecord(
                spec,
                if (rolledBack) JobRecord.STATUS_ROLLED_BACK else JobRecord.STATUS_FAILED,
                error,
                resolvedRef?.baseApk,
                backupRef?.path,
                startedAt,
            )
            _state.value = _state.value.copy(
                running = false,
                outcome = if (rolledBack) {
                    Outcome.RolledBack(error, null)
                } else {
                    Outcome.Failed(error)
                },
            )
        } finally {
            // A dry run never writes the marker; don't erase one from an
            // earlier interrupted job.
            if (backup != null) recovery.clear()
            detachCommandLogging()
            staging.deleteJobDir(spec.jobId)
            JobService.stop(context)
        }
    }

    private var lastResolved: ResolvedPath? = null

    // ------------------------------------------------------------------

    private suspend fun install(spec: JobSpec, jobDir: File) {
        val modes = when (spec.options.splitMode) {
            SplitMode.SMART -> listOf(SplitMode.SMART, SplitMode.ABI_ONLY, SplitMode.ALL)
            SplitMode.ABI_ONLY -> listOf(SplitMode.ABI_ONLY, SplitMode.ALL)
            SplitMode.ALL -> listOf(SplitMode.ALL)
        }

        var lastRaw = ""
        for (index in modes.indices) {
            val mode = modes[index]
            val splits = bundleInspector.select(
                spec.bundle,
                spec.deviceAbis,
                spec.densityBucket,
                mode,
                spec.deviceLocales,
            )
            if (splits.isEmpty()) continue

            // A retry may widen the split set, so extract anything missing.
            val missing = splits.filter { !File(jobDir, it.fileName).exists() }
            if (missing.isNotEmpty()) {
                staging.extractSplits(spec.bundle, missing, jobDir)
            }

            val files = splits.map { File(jobDir, it.fileName) }

            val total = files.sumOf { it.length() }
            val create = shell.exec(RootOps.installCreate(spec.options.installerPackage, total))
            val session = ShellParsing.sessionId(create.combined())
            if (!create.ok || session == null) {
                lastRaw = create.combined()
                if (session != null) shell.exec(RootOps.installAbandon(session))
                if (index < modes.lastIndex) {
                    log("create failed, retrying", JobLogLine.Kind.WARN)
                    continue
                }
                throw VeristallerException(
                    ErrorCode.E_INSTALL_FAILED,
                    "Could not open an install session",
                    lastRaw,
                )
            }

            log("session $session · ${splits.size} splits · $total bytes", JobLogLine.Kind.DIM)

            var failure: String? = null
            for (split in splits) {
                val file = File(jobDir, split.fileName)
                val out = shell.exec(
                    RootOps.installWrite(session, split.fileName, file.absolutePath, file.length()),
                )
                if (!out.ok) {
                    failure = out.combined()
                    break
                }
                _state.value = _state.value.copy(
                    detail = "Writing ${splits.indexOf(split) + 1}/${splits.size} splits…",
                    progress = (splits.indexOf(split) + 1).toFloat() / splits.size,
                )
                log("write ${split.fileName}", JobLogLine.Kind.DIM)
            }

            if (failure != null) {
                shell.exec(RootOps.installAbandon(session))
                lastRaw = failure
                if (index < modes.lastIndex) {
                    log("write failed, retrying with ${modes[index + 1].label}", JobLogLine.Kind.WARN)
                    continue
                }
                throw VeristallerException(
                    ErrorCode.E_INSTALL_FAILED,
                    "Writing the splits failed",
                    failure,
                )
            }

            val commit = shell.exec(RootOps.installCommit(session))
            if (commit.ok) {
                log("install-commit ok", JobLogLine.Kind.OK)
                return
            }

            shell.exec(RootOps.installAbandon(session))
            lastRaw = commit.combined()
            val error = installError(lastRaw)
            val retryable = error.code != ErrorCode.E_SIGNATURE_CONFLICT &&
                error.code != ErrorCode.E_VERSION_DOWNGRADE &&
                retryableInstall(lastRaw)
            if (index < modes.lastIndex && retryable) {
                log("commit rejected, retrying with ${modes[index + 1].label}", JobLogLine.Kind.WARN)
                continue
            }
            throw error
        }

        throw VeristallerException(
            ErrorCode.E_INSTALL_FAILED,
            "All split strategies were rejected",
            lastRaw,
        )
    }

    private suspend fun resolve(packageName: String): ResolvedPath {
        val pmPath = shell.exec(RootOps.pmPath(packageName))
        val paths = pmPath.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toList()

        var base = paths.firstOrNull { it.endsWith("/base.apk") }
        val dump = shell.exec(RootOps.dumpCodePath(packageName)).stdout.trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        if (base.isNullOrBlank()) {
            val dir = dump.ifBlank { paths.firstOrNull()?.substringBeforeLast('/').orEmpty() }
            if (dir.isNotBlank()) {
                fun findIn(target: String): String? = shell.exec(RootOps.findBase(target))
                    .stdout.trim()
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }

                base = findIn(dir)
                if (base.isNullOrBlank() && dir.startsWith("/data/app/")) {
                    // A pm path may point inside a deeper split folder; search
                    // one level up as well, then validate the result below.
                    base = findIn(dir.substringBeforeLast('/'))
                }
            }
        }

        if (base.isNullOrBlank()) {
            throw VeristallerException(
                ErrorCode.E_PATH_UNRESOLVED,
                "Could not locate base.apk for $packageName",
                "pm path:\n${pmPath.combined()}\ncodePath: $dump",
            )
        }
        if (!base.startsWith("/data/app/") || !base.endsWith("/base.apk") ||
            base.contains("..") || !base.contains(packageName)
        ) {
            throw VeristallerException(
                ErrorCode.E_PATH_UNSAFE,
                "Resolved path is outside /data/app or belongs to another package",
                base,
            )
        }

        val guardOut = shell.exec(RootOps.pathGuard(base))
        val guard = ShellParsing.guard(guardOut.stdout)
        if (!guard.safe) {
            throw VeristallerException(
                ErrorCode.E_PATH_UNSAFE,
                "base.apk failed the path safety check",
                guardOut.combined(),
            )
        }

        val resolved = ResolvedPath(directory = base.substringBeforeLast('/'), baseApk = base)
        lastResolved = resolved
        return resolved
    }

    private suspend fun swap(spec: JobSpec, path: ResolvedPath): Boolean {
        shell.exec(RootOps.forceStop(spec.patch.packageName))
        val alive = shell.exec(RootOps.isRunning(spec.patch.packageName)).stdout.trim()
        if (alive.isNotEmpty()) {
            throw VeristallerException(
                ErrorCode.E_SWAP_FAILED,
                "The app is still running after force-stop",
                alive,
            )
        }
        val temp = "${path.directory}/.base.apk.vst.tmp"

        try {
            val prep = shell.exec(RootOps.stageInPlace(spec.patch.file.absolutePath, temp))
            if (!prep.ok) {
                throw VeristallerException(
                    ErrorCode.E_SWAP_FAILED,
                    "Could not stage the new APK next to base.apk",
                    prep.combined(),
                )
            }
            log("staged ${spec.patch.sizeBytes} bytes as .base.apk.vst.tmp", JobLogLine.Kind.DIM)

            val remove = shell.exec(RootOps.removeFile(path.baseApk))
            if (!remove.ok) {
                throw VeristallerException(
                    ErrorCode.E_VERITY_LOCKED,
                    "The system refused to remove the original base.apk",
                    remove.combined(),
                )
            }

            val move = shell.exec(RootOps.move(temp, path.baseApk))
            if (!move.ok) {
                // A verity-locked inode can survive the first rename attempt.
                val forced = shell.exec(RootOps.moveForce(temp, path.baseApk))
                if (!forced.ok) {
                    throw VeristallerException(
                        ErrorCode.E_VERITY_LOCKED,
                        "Could not move the new APK into place",
                        move.combined() + "\n" + forced.combined(),
                    )
                }
            }
            return true
        } finally {
            // Harmless after a successful mv; cleans up after a failed one.
            shell.exec(RootOps.removeFile(temp))
        }
    }

    private suspend fun verify(
        spec: JobSpec,
        path: ResolvedPath,
        backupPath: String,
        expectedHash: String,
    ): SwapDetails {
        val statOut = shell.exec(RootOps.stat(path.baseApk))
        val stat = ShellParsing.stat(statOut.stdout)
            ?: throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "Could not read the new base.apk",
                statOut.combined(),
            )

        if (stat.size != spec.patch.sizeBytes) {
            throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "Size is ${stat.size}, expected ${spec.patch.sizeBytes}",
            )
        }
        if (stat.owner != "1000:1000") {
            throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "Owner is ${stat.owner}, expected 1000:1000",
            )
        }
        if (stat.mode.trimStart('0') != "644") {
            throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "Mode is ${stat.mode}, expected 0644",
            )
        }

        val hash = ShellParsing.sha256(shell.exec(RootOps.sha256(path.baseApk)).stdout)
        if (hash == null || hash != expectedHash) {
            throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "The bytes on disk do not match the patched APK",
            )
        }

        var context = shell.exec(RootOps.context(path.baseApk)).stdout.trim()
        if (!context.contains("apk_data_file")) {
            shell.exec(RootOps.fixupApk(path.baseApk))
            context = shell.exec(RootOps.context(path.baseApk)).stdout.trim()
        }
        if (!context.contains("apk_data_file")) {
            throw VeristallerException(
                ErrorCode.E_SELINUX_DENIED,
                "SELinux label is '$context', expected apk_data_file",
            )
        }

        val pmAgain = shell.exec(RootOps.pmPath(spec.patch.packageName))
        if (!pmAgain.stdout.contains(path.baseApk)) {
            throw VeristallerException(
                ErrorCode.E_VERIFY_FAILED,
                "PackageManager no longer reports the swapped base.apk",
                pmAgain.combined(),
            )
        }

        return SwapDetails(
            basePath = path.baseApk,
            owner = stat.owner,
            mode = stat.mode,
            selinux = context,
            sizeBytes = stat.size,
            backupPath = backupPath,
        )
    }

    private suspend fun rollback(
        backup: BackupRecord,
        path: ResolvedPath,
        packageName: String,
    ): Boolean {
        log("rolling back to the verified backup…", JobLogLine.Kind.WARN)
        shell.exec(RootOps.forceStop(packageName))
        val copy = shell.exec(RootOps.copy(backup.path, path.baseApk))
        val fix = shell.exec(RootOps.fixupApk(path.baseApk))
        shell.exec(RootOps.clearOat(path.directory))
        val stat = ShellParsing.stat(shell.exec(RootOps.stat(path.baseApk)).stdout)
        val hash = ShellParsing.sha256(shell.exec(RootOps.sha256(path.baseApk)).stdout)
        val ok = copy.ok && fix.ok && stat != null &&
            stat.owner == "1000:1000" &&
            stat.mode.trimStart('0') == "644" &&
            stat.size == backup.sizeBytes && hash == backup.sha256
        log(
            if (ok) "rollback verified (${backup.sizeBytes} bytes)" else "ROLLBACK FAILED",
            if (ok) JobLogLine.Kind.OK else JobLogLine.Kind.ERR,
        )
        return ok
    }

    // ------------------------------------------------------------------

    private suspend fun runRestore(record: BackupRecord, app: InstalledAppInfo) {
        val startedAt = System.currentTimeMillis()
        val jobId = "r-" + System.currentTimeMillis().toString(36)
        logStore = JobLogStore(context, jobId)
        _state.value = JobUiState(
            running = true,
            jobId = jobId,
            packageName = app.packageName,
            title = "Restoring",
        )
        JobService.start(context, app.packageName)
        attachCommandLogging()
        recovery.write(
            IncompleteJob(
                jobId = jobId,
                packageName = app.packageName,
                basePath = app.baseApk,
                backupPath = record.path,
                startedAt = startedAt,
            ),
        )

        try {
            step(StepId.SWAP, "Restoring ${app.packageName}") {
                if (!app.baseApk.startsWith("/data/app/") ||
                    !app.baseApk.endsWith("/base.apk") ||
                    app.baseApk.contains("..")
                ) {
                    throw VeristallerException(
                        ErrorCode.E_PATH_UNSAFE,
                        "Refusing to restore outside /data/app",
                        app.baseApk,
                    )
                }
                shell.exec(RootOps.forceStop(app.packageName))
                val copy = shell.exec(RootOps.copy(record.path, app.baseApk))
                if (!copy.ok) {
                    throw VeristallerException(
                        ErrorCode.E_ROLLBACK_FAILED,
                        "Could not copy the backup back",
                        copy.combined(),
                    )
                }
                val fix = shell.exec(RootOps.fixupApk(app.baseApk))
                if (!fix.ok) {
                    throw VeristallerException(
                        ErrorCode.E_SELINUX_DENIED,
                        "Could not fix the restored APK owner or label",
                        fix.combined(),
                    )
                }
                shell.exec(RootOps.clearOat(app.codePath))
            }

            val stat = ShellParsing.stat(shell.exec(RootOps.stat(app.baseApk)).stdout)
            val hash = ShellParsing.sha256(shell.exec(RootOps.sha256(app.baseApk)).stdout)
            if (stat == null || stat.owner != "1000:1000" ||
                stat.mode.trimStart('0') != "644" ||
                stat.size != record.sizeBytes || hash != record.sha256
            ) {
                throw VeristallerException(
                    ErrorCode.E_VERIFY_FAILED,
                    "Restored file does not match the backup (owner, mode, size or sha256)",
                )
            }

            history.add(
                JobRecord(
                    id = jobId,
                    packageName = app.packageName,
                    label = app.label,
                    versionName = record.versionName,
                    action = JobRecord.ACTION_RESTORE,
                    status = JobRecord.STATUS_RESTORED,
                    basePath = app.baseApk,
                    backupPath = record.path,
                    timestamp = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    logPath = logStore?.path(),
                ),
            )
            _state.value = _state.value.copy(
                running = false,
                progress = 1f,
                completed = setOf(StepId.SWAP),
                outcome = Outcome.Restored(app.packageName, record.versionCode),
            )
        } catch (t: Throwable) {
            val error = toJobError(t)
            log("restore failed: ${error.message}", JobLogLine.Kind.ERR)
            history.add(
                JobRecord(
                    id = jobId,
                    packageName = app.packageName,
                    label = app.label,
                    versionName = record.versionName,
                    action = JobRecord.ACTION_RESTORE,
                    status = JobRecord.STATUS_FAILED,
                    errorCode = error.code.name,
                    errorMessage = error.message,
                    timestamp = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    logPath = logStore?.path(),
                ),
            )
            _state.value = _state.value.copy(running = false, outcome = Outcome.Failed(error))
        } finally {
            recovery.clear()
            detachCommandLogging()
            JobService.stop(context)
        }
    }

    // ------------------------------------------------------------------

    private suspend fun saveRecord(
        spec: JobSpec,
        status: String,
        error: JobError?,
        basePath: String?,
        backupPath: String?,
        startedAt: Long,
    ): String {
        val record = JobRecord(
            id = spec.jobId,
            packageName = spec.patch.packageName,
            label = spec.packageLabel,
            versionName = spec.patch.versionName,
            action = JobRecord.ACTION_SWAP,
            status = status,
            errorCode = error?.code?.name,
            errorMessage = error?.message,
            basePath = basePath,
            backupPath = backupPath,
            timestamp = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            logPath = logStore?.path(),
        )
        history.add(record)
        return record.id
    }

    private fun retryableInstall(raw: String): Boolean {
        val text = raw.uppercase()
        return text.contains("INVALID_APK") ||
            text.contains("INCOMPATIBLE") ||
            text.contains("NO_MATCHING_ABIS") ||
            text.contains("SPLIT")
    }

    private fun installError(raw: String): VeristallerException {
        val text = raw.uppercase()
        val code = when {
            text.contains("UPDATE_INCOMPATIBLE") || text.contains("SIGNATURES DO NOT MATCH") ->
                ErrorCode.E_SIGNATURE_CONFLICT
            text.contains("VERSION_DOWNGRADE") || text.contains("DOWNGRADE") ->
                ErrorCode.E_VERSION_DOWNGRADE
            text.contains("INSUFFICIENT_STORAGE") -> ErrorCode.E_NOT_ENOUGH_SPACE
            text.contains("INVALID_APK") -> ErrorCode.E_BUNDLE_CORRUPT
            else -> ErrorCode.E_INSTALL_FAILED
        }
        val friendly = when (code) {
            ErrorCode.E_SIGNATURE_CONFLICT -> "The installed app is signed with another key"
            ErrorCode.E_VERSION_DOWNGRADE -> "The bundle has a lower version code than the installed app"
            ErrorCode.E_NOT_ENOUGH_SPACE -> "Not enough space for the install"
            ErrorCode.E_BUNDLE_CORRUPT -> "PackageManager rejected the APK set"
            else -> "The install was rejected"
        }
        return VeristallerException(code, friendly, raw)
    }

    private fun toJobError(t: Throwable): JobError {
        val state = _state.value
        return if (t is VeristallerException) {
            JobError(
                code = t.code,
                message = t.message,
                raw = t.raw,
                step = t.step ?: state.step?.name,
                exitCode = t.exitCode,
                jobId = state.jobId,
            )
        } else {
            JobError(
                code = ErrorCode.E_UNKNOWN,
                message = t.message ?: t.javaClass.simpleName,
                raw = t.stackTraceToString().take(2000),
                step = state.step?.name,
                jobId = state.jobId,
            )
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 500
    }
}
