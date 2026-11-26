# Veristaller — Implementation coverage

| | |
|---|---|
| Date | 2026-11-26 |
| Status | **Static-ready, CI-unverified** |
| Authority | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `TODO.md`, Kotlin sources > `docs/SRS.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md`, `docs/UI_SPEC.md` |

This document closes the audit loop between the plan/SRS/UI spec and the tree that
actually ships. It records what is implemented, what is deliberately deferred in
v0.1, and what can only be closed on a rooted device or in CI.

No local build has ever run in this workspace: there is no JDK, Gradle or Android
SDK here. The only compile/test gate is `.github/workflows/android.yml`.

---

## 1. Milestone status

| Milestone | Status | Evidence / open item |
|---|---|---|
| M0 — feasibility spike | **Not run** | `scripts/m0-probe.sh`, results belong in `docs/SPIKE.md` §4. Verity, reboot signature scan and split-loading verdicts are still open. This is the product go/no-go gate, not a code gap. |
| M1 — scaffold + root layer | Implemented | Single `:app`, manual `AppContainer`, `RootShell` + `ShellScript` + `shQuote`, `RootProbe`, `RootOps`, CI workflow. |
| M2 — input, inspection, preflight | Implemented (with S/C gaps below) | SAF staging, four bundle formats, `ApkInspector`, `InstalledAppReader`, `Preflight`, Review screen. |
| M3 — job engine | Implemented | `JobRunner` stage → install ladder → locate → backup → swap → fixup → verify → rollback; dry run; restore; recovery marker; foreground notification service. |
| M4 — UI | Implemented (static a11y pass done) | All string routes in `VeristallerRoot`, Home/Bundle/Patch/Review/Progress/Result/History/Settings, dark/light/system themes, disclosure and destructive confirmations. Physical TalkBack pass remains in M5. |
| M5 — verification / hardening | **Pending** | First CI build, device matrix, fault injection, real bundle fixture, TalkBack/contrast, SDK 36 bump, R8/baseline, bound service. |

---

## 2. SRS functional requirements

`Done` means the code path exists and is wired into the UI/job engine; it does not
claim device validation. `Deferred` is an explicit v0.1 gap recorded in `TODO.md`.
`Deviates` means the shipped behaviour differs from the SRS text and is called out
because it affects safety or UX.

| Requirement | Status | Implementation |
|---|---|---|
| FR-1.1 root chip + manager | Done | `root/RootProbe.kt`, `ui/screens/HomeScreen.kt`, `SettingsScreen.kt` |
| FR-1.2 required tools incl. `getenforce` | Done | `root/RootOps.kt`, `RootProbe.kt` |
| FR-1.3 device facts incl. ABI32/ABI64 | Done | `DeviceFacts`, `RootOps.probe()` |
| FR-1.4 root re-check before job | Done | `JobRunner.runJob()` inside the guarded `try` |
| FR-1.5 one shell / one prompt per job | Done | `RootShell` persistent `Shell` + `Mutex` |
| FR-2.1 / FR-2.2 SAF pickers | Done | `BundleScreen.kt`, `PatchScreen.kt` |
| FR-2.3 content-based format detection | Done | `domain/BundleInspector.kt` |
| FR-2.4 split enumeration + qualifiers | Done | `BundleInspector`, `SplitApk` |
| FR-2.5 patch metadata + cert | Done | `domain/ApkInspector.kt` |
| FR-2.6 recent files with persisted URI | Deferred (S) | Every pick is staged fresh; no recent-file list is persisted. |
| FR-2.7 private staging copy | Done | `data/StagingStore.kt`; SAF URIs never reach a shell script |
| FR-2.8 zip traversal guard | Done | `StagingStore.extractSplits()` rejects absolute/`..` entry names |
| FR-3.1 package match | Done | `Preflight.evaluate()` |
| FR-3.2 version warning / downgrade | Done | `Preflight`, `PatchScreen` state |
| FR-3.3 installed package + code path | Done | `InstalledAppReader`, `Preflight` |
| FR-3.4 cert mismatch warning | Done; reboot risk wording included | `Preflight` `signature` check |
| FR-3.5 bundle split cert vs patch | Deferred (S) | Only patch-vs-installed certs are read; split certificates are not enumerated. |
| FR-3.6 free-space check | Done | `Preflight` `space`, 200 MB headroom |
| FR-3.7 running-app detection | Done | `RootProbe.isRunning()`, `Preflight` `running` |
| FR-3.8 dangerous targets | Done | Hard denylist + WARN ack for `com.android.*`; own package uses `BuildConfig.APPLICATION_ID` |
| FR-3.9 code path + command preview | Done | `ReviewScreen`, `domain/JobPlan.kt` |
| FR-3.10 PASS/WARN/FAIL + ack gate | Done | `CheckRowView`, `AppViewModel.buildSpec()`, re-checked in `JobRunner.runJob()` |
| FR-4.1 session install with attribution | Done | `JobRunner.install()`, `RootOps.installCreate()` with `--user 0` |
| FR-4.2 split filtering | Done | `BundleInspector.select()`; smart/ABI-only/all |
| FR-4.3 retry ladder | Done | SMART → ABI_ONLY → ALL; failed sessions abandoned; signature/downgrade errors are not retried |
| FR-4.4 ignored entries | Done | `BundleInspector`; `standalones/` excluded when `splits/` exists |
| FR-4.5 typed `pm` error + raw output | Done | `JobRunner.installError()`, `JobError.raw` |
| FR-4.6 bundle-only diagnostic mode | Deferred (S) | Dry run covers command preview; install-only execution is not exposed. |
| FR-5.1 `pm path` + dump cross-check | Done | `JobRunner.resolve()`, `RootOps.path()` |
| FR-5.2 bounded downward + upward `find` | Done | `JobRunner.resolve()` |
| FR-5.3 `/data/app/**/base.apk` guard | Done | `ShellParsing.guard()` + resolver checks; `..` and symlinks rejected |
| FR-5.4 writable, non-symlink, package in path | Done | Same guard; read-only mount is covered by the failure path |
| FR-6.1 hash-verified backup | Done | `BackupRepository.create()` |
| FR-6.2 `meta.json` | Done | `BackupRecord` encoded beside the copy |
| FR-6.3 pre-swap hash comparison | Done | `sha256sum` pair compared before `base.apk` is touched |
| FR-6.4 retention 0–10 | Deviates | Code treats `0` as **keep every backup** (safer; no backup-free swap). A WARN explains this. SRS text says `0` disables backups. |
| FR-6.5 backups in History + restore | Done | `HistoryScreen` Backups tab, Home restore, Result restore |
| FR-7.1 force-stop + process check | Done | `RootOps.forceStop()` with `pidof` verification |
| FR-7.2 sibling temp file | Done | `.base.apk.vst.tmp` in the resolved directory |
| FR-7.3 attrs before rename | Done | `RootOps.fixupApk()`; `1000:1000`, `0644`, `apk_data_file` |
| FR-7.4 verity-aware replace | Done | `rm` then `mv`, `mv -f` fallback, typed `E_VERITY_LOCKED` |
| FR-7.5 final SELinux label + retry | Done | `restorecon -F` verification with `chcon` retry |
| FR-7.6 clear oat variants | Done | `RootOps.clearOat()` gated by Settings |
| FR-7.7 only `base.apk` touched | Done | Resolver + guard; splits/lib/data never written |
| FR-7.8 system-app update in `/data` | Done | `/data/app` target only; system image never mounted |
| FR-7.9 step and command events | Done | `JobRunner.step()`, `RootShell.commandListener` → `[event]` log lines |
| FR-8.1 `pm path` + size verify | Done | `JobRunner.verify()` |
| FR-8.2 owner/mode/context verify | Done | `JobRunner.verify()` via `stat`/`ls -Z` |
| FR-8.3 opt-in smoke test | Done | `RootOps.smokeTest()`, `StepId.SMOKE`; crash/inconclusive/ok never fails the job |
| FR-8.4 live post-swap version re-read | Deferred (C) | Result shows recorded versions; no live `dumpsys package` re-read / `lastUpdateTime` note |
| FR-9.1 automatic rollback | Done | Failure after swap attempts verified restore |
| FR-9.2 manual restore | Done | `JobRunner.restore()` from Home, History and Result |
| FR-9.3 copyable recovery block | Done | `ResultScreen.FailureBody()` |
| FR-9.4 history with timings/path/error/log | Done | `HistoryRepository`, `JobRecord` |
| FR-10.1 newest-first job list | Done | `HistoryScreen` |
| FR-10.2 timeline + commands | Done | `JobDetailDialog` reads the persisted log |
| FR-10.3 long-press actions | Done | Restore / export log / delete entry and backup |
| FR-10.4 cap 200 entries | Done | `HistoryRepository` pruning |
| FR-11.1 per-job log file | Done | `JobLogStore` at `files/logs/<jobId>.log` |
| FR-11.2 live monospace log + pause | Done | `ProgressScreen` with drag-to-pause and expand |
| FR-11.3 SAF diagnostic export | Done | `ExportLogButton` in Result, History and Settings; job log + device facts, no keys |
| FR-11.4 cert truncation only | Done | `ApkInfo.certShort`, 8-char preflight display; no full cert logged |
| FR-12.1 dry run | Done | `JobPlan.preview()`, no `/data/app` mutation |
| FR-12.2 retention control | Deviates | See FR-6.4; UI warning is red for `0` |
| FR-12.3 installer attribution | Done | Settings validation regex + default `com.android.vending` |
| FR-12.4 clear oat | Done | Toggle wired to `JobOptions.clearOat` |
| FR-12.5 split mode | Done | `SplitMode` choice row |
| FR-12.6 screen-on + vibration | Done | `VeristallerRoot` flags; `VIBRATE` permission |
| FR-12.7 theme + dynamic colour | Done | `VeristallerTheme`, API 31+ guard |
| FR-13.1 first-run disclosure | Done | Home dialog, persisted in DataStore, re-openable in Settings |
| FR-13.2 destructive confirmations | Done | Review Start dialog, restore/delete dialogs, force-on restore confirmation |
| FR-13.3 restore in ≤ 2 taps | Done | Home "Restore last backup" + confirmation |
| FR-13.4 interrupted-job recovery | Done (manual) | `JobRecovery` marker, Home banner, user-confirmed restore; no automatic restore |

---

## 3. Non-functional and acceptance status

| Requirement | Status | Note |
|---|---|---|
| NFR-1 cold start, NFR-2 swap time, NFR-3 disk, NFR-4 APK size | Unverified | Device/CI measurement required. |
| NFR-5 no network | Static pass | `AndroidManifest.xml` has no `INTERNET`; no networking dependency. |
| NFR-6 typed result, no crash | Static pass / device pending | `JobError` taxonomy + `try/catch`; device fault injection pending. |
| NFR-7 API 26–36 | Partial | `minSdk 26`, `targetSdk 35`; 36 bump is in M5. |
| NFR-8 root managers | Static pass / device pending | Manager detection implemented; KernelSU/APatch smoke tests pending. |
| NFR-9 accessibility | Static pass / device pending | Labels/`onClickLabel`, ≥48 dp targets, light/dark contrast tokens; TalkBack pass is M5. |
| NFR-10 localisation | Deferred | All UI strings are inline in v0.1, as recorded in `TODO.md`. |
| NFR-11 no background polling | Static pass | Root probe is user/VM triggered; foreground service only during a job. |
| NFR-12 privacy | Static pass | App-private logs and backups; export only on user action. |

| Acceptance | Status |
|---|---|
| AC-1 device matrix swap/launch | Pending M0/M5 hardware run |
| AC-2 stock attrs match | Pending device baseline comparison |
| AC-3 fault injection launchable state | Pending; code paths and rollback exist |
| AC-4 root denied, no crash | Static pass; device confirmation pending |
| AC-5 verified history restore | Pending device run |
| AC-6 no `INTERNET` | Static pass; CI `assembleDebug` still required |
| AC-7 verbatim privileged commands in log | Static pass via `RootShell.commandListener` |
| AC-8 dry run writes nothing | Static pass; device `find -newer` / hash check pending |

---

## 4. Deliberate v0.1 gaps

These are intentionally not implemented in this pass. They are listed in `TODO.md`
so they are not mistaken for regressions:

- Cancel a running job.
- Automatic restore after process death (the manual recovery banner is implemented).
- Recent-file shortcuts with persisted SAF URIs (FR-2.6).
- Split-certificate mismatch warning (FR-3.5).
- Bundle-only execution mode (FR-4.6).
- Live post-swap version re-read (FR-8.4).
- Re-signing/swapping splits, not just `base.apk`.
- Resource/localisation extraction (strings remain inline).
- Committed Gradle wrapper (CI uses `setup-gradle`).
- Preflight / split-selection and Compose unit tests (`ShellParsingTest` exists).
- Bound-service ownership of job state; the service is notification-only today.
- SDK 36 bump, baseline profile/R8 release validation.

The `keepBackups = 0` behaviour intentionally deviates from the SRS text: it keeps
all backups instead of allowing a backup-free swap, and surfaces a red warning.

---

## 5. Closing the audit

1. Push the tree and confirm CI `assembleDebug` + `testDebugUnitTest` are green. Fix
   any compiler diagnostics reported there; none can be reproduced locally.
2. Run `scripts/m0-probe.sh` and fill `docs/SPIKE.md` §4. That decides whether the
   file-swap mechanism is viable on the target ROMs.
3. Run the M5 device/fault-injection checklist and record results here.
4. Treat the deferred list above as a product decision: none of it blocks the
   must-have v0.1 flow, but each item remains visible rather than silently dropped.
