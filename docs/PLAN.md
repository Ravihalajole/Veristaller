# Veristaller — Development Plan

| | |
|---|---|
| Version | 1.0 |
| Basis | `docs/SRS.md` v1.0, `docs/ARCHITECTURE.md` v1.0 |
| Estimation unit | 1 dev-day ≈ 6 focused hours, single developer |
| Build environment | Gradle 8.11 / AGP 8.9 / JDK 17 (CI or desktop; an on-phone build needs ≥ 6 GB free RAM and is slow — see §7) |

> **Execution status:** this is the original schedule. M1–M4 are implemented in
> the current tree with the versions from `gradle/libs.versions.toml` (AGP 8.7.3,
> Gradle 8.9 in CI, SDK 35). M0 and M5 remain unrun; `docs/COVERAGE.md` records
> exactly which acceptance criteria still need hardware or CI.

---

## 1. Strategy

The product risk is **not** the UI. It is three device behaviours we cannot
guarantee from documentation:

1. whether `fs-verity` blocks the unlink+rename swap on a given OEM kernel,
2. whether the boot-time package scan re-validates signatures against
   `/data/system/packages.xml`,
3. whether splits signed with the bundle key keep loading when `base.apk` is
   signed with a different key.

Therefore the plan front-loads a **one-day spike (M0)** that answers all three on
real hardware before any UI is written. If a behaviour is hostile, the product
direction changes (framework-level install instead of file swap) and we find out
cheaply.

Everything after M0 is built in vertical slices: each milestone ends with something
installable and demonstrable on a phone.

---

## 2. Milestones

### M0 — Feasibility spike (1.5 d) ← blocking

**Goal:** prove the end-to-end shell sequence by hand on at least two Android
versions, then encode the findings.

| # | Task | Output |
|---|---|---|
| 0.1 | Prepare fixtures: build a 3-split bundle with `bundletool build-apks` from a sample open-source app; make a patched base APK (re-signed, versionCode bumped) for it. | `fixtures/notespro-4.2.1.apks`, `fixtures/notespro-patched.apk` |
| 0.2 | Manual run of the full sequence on API 29 / 33 / 35: session install → `pm path` → force-stop → backup → `rm`+`mv`+`chown`+`chmod`+`chcon` → clear `oat` → launch. | `docs/SPIKE-RESULTS.md` |
| 0.3 | Test verity: does `cp` into `base.apk` fail? Does `rm` then `mv` succeed? Does the new inode carry `security.fsverity`? | verdict per device |
| 0.4 | Test reboot: after swap, reboot and confirm the app still launches (i.e. boot scan did not reject the signature). | verdict per device |
| 0.5 | Test split signature mismatch: bundle signed A, base signed B, splits A — launch result. | verdict per device |
| 0.6 | Test `chcon` vs `restorecon` behaviour on a hidden temp file, and whether `restorecon -F` is available. | verdict |
| 0.7 | Record exact owner/mode/context of a stock install on each device to use as the assertion baseline. | baseline table |

**Exit criteria:** at least one device shows a working swap; the failure modes with
their exact errno and `logcat` lines are documented; a go/no-go decision is written
for "file swap" vs "framework install" as the v1 mechanism.

**If M0 fails on all devices:** pivot to the `SwapStrategy` seam — implement the
install-only path plus a documented `su`-script export, and revisit with a
PackageManager signature-bypass approach (larger scope, needs a system-side patch).

---

### M1 — Skeleton, root layer, design system (2 d)

| # | Task | Refs |
|---|---|---|
| 1.1 | Gradle project, version catalog, signing config (debug + release placeholder), `minSdk 26 / target 36`, `allowBackup=false`, no INTERNET | NFR-5/7 |
| 1.2 | Package skeleton per ARCHITECTURE §3, `AppContainer`, `VeristallerApp` | — |
| 1.3 | Theme: Material 3 dark-first palette, type scale, shapes, dynamic color on 31+ | FR-12.7 |
| 1.4 | `RootShell` on libsu: persistent shell, mutex, sentinel exit parsing, timeout, dry-run renderer | FR-1.5 |
| 1.5 | `RootProbe`: root + manager identification + tool check + device facts | FR-1.1–1.3 |
| 1.6 | `ShellScript` builder + `shQuote` + unit tests for quoting edge cases | §5.2 |
| 1.7 | Home screen with real root status chip and device facts card | FR-1.1 |
| 1.8 | Foreground service shell with a fake 3-step job | §9 |

**Exit:** app installs, shows real root/tool status, and runs a fake job that
survives rotation and backgrounding.

---

### M2 — Input, inspection, preflight (2.5 d)

| # | Task | Refs |
|---|---|---|
| 2.1 | SAF pickers + persisted URI + staging copy with progress | FR-2.1, 2.7 |
| 2.2 | `ApkInspector` via `getPackageArchiveInfo` (pkg, versions, SDKs, ABIs, cert hash) | FR-2.5 |
| 2.3 | `BundleInspector` + 4 adapters, central-directory-only enumeration, zip-slip guard | FR-2.3, 2.4, 2.8 |
| 2.4 | Split classifier (master/ABI/density/language) + unit tests on real fixture names | FR-4.2 |
| 2.5 | `InstalledAppReader` — version, codePath, cert, running state via `dumpsys`/`pm` | FR-3.3, 3.7 |
| 2.6 | `Preflight` pure function → `List<CheckResult>`; 12 checks, severities, copy | FR-3.1–3.10 |
| 2.7 | Review screen: check rows, warning acknowledgement, command preview | FR-3.9/3.10 |
| 2.8 | Danger guard (system packages, self, root manager) | FR-3.8 |

**Exit:** the two files can be picked and fully analysed, the review screen shows
correct verdicts, and golden tests cover mismatch/downgrade/danger cases.

---

### M3 — Install + swap engine (3 d)

| # | Task | Refs |
|---|---|---|
| 3.1 | `InstallOp`: session create/write/commit/abandon, split filtering, 3-attempt ladder, `INSTALL_FAILED_*` mapper | FR-4.1–4.5 |
| 3.2 | `PathOp`: `pm path` + `codePath` cross-check + deep `find` + `assertSafe` | FR-5.1–5.4 |
| 3.3 | `BackupRepository`: create, hash-verify, `meta.json`, prune, restore | FR-6.1–6.4, 9.2 |
| 3.4 | `SwapTransaction`: temp file, attrs-before-rename, verity-aware replace, relabel, clear `oat` | FR-7.1–7.9 |
| 3.5 | `VerifyOp`: size, attrs, `pm path`, optional launch+logcat smoke test | FR-8.1–8.3 |
| 3.6 | Rollback paths + `job.inprogress` marker + crash recovery prompt | FR-9.1/9.3, FR-13.4 |
| 3.7 | `JobRunner` pipeline emitting `JobEvent`s; dry-run mode honoured everywhere | FR-12.1 |

**Exit:** UC-1 and UC-4 pass on a real device from a debug menu (no polished UI yet).

---

### M4 — Full UI (2.5 d)

| # | Task | Refs |
|---|---|---|
| 4.1 | Navigation graph, scaffold, bottom bar, back handling | — |
| 4.2 | Progress screen: stepper, per-stage detail, live log tail with pause, cancel | FR-7.9, 11.2 |
| 4.3 | Result screen: success/rolled-back/failed variants, open app, restore, export | FR-8, 9 |
| 4.4 | History + job detail + backup rows + restore confirmations | FR-9.4, 10.1–10.3 |
| 4.5 | Settings: dry run, retention, installer pkg, clear-oat, split mode, theme | FR-12 |
| 4.6 | First-run disclosure + danger confirmations (typed app name for system apps) | FR-13.1/13.2 |
| 4.7 | Empty/loading/error states, motion, haptics, screen-on | — |
| 4.8 | TalkBack pass, 48 dp targets, contrast audit | NFR-9 |

**Exit:** the prototype's every screen exists in Compose and matches
`docs/UI_SPEC.md`; golden-path video recorded.

---

### M5 — Hardening and matrix (3 d)

| # | Task | Refs |
|---|---|---|
| 5.1 | Fault injection harness: abort at each step, kill the process mid-swap, corrupt backup, read-only dir | AC-3 |
| 5.2 | Device matrix run (see §5), log verdicts, file OEM quirks | §13 arch |
| 5.3 | KernelSU + APatch smoke tests (different su semantics: no `magisk` mount, JSON UID policy) | NFR-8 |
| 5.4 | Performance pass against §12 budgets; APK size check | NFR-1–4 |
| 5.5 | Log export + redaction review | FR-11.3 |
| 5.6 | Localization extraction, string audit | NFR-10 |
| 5.7 | Release build: R8, resource shrink, baseline profile, signing | — |

**Exit:** AC-1…AC-8 all green on the primary device set; known-issues list written.

---

### M6 — Release (1 d)

Release notes, screenshots, README with device support table, tag `v1.0.0`, optional
F-Droid-style reproducible build doc. No store distribution planned (root tool).

---

## 3. Schedule

| Week | Milestones | Notes |
|---|---|---|
| 1 | M0 (1.5 d) + M1 (2 d) | M0 result may re-plan the week |
| 2 | M2 (2.5 d) + start M3 | |
| 3 | M3 finish (2 d) + M4 start | first real end-to-end demo |
| 4 | M4 finish + M5 (3 d) | matrix testing is the long pole |
| 5 | M5 finish + M6 | |

**Total:** ~15.5 dev-days of build, ~2 weeks calendar at part-time pace, 3 weeks
comfortably with matrix testing.

Critical path: M0 → M3 (swap) → M5 (matrix). UI (M2/M4) can be parallelized with a
second pair of hands; the root/domain work cannot.

---

## 4. Backlog (post-v1)

| Idea | Value | Notes |
|---|---|---|
| Swap re-signed splits too | High for heavily-modded apps | Needs split signature consistency analysis |
| Preset "recipes" per package (bundle + patch remembered) | High | Auto-detect updates |
| Batch mode (queue several apps) | Medium | JobRunner is already a queue |
| A/B compare: diff manifest/permissions before swap | Medium | Show newly added permissions |
| Shizuku/adb backend | Medium | Non-root fallback, limited by verity |
| Widget / quick tile "restore last" | Low | |
| Local backup of app data before uninstall-escape-hatch | Medium | `tar` of `/data/data/<pkg>` as root |

---

## 5. Test plan

### 5.1 Layers

| Layer | Tool | What |
|---|---|---|
| Unit | JUnit4 | `shQuote`, sentinel parsing, split classification, path parsing, preflight truth table, error mapping |
| Golden | JUnit4 + fixtures | Real `pm`/`stat`/`dumpsys` output strings from 5 Android versions → parsers |
| Robolectric | Compose + repos | Settings defaults, history pruning, backup naming |
| Instrumented | Compose test | Flow navigation, disabled Start on FAIL, warning gating |
| E2E (rooted) | Manual + scripted adb | Full UC-1…UC-5 per device |

### 5.2 Device matrix (minimum for AC-1)

| Device/ROM | Android | Root | Priority |
|---|---|---|---|
| Pixel (AOSP-ish) | 15/16 | Magisk | P0 |
| Pixel | 13 | Magisk | P0 |
| Budget OEM (e.g. Redmi) | 12 | Magisk | P0 |
| Old device / emulator | 9/10 | Magisk on AVD | P1 |
| LineageOS | 14 | KernelSU | P1 |
| One UI | 14 | Magisk | P2 |
| HyperOS | 15 | APatch | P2 |

Fixtures: `notespro` (3 splits, small), a 150 MB app (performance), an app with a
native lib (16 KB alignment noise), a game with `split_config.*` language splits
(density/language filtering), and a system-app-with-update (FR-7.8).

### 5.3 Fault injection checklist (AC-3)

| Injected fault | Expected |
|---|---|
| Root revoked after preflight | `E_ROOT_DENIED`, nothing changed |
| Zip truncated during staging | `E_BUNDLE_CORRUPT`, no install session |
| `pm install-commit` fails | session abandoned, stock app untouched |
| `/data` full mid-backup | `E_NOT_ENOUGH_SPACE`, temp removed |
| Backup hash mismatch | abort before any write to `base.apk` |
| `rm base.apk` → EPERM | `E_VERITY_LOCKED`, backup restored, app launches |
| `chcon` denied | `E_SELINUX_DENIED`, rollback, recovery block shown |
| Process killed between `rm` and `mv` | next launch detects marker, restores backup |
| Patch APK zero-length | preflight FAIL (size > 0), never starts |
| Reboot immediately after swap | app launches; result recorded as "unverified" |

---

## 6. Risk register

| ID | Risk | Prob | Impact | Mitigation | Trigger |
|---|---|---|---|---|---|
| R1 | `fs-verity` makes the swap impossible on some kernels | M | High | M0; unlink+recreate; per-device verdict table; fallback messaging | M0 |
| R2 | Boot-time scan rejects the new signature | L–M | High | M0 reboot test; if hostile, document "works until reboot" and offer auto-restore on boot (needs a boot receiver + root) | M0 |
| R3 | Splits signed by another key stop loading | M | Medium | M0 test; ship "base only" and warn; future: re-sign splits | M0/M2 |
| R4 | OEM changes `/data/app` layout again | M | Medium | Depth-agnostic resolver + `find` fallback + unsafe-path guard | M5 |
| R5 | `restorecon`/`chcon` semantics differ (or missing) | M | Medium | Try `chcon` → `restorecon -F` → `restorecon`; probe tools at start | M1 |
| R6 | Root manager prompt per command | L | Low | Single persistent shell (FR-1.5) | M1 |
| R7 | App detects tampering and exits / Play Integrity | H | Low (by design) | Out of scope; state it in the UI and README | — |
| R8 | User swaps a system-critical app and bootloops | L | High | Danger denylist + typed confirmation + only `/data/app` writes | M4 |
| R9 | Process death mid-swap | L | Medium | In-progress marker + backup + recovery prompt | M3 |
| R10 | Wrong file picked (patch for another app/version) | M | Low | Package+version checks, blocking mismatch | M2 |
| R11 | Android 16 16 KB page-size apps | L | Low | Only matters for the sample app, not for us; note in README | M5 |
| R12 | Signature check inside `pm` blocks bundle install over a Play-installed app | H | Medium | `E_ALREADY_INSTALLED_CONFLICT` + guided uninstall (data loss warning) | M2 |

---

## 7. Build environment note

The current workspace (`/storage/emulated/0/workspace/Veristaller`) has no JDK,
Gradle or Android SDK. Two options:

1. **Recommended:** build on a desktop/CI (GitHub Actions `ubuntu-latest` with
   `android-actions/setup-android`), push APKs to the phone for testing.
2. On-device: install JDK 17 + Android SDK cmdline-tools (~1.5 GB) — possible on this
   phone but slow, memory-hungry, and Android may kill the daemon. Only do it if a
   desktop is unavailable.

Design/planning artifacts and the HTML prototype need no toolchain.

---

## 8. Definition of done (per milestone)

- Code merged, `assembleDebug` green, unit tests green.
- Every new privileged command appears in the command catalogue (ARCHITECTURE §5.3)
  and in the log.
- New strings in `strings.xml` with a11y labels.
- Manual run on at least one physical device, screenshot attached to the milestone.
- Docs updated (`SRS` requirement IDs ↔ PR description).

---

## 9. Immediate next actions

1. Approve SRS open questions Q-1…Q-5 (defaults are pre-selected).
2. Run M0 on your device — I can generate the exact shell script and the fixture
   build commands next.
3. Review `design/prototype.html` and mark which screens to change.
4. On approval, scaffold the Gradle project (M1.1–1.3) so the first buildable APK
   exists.
