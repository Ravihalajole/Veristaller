# Veristaller

A root-only Android tool that installs a split bundle, then replaces the installed
app's `base.apk` with a patched one. No backup is taken and there is no undo or
automatic rollback: if a swap fails after the original APK was removed, recovery is
by reinstalling the target app. This is an accepted v0.2 trade-off.

This repository holds the **plan and specification**, the **clickable GUI prototype**,
and a **complete first-pass Android implementation** (Kotlin + Compose). The code has
not been compiled yet: this workspace has no JDK/SDK, so the first build runs on
GitHub Actions (see below).

---

## Build

CI builds every push (`.github/workflows/android.yml`) and uploads a debug APK:

1. Push the repository to GitHub.
2. Open **Actions → Android CI → latest run**.
3. Download the `veristaller-debug` artifact and install it on the rooted device.
   Debug builds are signed with the committed `debug.p12`, so later builds update
   in place. If the installer reports a signature mismatch, uninstall the older
   debug build first (builds before `0.1.1` used a throwaway per-run CI key).

Locally, with JDK 17 and Android SDK 35 installed:

```bash
gradle :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

There is no Gradle wrapper in the repo; use a local Gradle 8.9+ or let Android
Studio generate one (`gradle wrapper --gradle-version 8.9`).

## Files

| Path | What it is |
|---|---|
| `docs/SRS.md` | Requirements: FR-1…FR-13, NFRs, use cases, acceptance criteria |
| `docs/ARCHITECTURE.md` | Tech stack + rationale, module layout, root layer, path resolution, swap transaction, error taxonomy, compatibility matrix |
| `docs/PLAN.md` | Milestones M0–M6, backlog, test plan, risk register, schedule |
| `docs/UI_SPEC.md` | Screen-by-screen spec, design tokens, copy rules, Compose mapping |
| `docs/SPIKE.md` | The M0 hands-on validation script/checklist (run this first) |
| `scripts/m0-probe.sh` | Read-only device probe: root, tools, code path, attrs, verity hints, splits |
| `design/prototype.html` | Clickable prototype — 8 screens + board view |
| `app/` | Android app: root layer, inspectors, job engine, Compose UI |
| `.github/workflows/android.yml` | CI: JDK 17, Gradle 8.9, `assembleDebug`, APK artifact |
| `TODO.md` | Milestone checklist and deliberate v0.1 gaps |

## Open the prototype

On the phone, open:

```
file:///storage/emulated/0/workspace/Veristaller/design/prototype.html
```

`Flow` walks the real user journey (pick two files → review → 6-stage job → result).
`Board` shows every screen at once for design review. No server, no network, no
build step.

---

## Decisions at a glance

| Question | Decision |
|---|---|
| Language / UI | Kotlin 2.0.21, Jetpack Compose (BOM 2024.10.01), Material 3, single Activity |
| Root access | `libsu` (Topjohnwu), root proven with `su -c "id -u"`, one persistent shell when libsu can drive the manager and one `su -c` per command otherwise; all scripts built by a quoting-safe builder |
| Install mechanism | `pm install-create / install-write / install-commit` as root |
| Swap mechanism | sibling temp file → `chown 1000:1000` + `chmod 0644` + `chcon apk_data_file` → `rm` + `mv` (fs-verity aware) → `restorecon -F` → clear `oat/` |
| Path discovery | `pm path` + `codePath` cross-check, then bounded `find` — **never** a hardcoded depth |
| Verification | size, owner, mode, SELinux label, `pm path`, optional launch + crash-log scan |
| Backup / undo | None. No backup is taken, no rollback or restore UI; recovery from a failed swap is by reinstalling the target app (accepted trade-off) |
| Permissions | **no `INTERNET`**, no analytics, nothing exported |
| Persistence | DataStore for settings (no Room, no persisted job history or backups) |
| Build | AGP 8.7.3, `minSdk 26`, `compileSdk`/`targetSdk` 35, debug APK |

## The three unknowns (why M0 comes first)

1. **fs-verity** — on Android 11+ APKs under `/data/app` may be immutable, so an
   in-place write fails and only `unlink` + `rename` works.
2. **Boot-time scanning** — whether a differently-signed `base.apk` survives a
   reboot (the package database is not updated by the swap).
3. **Split signatures** — whether splits signed with the bundle key still load when
   the base is signed with the patcher's key.

M0 answers all three in about a day. `docs/SPIKE.md` has the exact commands and a
results table; `scripts/m0-probe.sh` collects the device facts.

---

## Assumptions (change them now if wrong)

- Target devices: Android 8–16, rooted with Magisk / KernelSU / APatch.
- The bundle and the patched APK are for the same package, ideally the same version.
- One user (user 0); no work profiles.
- The tool never produces a patched APK and never bypasses framework signature
  checks — it only performs the file replacement.
- Distribution is sideload / F-Droid style, not Play Store.

## Suggested next step

Push to GitHub and let CI produce the first APK. Then run the M0 probe
(`scripts/m0-probe.sh`) on the target device and fill in `docs/SPIKE.md` §4 — the
swap engine is written to report fs-verity and SELinux failures as typed results,
but only hardware can confirm the three unknowns above. A failed swap may leave the
target app unusable until it is reinstalled.
