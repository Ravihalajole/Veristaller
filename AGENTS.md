# Veristaller — agent notes

## Project state
- Root-only Android tool: installs a split bundle via `pm install-*`, then replaces the installed app's `/data/app/**/base.apk` with a patched APK; hash-verified backup, rollback and manual restore. Single module `:app`, package `dev.veristaller`.
- This workspace holds planning docs, a standalone HTML prototype and a first-pass implementation that has **never been compiled** (no JDK/Gradle/Android SDK here). `TODO.md` is the live milestone/gap list; its unchecked M0/M5 items are real, not stale bookkeeping.

## Build and verification
- CI is the build: `.github/workflows/android.yml` (push/PR to `main`/`master`) runs `gradle :app:assembleDebug --stacktrace` then `gradle :app:testDebugUnitTest --stacktrace` with JDK 17, SDK platform 35/build-tools 35.0.0 and Gradle 8.9, then uploads `veristaller-debug`. Verify there, not locally.
- No Gradle wrapper is committed. A local build needs JDK 17 + SDK 35 and `gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- `libsu` comes from JitPack behind a content filter that includes only `com.github.topjohnwu.libsu` (`settings.gradle.kts`); another JitPack dependency requires widening that filter.
- The only unit test is `app/src/test/java/dev/veristaller/root/ShellParsingTest.kt` (JUnit4). No Robolectric or Compose tests exist despite `docs/PLAN.md`.

## Docs vs. code
- `docs/SRS.md`, `ARCHITECTURE.md`, `PLAN.md`, `UI_SPEC.md` are a plan. When they conflict with `app/build.gradle.kts` / `gradle/libs.versions.toml`, the build files win: actual Kotlin 2.0.21, AGP 8.7.3, libsu 5.2.2, compile/targetSdk 35 (docs say 2.1 / 8.9 / 6.x / 36).
- `ARCHITECTURE.md` also describes things absent from the tree: a `__VST_EXIT` sentinel, Okio, Robolectric, `SwapTransaction.kt`, a `findDeep()` resolver and an in-progress recovery marker. Real scripts are plain `RootOps` lines with no sentinel; path resolution lives in `JobRunner.resolve()`.
- `design/prototype.html` opens directly with `file://.../design/prototype.html` on the phone; no server or build step.

## Wiring and execution flow
- Entry chain: `platform/VeristallerApp` → `AppContainer` (manual DI, runs `staging.cleanupOld()`) → `MainActivity` → `AppViewModel` (custom factory) → `ui/VeristallerRoot` NavHost with string routes (home, bundle, patch, review, progress, result, history, settings). `JobService` is a foreground `dataSync` service; `JobRunner` owns the job `StateFlow`.
- Privileged layer: `RootShell` is one mutex-serialized persistent libsu shell (drops a denied/dead cached shell; `reset()` forces re-acquire); `ShellScript` + `shQuote` are the only sanctioned way to assemble commands; `RootOps` is the command catalogue; `ShellParsing` parses `stat`, `sha256sum` and install-session IDs.
- `JobRunner` pipeline: stage → install (split ladder SMART → ABI_ONLY → ALL, abandoning failed sessions) → locate → backup → swap → fixup → verify → rollback on later failure. One job at a time via `Mutex.tryLock()`. Dry-run stops at `JobPlan.preview`, writes a history row and never touches `/data/app`.
- Swap stages `.base.apk.vst.tmp` beside `base.apk`, sets `1000:1000` / `0644` / `apk_data_file`, then `rm` + `mv` (fs-verity workaround), `restorecon -F`, clears `oat/`, and verifies size, owner, mode, sha256 and SELinux. Backup creation compares hashes before the swap; failures after the swap attempt a verified rollback.

## Safety invariants (do not relax)
- SAF URIs are never interpolated into shell commands: `StagingStore` copies selections to app-private files first, and only app-generated absolute paths go through `shQuote`.
- `JobRunner.resolve()` rejects paths outside `/data/app`, paths containing `..`, and anything not ending in `/base.apk`; extracted zip entry names must match `^[A-Za-z0-9._-]+\.apk$`.
- Manifest declares no `INTERNET`; it does declare `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE[_DATA_SYNC]`, and `allowBackup=false`; the debug application id is `dev.veristaller.debug`.

## Device work
- M0 is unrun: fs-verity behaviour, post-reboot signature scanning and loading of splits with a re-signed base are unverified. `scripts/m0-probe.sh` is read-only, and `/sdcard` is noexec — run `su -c "sh /sdcard/workspace/Veristaller/scripts/m0-probe.sh <pkg>"` and record results in `docs/SPIKE.md` §4.
- `TODO.md` v0.1 gaps are real: no job cancel, no per-command history timeline, no process-death recovery (`job.inprogress` marker does not exist), inline strings with no localization.
