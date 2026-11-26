# Veristaller — Architecture & Tech Stack

| | |
|---|---|
| Version | 1.0 |
| Related | `docs/SRS.md`, `docs/PLAN.md`, `docs/UI_SPEC.md` |
| Target | Android 8–16, root (Magisk / KernelSU / APatch) |

> **Implementation status:** this document is the original design. Where it
> disagrees with `app/build.gradle.kts`, `gradle/libs.versions.toml`, `TODO.md`
> or the Kotlin sources, those files are authoritative: Kotlin 2.0.21, AGP
> 8.7.3, Gradle 8.9 in CI, libsu 5.2.2, compile/target SDK 35, no Okio, no
> Robolectric, and flat `ui/screens` + `root/RootOps.kt` instead of the
> directories below. The `__VST_EXIT` sentinel and `job.inprogress` marker are
> now implemented. The foreground service still posts notifications only;
> moving the `StateFlow` into a bound service remains on M5. See
> `docs/COVERAGE.md` for the requirement-to-code audit and the deliberate gaps.

### Implementation delta (current tree)

| Original design | Shipped implementation |
|---|---|
| Kotlin 2.1 / AGP 8.9 / libsu 6.x / SDK 36 | Kotlin 2.0.21 / AGP 8.7.3 / libsu 5.2.2 / compile+target 35 (`app/build.gradle.kts`) |
| Type-safe routes and nested `ui/home`, `ui/pick`, … | String routes in `ui/VeristallerRoot.kt`; flat `ui/screens/` |
| `domain/JobRunner.kt`, `SwapTransaction.kt`, `findDeep()` | `job/JobRunner.kt` owns the pipeline, swap, rollback and `resolve()` |
| `root/ops/*` op classes | Flat `root/RootOps.kt` command catalogue |
| Okio atomic writes, Robolectric, Compose tests | Plain JVM file IO and one JUnit4 test (`ShellParsingTest`) |
| State held by a bound service | `JobRunner` holds the `StateFlow`; `JobService` is a foreground notification only |
| `keepBackups=0` disables backups (`SRS FR-12.2`) | `0` keeps every backup with a red warning, so no swap is backup-free |
| Diagnostics clipboard-only | SAF `CREATE_DOCUMENT` export from Result, History and Settings |

---

## 1. Tech stack decision

| Layer | Choice | Why | Rejected |
|---|---|---|---|
| Language | **Kotlin 2.1**, JDK 17 | Coroutines, sealed classes for the state machine and error taxonomy, no boilerplate. | Java (verbose, no coroutines), Flutter (heavy runtime, poor root/toybox ergonomics, 20 MB+ APK). |
| UI | **Jetpack Compose + Material 3**, single Activity | The whole app is one linear flow + lists; Compose gives the stepper/progress UI cheaply and previews without XML. | XML + ViewBinding (more files, no benefit here), Compose Multiplatform (irrelevant). |
| Navigation | **Navigation-Compose**, type-safe routes, one NavHost | 6 destinations, no deep links needed. | Fragments, custom back-stack. |
| Async | **Coroutines + StateFlow**, one `JobRunner` per job | A job is a long-running suspend pipeline emitting `JobEvent`s; Flow is a natural fit. | LiveData, RxJava. |
| Root access | **libsu 6.x** (`com.github.topjohnwu.libsu:core`) | De-facto standard, ships a persistent root shell, `Shell.getShell()`, `Shell.Result`, JSON `su` fallback, handles Magisk/KernelSU/APatch detection, tiny (~120 KB). | Raw `Runtime.exec("su")` (per-command prompt, quoting bugs, no exit-code framing), `librootjava` (JVM-in-root-process, overkill), `RootShell` (unmaintained). |
| ZIP / IO | `java.util.zip` + **Okio** | Streaming extraction with size caps; Okio for atomic file writes. | Apache Commons Compress (unneeded formats), zip4j (encryption not needed). |
| APK metadata | **`PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`** | Gives package, versionCode, splitName, min/target SDK and signing certs from a plain file — no parser dependency, no root. | `apk-parser` (extra dep, weaker signature story), `aapt2` binary (not on device). |
| Persistence | **DataStore (Preferences)** for settings + **kotlinx.serialization** JSON for history/backups | Two small documents; Room's KSP overhead is not worth it for ≤200 rows. | Room, SQLite raw, SharedPreferences. |
| DI | **Manual `AppContainer`** + `viewModelFactory` | ~10 objects. Avoids KSP/Hilt build cost (noticeable when building on-device). | Hilt (add later only if the graph grows). |
| Logging | Custom `JobLog` (ring buffer → file) + `android.util.Log` | Logs are a product feature (export, timeline), not debug noise. | Timber (thin wrapper, not needed). |
| Build | **AGP 8.9 / Gradle 8.11**, version catalog, `minSdk 26`, `targetSdk 36`, `compileSdk 36` | Standard, reproducible. | Bazel (no). |
| Tests | JUnit4 + **Robolectric** (pure logic), Compose UI tests, **instrumented e2e on a rooted device** | The risky part is shell/FS behaviour → must be tested on real root, not mocked. | Unit-mocking the shell only (false confidence). |

### Dependency budget

```
libsu:core                     ~120 KB
androidx.compose.* (bom)       ~3.0 MB
androidx.navigation            ~250 KB
androidx.datastore              ~180 KB
kotlinx-serialization-json      ~300 KB
okio                            ~250 KB
------------------------------------------------
APK estimate                    ~5.2 MB  (NFR-4 ≤ 6 MB)
```

No `INTERNET` permission is declared, so the dependency graph cannot phone home even
by accident (NFR-5).

---

## 2. System context

```
┌──────────────────────────────┐
│      Veristaller (app)       │
│  UI ── JobRunner ── Domain   │
└──────────────┬───────────────┘
               │  su -c  (libsu persistent shell, uid 0)
               ▼
┌──────────────────────────────┐        ┌──────────────────────────┐
│  root shell (magisk su)      │───────▶│  toybox: stat/chown/rm/  │
│  pm / cmd package            │        │  chcon/restorecon/find   │
└──────────────┬───────────────┘        └──────────────────────────┘
               │ binder
               ▼
┌──────────────────────────────┐        ┌──────────────────────────┐
│  system_server               │        │  /data/app/~~x/pkg-y/    │
│  PackageManagerService       │───────▶│   base.apk  (target)     │
│  (session install, path)     │        │   split_*.apk, lib/, oat/│
└──────────────────────────────┘        └──────────────────────────┘
```

Trust boundary: everything the app sends to `su` is generated by the app from
validated inputs. SAF URIs are never interpolated into shell commands — files are
first copied to app-private staging, and only **app-generated absolute paths** are
ever quoted into a script.

---

## 3. Module layout

Single Gradle module (`:app`) with hard package boundaries; splitting into Gradle
modules is deferred until build times hurt.

```
dev.veristaller
├── ui/                         Compose only, no shell, no file IO
│   ├── theme/                  Color, Type, Shape, Dimens
│   ├── nav/                    Routes, VeristallerNavHost
│   ├── home/                   HomeScreen, RootStatusChip, RecentJobs
│   ├── pick/                   PickBundleScreen, PickPatchScreen, FileCard
│   ├── review/                 ReviewScreen, CheckRow, CommandPreview
│   ├── progress/               ProgressScreen, StageStepper, LogTail
│   ├── result/                 ResultScreen, ResultBadge
│   ├── history/                HistoryScreen, JobDetailScreen, BackupRow
│   ├── settings/               SettingsScreen
│   └── common/                 VeristallerScaffold, SectionCard, WarnDialog
├── domain/
│   ├── model/                  BundleInfo, SplitApk, ApkInfo, JobSpec, JobState,
│   │                           JobEvent, StepId, CheckResult, BackupRecord
│   ├── JobRunner.kt            suspend pipeline, emits JobEvent
│   ├── Preflight.kt            pure checks over facts → List<CheckResult>
│   ├── BundleInspector.kt      format adapters + split enumeration
│   ├── ApkInspector.kt         getPackageArchiveInfo wrapper
│   ├── SwapTransaction.kt      backup → swap → fixup → verify, rollback
│   └── error/                  VeristallerException + ErrorCode enum
├── root/
│   ├── RootShell.kt            libsu wrapper, serialized queue, timeouts
│   ├── RootProbe.kt            root + tool + device facts
│   ├── ShellScript.kt          script builder, shellQuote(), dry-run render
│   └── ops/                    InstallOp, PathOp, StatOp, SwapOp, FixupOp, OatOp
├── data/
│   ├── SettingsRepository.kt   DataStore
│   ├── HistoryRepository.kt    JSON file + ring buffer
│   ├── BackupRepository.kt     /data/local/tmp/veristaller/backups
│   ├── StagingStore.kt         cache/stage/<jobId>/, cleanup on start
│   └── JobLogStore.kt          files/logs/<jobId>.log
└── platform/
    ├── AppContainer.kt
    └── VeristallerApp.kt
```

Rule: `ui → domain → root/data`, never backwards. `root` knows nothing about
Compose; `domain` never touches `Context` except through injected interfaces.

---

## 4. Domain model

```kotlin
data class BundleInfo(
    val file: StagedFile,
    val format: BundleFormat,          // BUNDLETOOL_APKS, APKM, XAPK, SINGLE_APK, UNKNOWN
    val packageName: String?,
    val versionCode: Long?,
    val splits: List<SplitApk>,        // name, path, size, abi?, density?, language?, isMaster
    val installerExtras: List<String>, // toc.pb, manifest.json, obb/... (reported, ignored)
)

data class ApkInfo(
    val path: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val certSha256: String,            // hex, truncated to 16 chars for display
    val sizeBytes: Long,
)

data class JobSpec(
    val bundle: BundleInfo,
    val patch: ApkInfo,
    val installed: InstalledApp?,      // from Preflight
    val options: JobOptions,           // installerPackage, clearOat, splitMode, dryRun, keepBackups
    val acknowledgedWarnings: Set<String>,
)

sealed interface JobEvent {
    data class StepStarted(val step: StepId, val label: String) : JobEvent
    data class StepProgress(val step: StepId, val done: Int, val total: Int) : JobEvent
    data class StepFinished(val step: StepId, val durationMs: Long) : JobEvent
    data class LogLine(val stream: LogStream, val text: String) : JobEvent
    data class Failed(val step: StepId, val error: VeristallerException) : JobEvent
    data class Finished(val result: JobResult) : JobEvent
}
```

### Job state machine

```
 IDLE
  │ pick bundle + patch
  ▼
 INSPECTING ──fail──▶ FAILED(E_BUNDLE_*)
  │
  ▼
 PREFLIGHT ──fail──▶ BLOCKED (checks with FAIL)
  │ user confirms
  ▼
 STAGING ──fail──▶ FAILED(E_STAGE_*)          [nothing on device changed]
  │
  ▼
 INSTALLING ──fail──▶ FAILED(E_INSTALL_*)     [pm session aborted, rollback of session]
  │
  ▼
 RESOLVING_PATH ──fail─▶ FAILED(E_PATH_*)     [bundle is installed, app stock]
  │
  ▼
 BACKING_UP ──fail──▶ FAILED(E_BACKUP_*)      [stock intact]
  │
  ▼
 SWAPPING ──fail──▶ ROLLING_BACK ──▶ FAILED(E_SWAP_*) | RESTORED
  │
  ▼
 FIXUP ──fail──▶ ROLLING_BACK
  │
  ▼
 VERIFYING ──fail──▶ ROLLING_BACK
  │
  ▼
 DONE
```

Every terminal state writes a `JobRecord` (history) and a log file. A
`job.inprogress` marker in files dir allows FR-13.4 recovery detection.

---

## 5. Root layer

### 5.1 RootShell

```kotlin
class RootShell(private val dryRun: Boolean = false) {
    suspend fun run(script: ShellScript, timeoutMs: Long = 30_000): ShellResult
    suspend fun runOrThrow(script: ShellScript, step: StepId): ShellOutput
    val isRoot: Boolean
}

data class ShellOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val rendered: String,   // the exact script, for the log and command preview
)
```

- One persistent libsu `Shell` for the whole job (FR-1.5), created with
  `Shell.Builder().setTimeout(...)`. Verified once with `id -u`.
- Commands are **serialized** through a `Mutex` — never two root commands at once.
- Scripts always start with `set -o pipefail`-equivalent behaviour: we end each
  script with an explicit `echo "__VST_EXIT:$?"` sentinel because toybox `sh` is
  not bash and libsu's exit code is the last command's. The parser strips the
  sentinel.
- `dryRun` short-circuits: the script is rendered, logged as `[DRY RUN]`, and a
  synthetic success (`exit 0`) is returned for read-only ops; mutating ops return
  `Skipped`.

### 5.2 Quoting

All paths are generated by the app (staging paths, resolved code paths, backup
dirs), so they are trusted-ish — but they still pass through `shQuote()`:

```kotlin
fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
```

`ShellScript` is a builder, not raw strings, so a path can never be concatenated
without quoting. `BundleInspector` validates entry names against
`^[A-Za-z0-9._\-]+\.apk$` before extraction (FR-2.8).

### 5.3 Command catalogue

| Op | Script (abridged) |
|---|---|
| Probe root | `id -u` |
| Probe tools | `for t in pm stat rm chcon restorecon getenforce find; do command -v "$t" \|\| echo "MISSING:$t"; done` |
| Device facts | `getprop ro.build.version.sdk; getprop ro.product.cpu.abilist; getenforce; df -k /data` |
| Install create | `pm install-create -r -i com.android.vending -S <total> --user 0` → parses `Success: created install session [12345]` |
| Install write | `pm install-write -S <size> 12345 base.apk /data/local/tmp/veristaller/stage/<id>/base.apk` |
| Install commit | `pm install-commit 12345` |
| Path | `pm path com.x` → `package:/data/app/.../base.apk` |
| Path fallback | `cmd package dump com.x \| grep -m1 codePath=` |
| Deep locate | `find '<codePath>' -maxdepth 4 -name base.apk -print -quit` |
| Guard | `test -f '<p>' -a -w '<dir>' -a ! -L '<p>'` |
| Backup | `cp -p '<base>' '<backup>/base.apk' && sha256sum '<base>' '<backup>/base.apk'` |
| Stop | `am force-stop com.x; sleep 0.3; pidof com.x \|\| true` |
| Stage in place | `cp '<patch>' '<dir>/.base.apk.vst.tmp'` |
| Fix attrs | `chown 1000:1000 '<tmp>' && chmod 0644 '<tmp>' && chcon u:object_r:apk_data_file:s0 '<tmp>'` |
| Swap | `rm -f '<base>' && mv '<tmp>' '<base>'` |
| Relabel | `restorecon -F '<base>' \|\| chcon u:object_r:apk_data_file:s0 '<base>'` |
| Clear oat | `rm -rf '<dir>/oat' '<dir>/oat_primary' '<dir>/oat_secondary'` |
| Verify | `stat -c '%u:%g %a %s %C' '<base>'; pm path com.x` |

`chcon` is preferred over `restorecon` for the temp file because `restorecon`
resolves by path pattern and would label a hidden `.tmp` file by its own rule;
`restorecon -F` is the fallback for the final name.

### 5.4 Why `rm` before `mv` (fs-verity)

On Android 11+ PackageManager may enable `fs-verity` on the APK. Such an inode is
read-only and immutable: `cp` into it and even rename-over it fail with `EIO` /
`EPERM`. Unlinking is allowed for root, and the replacement inode has no Merkle
tree. Hence: write the temp file (fresh inode, no verity) → unlink the original →
rename the temp into place. If unlink fails, we do **not** force it: we restore the
backup state and report `E_VERITY_LOCKED` (UC-3). This is the single most
device-dependent behaviour in the app and is validated in milestone M0.

---

## 6. Path resolution algorithm

```kotlin
suspend fun resolveBaseApk(pkg: String): ResolvedPath {
    val fromPm = pmPath(pkg)                       // package:/data/app/~~kQ==/pkg-Xy==/base.apk
    val fromDump = dumpCodePath(pkg)               // codePath=/data/app/~~kQ==/pkg-Xy==
    require(fromPm.dir == fromDump || fromDump == null) { E_PATH_MISMATCH }

    var dir = fromPm.dir
    var candidate = "$dir/base.apk"
    if (!exists(candidate)) {
        candidate = (findDeep(dir, maxDepth = 4)          // FR-5.2
            ?: findDeep(dir.parent, maxDepth = 4)
            ?: throw VeristallerException(E_PATH_UNRESOLVED))
    }
    assertSafe(candidate, pkg)   // /data/app/ prefix, realpath, not a symlink, writable parent
    return ResolvedPath(dir, candidate)
}
```

Depth handling: the locator never encodes a depth. It tries `dir/base.apk` first
(Android ≤ 10 and most 11+ devices), then a bounded `find` downward (covers
`~~/pkg-rand/base.apk` and vendor sub-layouts such as a nested
`<pkg>-<rand>/<arch>/base.apk`), then one level up (covers a `pm path` that points at
a split inside a deeper folder). Whatever it finds is validated against
`/data/app/**/base.apk` and the package name, so a wrong `find` result is rejected
rather than written to.

---

## 7. Swap transaction

```kotlin
suspend fun execute(spec: JobSpec, path: ResolvedPath): SwapResult {
    val marker = jobMarkers.begin(spec)                 // FR-13.4
    val backup = backupRepo.create(path, spec)          // FR-6, hash verified
    try {
        switch.forceStop(spec.packageName)              // FR-7.1
        val tmp = "${path.dir}/.base.apk.vst.tmp"
        switch.copyPatch(spec.patch.path, tmp)          // FR-7.2
        switch.applyAttrs(tmp)                          // 1000:1000, 0644, apk_data_file
        switch.replace(path.base, tmp)                  // rm + mv, verity aware
        switch.relabel(path.base)                       // verify context
        if (spec.options.clearOat) switch.clearOat(path.dir)
        verify.assertAll(path, spec)                    // FR-8.1/8.2
        marker.commit()
        return SwapResult.Success(backup, path)
    } catch (e: VeristallerException) {
        val restored = runCatching { rollback(backup, path) }
        marker.fail(e.code)
        return if (restored.isSuccess) SwapResult.RolledBack(e) else SwapResult.Stuck(e, backup)
    }
}
```

Idempotency: every step is safe to re-run. A leftover `.base.apk.vst.tmp` is deleted
at job start; a `base.apk` missing but a backup present triggers automatic restore
before anything else (FR-9.1 / FR-13.4).

Rollback order matters: verify the backup hash **before** touching `base.apk`, so a
corrupt backup is discovered while the device is still stock.

---

## 8. Bundle adapters

```kotlin
interface BundleAdapter {
    fun matches(zip: ZipFile): Boolean
    fun read(zip: ZipFile, staged: StagedFile): BundleInfo
    val installPlan: (BundleInfo, DeviceFacts) -> List<SplitApk>
}
```

| Adapter | Signature | Split source | Ignored |
|---|---|---|---|
| `ApksAdapter` | `toc.pb` present | `splits/**/*.apk`, fallback `universal.apk`, `standalones/*.apk` | `toc.pb`, `instant/`, `*.pb` |
| `ApkmAdapter` | `meta.json` present | `*.apk` at any depth | `meta.json`, `icon.png` |
| `XapkAdapter` | `manifest.json` present | `*.apk` at root | `manifest.json`, `icon.png`, `Android/obb/**` (reported) |
| `SingleApkAdapter` | file is an APK (`PK\x03\x04` + `AndroidManifest.xml` entry) | the file itself | — |

Split classification from the entry name:

```
base-master.apk                  → master, keep always
base-arm64_v8a.apk               → abi=arm64-v8a      keep if in abilist
base-xxhdpi.apk                  → density=xxhdpi     keep if nearest bucket
base-en.apk / base-b+sr+Latn.apk → language=en        keep if in locales ∪ {en}
config.arm64_v8a.apk             → same rules
```

Fallback ladder (FR-4.3): smart filter → ABI-only → all splits. Each attempt is a
fresh session; a failed `install-commit` abandons the session
(`pm install-abandon <id>`) so no partial install lingers.

---

## 9. Concurrency and lifecycle

- One `JobRunner` instance guarded by a `Mutex`; the UI cannot start a second job
  (`Start` disables while `state != Idle`).
- The job runs in a **foreground service** (`dataSync` type, low-importance
  notification with progress) so a 30–60 s install survives the user leaving the
  app or the activity being recreated on rotation.
- State is `StateFlow<JobUiState>` held by the service and re-collected by the UI,
  so rotation never loses progress.
- `Screen-on` flag while running (FR-12.6, optional).
- Root shell is closed in `finally`, staging is deleted after success, backups are
  pruned on app start (not during a job).

---

## 10. Error taxonomy → UX

| Code | Cause | Message | Recovery |
|---|---|---|---|
| `E_ROOT_DENIED` | su denied / not rooted | "Root access was not granted" | Retry, open root manager |
| `E_TOOL_MISSING` | busybox-only ROM | "Missing tool: restorecon" | Use chcon fallback, report device |
| `E_BUNDLE_CORRUPT` | bad zip / truncated | "This file isn't a valid bundle" | Re-pick file |
| `E_BUNDLE_UNSUPPORTED` | unknown container | "Unrecognised bundle format" | Pick .apks/.apkm/.xapk |
| `E_PKG_MISMATCH` | different packages | "Bundle and patch are different apps" | Re-pick |
| `E_ALREADY_INSTALLED_CONFLICT` | signature conflict at install | "Existing install has a different signature" | Uninstall (warn) or matching bundle |
| `E_INSTALL_FAILED` | any `INSTALL_FAILED_*` | mapped friendly text + raw tail | Show raw, retry strategy |
| `E_APP_NOT_FOUND` | not installed after commit | "Install finished but the app isn't visible" | Retry resolve |
| `E_PATH_UNRESOLVED` | no base.apk | "Couldn't find the app's APK folder" | Copy commands, report SDK |
| `E_PATH_UNSAFE` | path outside /data/app | "Refusing to write outside /data/app" | Abort (bug guard) |
| `E_NOT_ENOUGH_SPACE` | df check | "Need 480 MB free, 120 MB available" | Free space |
| `E_VERITY_LOCKED` | unlink/write denied | "The system protects this APK (fs-verity)" | Restored; suggest disable-verity |
| `E_SELINUX_DENIED` | chcon/restorecon fail | "Couldn't set the APK security label" | Restored; copy `dmesg`/`logcat` hint |
| `E_VERIFY_FAILED` | size/attrs/pm path wrong | "The swap didn't verify" | Restored |
| `E_ROLLBACK_FAILED` | restore also failed | "Manual recovery needed" | Copyable command block, backup path |

Each error carries `step`, `exitCode`, `rawOutput`, `hint`, and `jobId`. The Result
screen shows the friendly line first and the raw output behind a disclosure.

---

## 11. Security and privacy

| Concern | Mitigation |
|---|---|
| Command injection | `ShellScript` builder + `shQuote`; SAF URIs never interpolated; entry names whitelisted. |
| Zip slip | canonical-path prefix check after extraction into staging. |
| Writing to arbitrary paths | `assertSafe`: realpath must start with `/data/app/`, end with `/base.apk`, dir writable, no symlink, package name must appear in the dir. |
| Bricking the device | only `/data/app/**` is ever mutated; system partitions are never mounted rw; backup + verified rollback. |
| Privilege persistence | app declares no `INTERNET`; no exported components; `allowBackup=false`; no root helper binary installed to `/system` (unlike some tools) — everything is transient `su -c`. |
| Log leakage | certificates truncated to 16 hex chars; paths are local-only; export is user-initiated. |
| Root manager tampering | no attempt to hide from Play Integrity, no denylist edits, no `resetprop`. |

---

## 12. Performance budget

| Step | Budget | Notes |
|---|---|---|
| Inspect bundle (zip central directory only) | ≤ 300 ms | Do not extract to inspect |
| Staging copy | disk-bound, ~250 MB/s | Use 1 MB buffer, `FileChannel.transferTo` |
| `pm install-write` | 10–20 s for 150 MB | Dominated by PackageManager |
| `pm install-commit` | 2–8 s | Dexopt |
| Backup copy | ≤ 1 s | Same fs, use `cp` in root shell (no JVM byte shuffling) |
| Swap | ≤ 1 s | rename is O(1) |
| Verify | ≤ 300 ms | stat + pm path |

Rule: **large file moves happen inside the root shell (`cp`, `mv`), not through
Kotlin IO.** Kotlin only stages the SAF selection once (unavoidable, content
resolver is app-side).

---

## 13. Compatibility matrix (release gate)

| Android | SDK | Path shape | Verity expected | Notes |
|---|---|---|---|---|
| 8.0–10 | 26–29 | `/data/app/<pkg>-<rand>/base.apk` | no | `pm install-create` exists since 21 |
| 11 | 30 | `/data/app/~~x/<pkg>-y/base.apk` | yes | first nested layout |
| 12–13 | 31–33 | same | yes | `cmd package` preferred for dump |
| 14 | 34 | same | yes | FGS type required |
| 15–16 | 35–36 | same, `~~` names longer | yes | 16 KB page alignment for libs of *sample* app, not our concern |

Root managers: Magisk (stable/canary), KernelSU (LKM + GKI), APatch. ROMs: AOSP,
Pixel, LineageOS, One UI, HyperOS. Known-variance list is maintained in
`docs/PLAN.md` §6.

---

## 14. Extension points

- `BundleAdapter` — add `.apkmirror` variants, `.apkz`, folder-based bundles.
- `SwapStrategy` — future framework-level install (patch `PackageManager` signature
  check) as an alternative to file replacement for verity-locked ROMs.
- `JobStep` — pluggable post-steps (e.g. clear app cache, re-apply locale splits).
- `Backend` interface — today a local root shell; could become an `adb`/Shizuku
  backend for non-root scenarios (out of scope, but the seam exists).
