# Veristaller — agent notes

## What this is
- Root-only Android tool: installs a split bundle via `pm install-*`, then replaces the installed app's `/data/app/**/base.apk` with a patched APK. No backup or undo: a failed swap can require reinstalling the target app. Single module `:app`, package `dev.veristaller` (debug builds `dev.veristaller.debug`).
- Also in the tree: planning docs (`docs/`), a standalone clickable prototype (`design/prototype.html`, opens via `file://`, no build step), and `TODO.md` (the live milestone/gap list).

## Build and verification
- CI is the build: this workspace has no JDK/Gradle/Android SDK and no Gradle wrapper is committed. `.github/workflows/android.yml` (push to `main`/`master` — the repo's default branch is `master` — plus PRs and manual dispatch) runs `gradle :app:assembleDebug --stacktrace` then `gradle :app:testDebugUnitTest --stacktrace` with JDK 17, SDK platform 35 / build-tools 35.0.0 and Gradle 8.9, then uploads the `veristaller-debug` APK artifact. No lint/format task is configured.
- CI is green as of `22a08b9` (run #7, 2026-11-26). The "never compiled / CI-unverified" claims in `README.md`, `docs/COVERAGE.md` and `TODO.md` M5 are stale — do not re-plan a first build.
- With JDK 17 + SDK 35 locally: `gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Single test: `gradle :app:testDebugUnitTest --tests "dev.veristaller.root.ShellParsingTest"`.
- `libsu` resolves from JitPack through a content filter that only includes `com.github.topjohnwu.libsu` (`settings.gradle.kts`); any other JitPack dependency needs that `includeGroup` widened.
- The only unit test is `app/src/test/java/dev/veristaller/root/ShellParsingTest.kt` (JUnit4). No Robolectric, Compose or instrumented tests exist despite `docs/PLAN.md`.

## Docs vs. code
- `app/build.gradle.kts` + `gradle/libs.versions.toml` win: Kotlin 2.0.21, AGP 8.7.3, libsu 5.2.2, compile/target SDK 35, minSdk 26, no version catalog for lint/detekt.
- `docs/ARCHITECTURE.md` opens with an "Implementation status" + "Implementation delta" table — trust that table; the body below it still shows the original plan (Kotlin 2.1, AGP 8.9, libsu 6.x, SDK 36, Okio, Robolectric, a `findDeep()` resolver, `SwapTransaction.kt`). None of those exist in the tree.
- Real mechanics: every privileged command lives in `root/RootOps.kt`; `root/ShellScript.kt` assembles them and appends the `__VST_EXIT:$?` sentinel that `root/ShellParsing.kt` parses for the exit code. Path resolution is `JobRunner.resolve()`, not a `findDeep()` helper.

## Wiring
- Entry chain: `platform/VeristallerApp` (calls `staging.cleanupOld()`) → `AppContainer` (manual DI) → `MainActivity` → `AppViewModel` (custom factory) → `ui/VeristallerRoot` NavHost on string routes `home, bundle, patch, review, progress, result, settings`.
- `JobRunner` owns the job `StateFlow`; `job/JobService.kt` is a foreground `dataSync` notification service only — state is not bound to it.
- `RootShell` = one mutex-serialized root channel. The authoritative check is `su -c "id -u"` across the known su paths (no prompt, no race, same on Magisk/KernelSU/APatch); libsu's interactive shell is only an optimisation and every command falls back to one `su -c` when libsu cannot drive the manager. It never calls `Shell.getShell()` (that silently falls back to a non-root `sh` and caches it for the process). `reset()` forces re-acquire after a grant; `RootProbe` reports the failure reason and the mode (`persistent` / `one-shot`).
- `JobRunner` pipeline: stage → install → locate → swap → fixup → verify → optional smoke test. The install ladder retries SMART → ABI_ONLY → ALL, abandoning the failed `pm` session each time and refusing to retry signature-conflict/downgrade errors. One job at a time (`Mutex.tryLock()`); dry run stops at `JobPlan.preview` and never touches `/data/app`.
- Swap: stage `.base.apk.vst.tmp` next to `base.apk` (`chown 1000:1000`, `chmod 0644`, `chcon u:object_r:apk_data_file:s0`), then `rm` + `mv` (fs-verity workaround, `mv -f` fallback), then `fixupApk` (`chown`/`chmod`/`restorecon -F`), optional `rm -rf oat/ oat_primary/ oat_secondary`, then verify size, owner `1000:1000`, mode `0644`, sha256, SELinux label and `pm path`.

## Safety invariants (do not relax)
- SAF URIs are never interpolated into shell commands: `StagingStore.import()` copies the selection into app-private cache first; only app-generated absolute paths reach a command, and only through `shQuote`.
- `JobRunner.resolve()` requires the path to start with `/data/app/`, end in `/base.apk`, contain no `..` and contain the package name; `RootOps.pathGuard()` then gates on FILE/WRITABLE/NOLINK/SCOPE/NAME. Extracted zip entries must match `^[A-Za-z0-9._-]+\.apk$` and contain no `..`, leading slash or backslash.
- `JobRunner.runJob()` re-checks root and re-checks warning acknowledgements (UI gating alone is not enough) before touching `/data/app`.
- Manifest: no `INTERNET`; declares `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `VIBRATE`; `allowBackup=false`, `fullBackupContent=false`; `JobService` is `exported=false`.

## Device work
- M0 is still unrun: fs-verity behaviour, post-reboot signature scanning and loading splits with a re-signed base are unverified. `scripts/m0-probe.sh` is read-only; `/sdcard` is noexec, so run `su -c "sh /sdcard/workspace/Veristaller/scripts/m0-probe.sh <pkg>"` and record results in `docs/SPIKE.md` §4.
- `veristaller-diagnostics.txt` in the repo root is an untracked export from the debug app on the test device (Settings → Export diagnostics). The current one is a Xiaomi 2511FPC34I on Android 16 / SDK 36 and shows root acquisition still failing ("Could not start su: Unable to create a shell!") — the KernelSU grant has to be given to `dev.veristaller.debug`. That is the live blocker, not a code gap.
- Deliberate v0.1 gaps (real, not bookkeeping): no job cancel, no persisted job history and no process-death recovery (the log is an in-memory ring buffer; it can be exported to a file via SAF `CREATE_DOCUMENT` from Result/Settings), and UI copy is inline except `app_name`/`notification_channel` in `res/values/strings.xml`.
