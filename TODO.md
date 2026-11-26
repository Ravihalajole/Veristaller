# Veristaller — Development Todo

Legend: `[x]` done · `[~]` in progress · `[ ]` todo

Audit status: `docs/COVERAGE.md` maps every SRS requirement to code and records
the deliberate v0.1 gaps. Code is static-ready; CI has not yet compiled it.

## M0 — Spike (manual, on device)

- [ ] Run `scripts/m0-probe.sh` on API 29 / 33 / 35 devices
- [ ] Fill `docs/SPIKE.md` §4 device table
- [ ] Verdict on fs-verity (`rm` + `mv` works?)
- [ ] Verdict on boot-time signature scan (survives reboot?)
- [ ] Verdict on split loading with re-signed base
- [ ] Go / no-go for the file-swap approach

## M1 — Scaffold + root layer

- [x] Todo list
- [x] Gradle build files + version catalog
- [x] Manifest, app theme, launcher icon
- [x] CI workflow (`.github/workflows/android.yml`)
- [x] `ShellScript` + `shQuote`
- [x] `RootShell` (libsu wrapper, mutex, dry-run)
- [x] `RootProbe` (root, manager, tools, device facts)
- [x] `RootOps` command builders
- [x] `AppContainer` + `VeristallerApp`

## M2 — Input, inspection, preflight

- [x] `StagingStore` (SAF import + zip extraction, zip-slip guard)
- [x] `ApkInspector` (`getPackageArchiveInfo`)
- [x] `BundleInspector` (`.apks` / `.apkm` / `.xapk` / `.apk`)
- [x] `InstalledAppReader`
- [x] `Preflight` checks + ack gating
- [x] Settings (DataStore)
- [x] History + backups (JSON)

## M3 — Job engine

- [x] `JobRunner` pipeline + events
- [x] Session install with split filter retry ladder
- [x] Path resolution (depth-agnostic) + safety guard
- [x] Backup with sha256 verification
- [x] Atomic swap (verity-aware) + fixup
- [x] Verify + automatic rollback
- [x] Dry-run plan
- [x] Restore from backup
- [x] Foreground notification service

## M4 — UI

- [x] Theme (dark / light / system, dynamic colour on API 31+)
- [x] Home + disclosure dialog
- [x] Pick bundle / pick patch
- [x] Review (checks, acks, command preview)
- [x] Progress (stepper + live log)
- [x] Result (success / rollback / fail / dry-run)
- [x] History + backups
- [x] Settings
- [x] Navigation

## M5 — Verification (after first green CI build)

- [ ] First CI build green (fix any compile errors — nothing has been compiled yet)
- [ ] Install debug APK, grant root, confirm probe (manager, ABI, free space, tools)
- [ ] Real `.apks` fixture parses correctly (split list, skipped splits, version)
- [ ] End-to-end job on a test app (install → locate → backup → swap → verify)
- [ ] Fault injection: abort at each step, confirm rollback and history record
- [ ] Restore from History and from the result screen
- [ ] Bump `compileSdk`/`targetSdk` to 36
- [ ] Baseline profile, R8 release build
- [ ] TalkBack + contrast pass
- [ ] Promote FGS to bound service (progress notification)

## Known gaps (deliberate, v0.1)

- [ ] Cancel a running job (currently disabled while the engine works)
- [x] Per-command timeline in the history detail dialog (log tail + command lines)
- [x] Export logs and diagnostics via SAF `CREATE_DOCUMENT`
- [ ] Recent-file shortcuts with persisted SAF permissions (FR-2.6, should)
- [ ] Bundle split certificate vs patch warning (FR-3.5, should)
- [ ] Bundle-only diagnostic mode (FR-4.6, should)
- [ ] Live post-swap version re-read / `lastUpdateTime` note (FR-8.4, could)
- [ ] Re-sign / swap splits, not just base
- [ ] Localisation extraction (strings are inline in v0.1)
- [ ] `gradle wrapper` committed (CI uses `setup-gradle`)
- [ ] Unit tests for `Preflight` and split selection (`ShellParsing` covered)
- [x] Process-death recovery banner with verified restore (`job.inprogress` marker)
- [ ] Automatic restore without user confirmation (manual banner action in v0.1)
- [ ] Roman/per-locale string resources (all UI strings inline in v0.1)
- [ ] `keepBackups=0` means "no backup" per SRS FR-12.2; shipped code keeps every
      backup and shows a red warning so a swap is never backup-free
