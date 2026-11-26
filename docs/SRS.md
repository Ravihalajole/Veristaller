# Veristaller — Software Requirements Specification

| | |
|---|---|
| Product | Veristaller |
| Package | `dev.veristaller` |
| Document version | 1.0 (draft for review) |
| Date | 2026-11-25 |
| Status | For approval |

---

## 1. Purpose

Veristaller is a **root-only Android utility** that replaces the `base.apk` of an
already-installed application with a user-supplied, modified APK, while keeping the
application's original split APK set, data directory, permissions and UID intact.

It exists because the normal install path cannot express "install this split bundle,
then swap only its base APK". Doing it by hand requires locating the randomized
`/data/app` code path, stopping the app, defeating file verity, restoring
`system:system 0644` ownership and the `apk_data_file` SELinux label, and clearing
stale ART artifacts. Veristaller automates that sequence, makes it observable, and
makes it reversible.

### 1.1 Problem statement

Given:

- **A** — an `.apks` / `.apkm` / `.xapk` split bundle for package `P`, version `V`
- **B** — a single patched `.apk` for the same `P` and `V`
- a rooted device with SELinux enforcing

produce a device state where `P` runs `B`'s code with `A`'s splits, config, native
libraries and existing user data — safely, repeatably, and with a one-tap undo.

### 1.2 Goals

- G1. One guided flow from two picked files to a verified running app.
- G2. Every privileged step is atomic or reversible; a failure never leaves a
  half-written APK behind.
- G3. The user can see exactly what will be executed (command preview + full log).
- G4. Layout-agnostic: works on Android 8 → 16 regardless of how deep the
  code path nests `base.apk`.
- G5. Zero network access. The app holds no `INTERNET` permission at all.

### 1.3 Non-goals

- Patching, decompiling, re-signing or "modding" an APK. Veristaller consumes a
  finished patched APK; it never produces one.
- Signature-verification bypass at the framework level. If the ROM enforces
  signature equality, Veristaller reports it instead of fighting it.
- Bypassing Play Integrity / SafetyNet attestation, licence servers or server-side
  checks.
- Non-root installation, Xposed/LSPosed module management, or system-app conversion.

---

## 2. Definitions

| Term | Meaning |
|---|---|
| **Bundle** | A container file holding a base APK plus split APKs (`.apks`, `.apkm`, `.xapk`, or a plain directory of APKs). |
| **Patch** | The single user-supplied replacement `base.apk`. |
| **Code path** | The directory PackageManager created for the app, e.g. `/data/app/~~AbC==/com.x-YYY==/`. |
| **Swap** | Replacing `base.apk` inside the code path. |
| **Fixup** | Restoring owner/group, mode, SELinux context and clearing stale artifacts. |
| **Verity** | `fs-verity` Merkle-tree protection applied to APK files on Android 11+. A protected file cannot be modified in place. |
| **Session install** | `pm install-create` → `pm install-write` × N → `pm install-commit`. |
| **Root manager** | Magisk, KernelSU, APatch, or a `su` binary on a userdebug build. |

---

## 3. Actors and environment

| Actor | Description |
|---|---|
| **User** | Device owner, technical enough to have rooted the phone. |
| **Root manager** | Grants or denies `su`; may impose its own SELinux domain and mount namespace. |
| **PackageManager (system_server)** | Owns the install transaction and the code path. |
| **Veristaller** | Orchestrates the above. |

### 3.1 Assumptions

- A1. The device is rooted and `su -c id -u` returns `0` non-interactively after the
  user grants the prompt.
- A2. `pm`, `toybox` (or coreutils), `restorecon`/`chcon`, `stat` and `rm` are
  available on `PATH` for the root shell.
- A3. The bundle and patch are for the **same package name**. Version equality is
  expected but not mandatory (a mismatch becomes a blocking warning the user can
  override).
- A4. The bundle's split APKs are signed by the same key as the base APK inside
  that bundle (normal for store-signed bundles).

### 3.2 Constraints

- C1. Android 8.0 (API 26) minimum; validated through API 36.
- C2. `base.apk` may sit at `/data/app/<pkg>/`, `/data/app/<pkg>-<rand>/`, or
  `/data/app/~~<rand>==/<pkg>-<rand>==/`. Depth must be discovered, never assumed.
- C3. On API 30+ the file may be `fs-verity` protected. In-place writes fail with
  `EIO`/`EPERM`; the file must be unlinked and recreated.
- C4. The app must stay functional if the user denies the root prompt, and must not
  crash or leave staging files behind.
- C5. Phone-class resources: no heavy native toolchain, no JVM daemon, small APK.

---

## 4. Functional requirements

Priority: **M** = must, **S** = should, **C** = could.

### FR-1 Root environment

| ID | Req | Pri |
|---|---|---|
| FR-1.1 | Detect root on launch and show a status chip: granted / denied / not rooted, with root manager name (Magisk, KernelSU, APatch) when identifiable from `getprop` / mount info. | M |
| FR-1.2 | Verify the required tools (`pm`, `stat`, `rm`, `restorecon` or `chcon`, `getenforce`) and report the exact missing ones. | M |
| FR-1.3 | Report device facts used later: `ro.build.version.sdk`, `ro.product.cpu.abilist`, `ro.product.cpu.abilist32/64`, SELinux mode, `/data` free space. | M |
| FR-1.4 | Re-check root before starting a job; if lost, fail with `E_ROOT_DENIED` and no side effects. | M |
| FR-1.5 | Never prompt for root more than once per job; batch commands into one shell session. | S |

### FR-2 Input

| ID | Req | Pri |
|---|---|---|
| FR-2.1 | Pick the bundle via SAF (`ACTION_OPEN_DOCUMENT`), accepting `.apks`, `.apkm`, `.xapk`, `.apk`, `.zip`. | M |
| FR-2.2 | Pick the patch APK the same way, restricted to `.apk`. | M |
| FR-2.3 | Detect the bundle format by content, not extension: `toc.pb` → bundletool `.apks`; `meta.json` → `.apkm`; `manifest.json` → `.xapk`; `AndroidManifest.xml` at root → plain APK; else "unknown container". | M |
| FR-2.4 | Enumerate splits with their sizes and ABI/density/language qualifiers parsed from file names. | M |
| FR-2.5 | Read package name, versionCode, versionName, min/target SDK, ABI list and signing certificate SHA-256 from the patch without root (`PackageManager.getPackageArchiveInfo`). | M |
| FR-2.6 | Remember the last used files and offer them as "recent" entries, with persisted URI permission. | S |
| FR-2.7 | Stream-copy the selection into private staging so the swap is immune to the SAF provider revoking access mid-job. | M |
| FR-2.8 | Reject zip entries with absolute paths or `..` traversal when extracting. | M |

### FR-3 Preflight

| ID | Req | Pri |
|---|---|---|
| FR-3.1 | Compare package names of bundle base and patch; mismatch is blocking. | M |
| FR-3.2 | Compare versionCodes; mismatch is a warning requiring explicit confirmation, and flagged "downgrade" when patch < bundle. | M |
| FR-3.3 | Resolve the currently installed package, its versionCode, code path and signing certificate. If not installed, block with "install the bundle first" (or offer bundle-only mode). | M |
| FR-3.4 | Warn when the patch signing certificate differs from the installed app's, explaining that signature-gated ROMs and paid apps may refuse to launch. This is expected for patched apps and is not blocking. | M |
| FR-3.5 | Warn when the bundle contains split APKs whose certificate differs from the patch (the patch author may have re-signed only the base). | S |
| FR-3.6 | Check free space on `/data` ≥ (bundle + patch + 200 MB) and refuse otherwise. | M |
| FR-3.7 | Detect the target app running (`pidof`, `dumpsys activity processes`) and require force-stop. | M |
| FR-3.8 | Refuse dangerous targets: `android`, `com.android.*` system packages, the root manager, the installer, and Veristaller itself. Allow with an explicit "I know what I'm doing" toggle only for `com.android.*` non-core packages. | M |
| FR-3.9 | Show the resolved code path and the exact commands to be run (expandable command preview, redacted of nothing — it is all local). | M |
| FR-3.10 | Summarize checks as PASS / WARN / FAIL rows; the Start button is enabled only with no FAIL and all WARNs acknowledged. | M |

### FR-4 Bundle installation

| ID | Req | Pri |
|---|---|---|
| FR-4.1 | Install the bundle through a `pm` session as root, with `-r` (keep data) and the configured installer attribution (default `com.android.vending`, toggleable in Settings). | M |
| FR-4.2 | Filter splits to the device: keep `master`/base splits, ABI splits matching `abilist`, density splits matching the closest bucket (`nodpi`/`anydpi` always kept), and language splits for device locales + `en`. | M |
| FR-4.3 | If the filtered set is rejected by PackageManager, retry once with ABI filtering only, then once with the full split set, reporting which strategy succeeded. | S |
| FR-4.4 | Never install `toc.pb`, `meta.json`, `manifest.json`, icons, OBB data or `standalones/` when `splits/` exists. Use `universal.apk` / `standalones` only when no split set exists. | M |
| FR-4.5 | Map `pm` failure strings to a typed error and show the raw output (`INSTALL_FAILED_*`) on demand. | M |
| FR-4.6 | Support "bundle only" mode (install, no swap) as a diagnostic step. | S |

### FR-5 Code path resolution

| ID | Req | Pri |
|---|---|---|
| FR-5.1 | Resolve the code path from `pm path <pkg>` (primary) and cross-check with `cmd package dump <pkg>` `codePath=` (secondary). | M |
| FR-5.2 | If `base.apk` is not directly inside the resolved directory, search downward up to 4 levels with `find ... -name base.apk`, then upward one level, covering legacy and deeply nested layouts. | M |
| FR-5.3 | Require the final path to match `/data/app/**/base.apk` and to belong to the requested package; otherwise abort with `E_PATH_UNSAFE`. | M |
| FR-5.4 | Confirm the directory is writable by root (`test -w`), is not a symlink outside `/data/app`, and that the path is not on a read-only mount. | M |

### FR-6 Backup

| ID | Req | Pri |
|---|---|---|
| FR-6.1 | Before swapping, copy the current `base.apk` to `/data/local/tmp/veristaller/backups/<pkg>/<versionCode>-<epoch>/base.apk`. | M |
| FR-6.2 | Write a `meta.json` beside it: package, versionCode, versionName, original path, size, sha256, owner/group/mode/SELinux label, SDK, timestamp, patches applied. | M |
| FR-6.3 | Verify the backup's sha256 matches the source before proceeding. | M |
| FR-6.4 | Retain the N most recent backups per package (default 3) and prune older ones. | S |
| FR-6.5 | Surface backups in History with size and one-tap restore. | M |

### FR-7 Swap and fixup

| ID | Req | Pri |
|---|---|---|
| FR-7.1 | Force-stop the target package and verify no process remains. | M |
| FR-7.2 | Write the patch to a sibling temp file (`.<name>.veristaller.tmp`) in the **same directory** so the final rename is atomic. | M |
| FR-7.3 | Apply `chown 1000:1000` (system:system), `chmod 0644`, and `chcon u:object_r:apk_data_file:s0` (or `restorecon`) to the temp file **before** it becomes `base.apk`. | M |
| FR-7.4 | Replace via `rm -f base.apk` then `mv` temp → `base.apk`, handling the fs-verity case: if in-place write fails, unlink and recreate; if unlink fails, fall back to `mv -f` and report `E_VERITY_LOCKED`. | M |
| FR-7.5 | Re-apply the SELinux label to the final file and verify with `ls -Z` / `stat -c %C`, retrying with `restorecon -F`. | M |
| FR-7.6 | Remove `<codePath>/oat/` (and `oat_primary`/`oat_secondary` if present) so ART recompiles against the new dex. | M |
| FR-7.7 | Never touch split APKs, `lib/`, `dm/`, or the app's data directory in the swap. | M |
| FR-7.8 | When the target is a system app updated in `/data`, swap the `/data` copy, not the system image. | S |
| FR-7.9 | Emit a machine-readable step event for every command (started / ok / failed, exit code, duration). | M |

### FR-8 Verification

| ID | Req | Pri |
|---|---|---|
| FR-8.1 | Verify `pm path` still resolves and the file size equals the patch size. | M |
| FR-8.2 | Verify owner, mode and SELinux context of the final file. | M |
| FR-8.3 | Offer "Smoke test": launch via `monkey -p <pkg> 1`, wait 5 s, then scan `logcat -d` for `FATAL EXCEPTION` mentioning the package; report crash / ok / inconclusive. | S |
| FR-8.4 | Show the resulting app version as reported by PackageManager (`dumpsys package`) if it changed, and note that `lastUpdateTime` is unchanged because the swap bypassed PackageManager. | C |

### FR-9 Rollback and restore

| ID | Req | Pri |
|---|---|---|
| FR-9.1 | On any failure after the swap begins, attempt automatic rollback: remove the temp file, restore the backup if `base.apk` is missing or zero-length, re-apply fixups, and report the outcome. | M |
| FR-9.2 | Manual restore from History: force-stop, copy backup back with fixups, clear `oat/`, verify. | M |
| FR-9.3 | If automatic rollback fails, keep the backup and show a copyable recovery command block. | M |
| FR-9.4 | Record every job in history with status, timings, resolved path, error code and log file reference. | M |

### FR-10 History

| ID | Req | Pri |
|---|---|---|
| FR-10.1 | List jobs newest first: app label/icon, version, result badge, timestamp, duration. | M |
| FR-10.2 | Detail view with the full step timeline and the exact commands executed. | S |
| FR-10.3 | Long-press actions: restore, export log, delete entry (+ optionally delete its backup). | S |
| FR-10.4 | Cap history at 200 entries with automatic pruning. | C |

### FR-11 Logging and diagnostics

| ID | Req | Pri |
|---|---|---|
| FR-11.1 | Persist a per-job log to `files/logs/<jobId>.log`, including stdout/stderr of every privileged command with timestamps. | M |
| FR-11.2 | In-app live log tail during the job, monospace, auto-scroll with pause on manual scroll. | S |
| FR-11.3 | Export a diagnostic bundle (log + device facts + job meta, no signing keys) via SAF `CREATE_DOCUMENT`. | S |
| FR-11.4 | Redact nothing except certificate fingerprints (kept as truncated hashes). | C |

### FR-12 Settings

| ID | Req | Pri |
|---|---|---|
| FR-12.1 | Dry run: full preflight and command generation, zero mutations. | M |
| FR-12.2 | Backup retention count (0–10; 0 disables backup with a red warning). | S |
| FR-12.3 | Installer attribution package (default `com.android.vending`, free text, validated). | S |
| FR-12.4 | Clear `oat/` after swap (default on). | S |
| FR-12.5 | Split filtering mode: smart / ABI-only / all. | S |
| FR-12.6 | Keep screen on during a job; vibration on finish. | C |
| FR-12.7 | Theme: system / dark / light; dynamic color on API 31+. | C |

### FR-13 Safety and recovery UX

| ID | Req | Pri |
|---|---|---|
| FR-13.1 | First launch shows a one-time disclosure: this is a root tool, it modifies installed apps, a mistake can require a ROM reflash. Persisted acknowledgement. | M |
| FR-13.2 | Every destructive action is described in plain language before it runs. | M |
| FR-13.3 | Emergency "Restore last backup" reachable from Home in ≤ 2 taps. | M |
| FR-13.4 | If the device reboots mid-job, on next launch detect the incomplete job marker and offer rollback/recovery. | S |

---

## 5. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Cold start to interactive Home | ≤ 1.2 s on a mid-range 2020 device |
| NFR-2 | Swap step wall time (excluding install) | ≤ 3 s for a 150 MB APK |
| NFR-3 | Peak extra disk usage | ≤ bundle + patch + 200 MB |
| NFR-4 | APK size | ≤ 6 MB |
| NFR-5 | Network | No `INTERNET` permission; zero telemetry |
| NFR-6 | Crash-free sessions | ≥ 99.5 % of jobs produce a typed result, never a crash |
| NFR-7 | Compatibility | API 26–36, arm64-v8a primary, armeabi-v7a best-effort |
| NFR-8 | Root managers | Magisk ≥ 24, KernelSU, APatch; `su` fallback |
| NFR-9 | Accessibility | TalkBack labels on all controls, ≥ 48 dp targets, contrast ≥ 4.5:1 |
| NFR-10 | Localization | All strings in `strings.xml`; EN shipped, HI/ID/ES/PT-ready |
| NFR-11 | Battery | No wakelocks outside an active job; no background polling |
| NFR-12 | Privacy | No file contents leave the device; logs stay in app-private storage |

---

## 6. Key use cases

### UC-1 Happy path (priority)

1. User opens Veristaller; root chip shows "Magisk • granted".
2. Taps **New install** → picks `notespro-4.2.1.apks`; app shows format `bundletool`, 7 splits, 148 MB.
3. Picks `notespro-patched.apk`; app shows `com.notespro.app` 4.2.1 (4210), cert `a91f…` — differs from installed.
4. Review screen: 11 checks pass, 2 warnings (cert differs; 1 split not matching device density will be skipped). User acknowledges.
5. Start → progress: Staging → Installing bundle (pm session, 7/7 written) → Resolving path (`/data/app/~~kQ==/com.notespro.app-Xy==`) → Backing up (sha256 verified) → Swapping → Fixing permissions → Verifying.
6. Result: green, "Running patched base.apk", path, `system:system 0644 u:object_r:apk_data_file:s0`, backup size. Buttons: Open app, Restore, Export log.

### UC-2 Install fails

`pm install-commit` returns `INSTALL_FAILED_INVALID_APK: … signatures do not match previously installed version`. App maps to `E_INSTALL_SIGNATURE`, leaves the device untouched (no swap attempted), offers "uninstall existing app (loses data)" as an explicit, separately confirmed action, or "use matching bundle".

### UC-3 Swap fails at verity

`rm base.apk` succeeds but `mv` fails `EACCES`. App restores the backup, re-labels, verifies, and reports `E_VERITY_LOCKED` with guidance: disable APK verity for the app or use the framework-level signature bypass path instead.

### UC-4 Restore

User opens History → last job → **Restore backup**. App force-stops, copies back, fixups, clears `oat/`, verifies, reports "Restored 4.2.1 (4210) stock base.apk".

### UC-5 Dry run

Settings → Dry run on. Same flow, but the Progress screen shows generated commands with `[DRY RUN]` badges, and Result says "Nothing was modified".

---

## 7. Out of scope (v1)

- Editing/signing APKs, dex patching, resource injection.
- Installing Xposed/LSPosed modules or Magisk modules.
- Multi-user / work-profile targeting (single user 0 only).
- OBB/data bundle handling for `.xapk` (detected and reported, not installed).
- Automatic download of bundles.
- Cloud sync, accounts, telemetry.

## 8. Acceptance criteria (v1 release)

| ID | Criterion |
|---|---|
| AC-1 | On API 29, 31, 33, 35 and 36, a 3-split sample app is installed and its base APK swapped, and the app launches without crash. |
| AC-2 | Owner, group, mode and SELinux context of the final `base.apk` exactly match a stock install on the same device. |
| AC-3 | Injecting a failure at each step (staging, install, backup, swap, fixup, verify) leaves the device in a launchable state — either original or patched. |
| AC-4 | The app runs with the root prompt denied, showing a clear explanation and no crash. |
| AC-5 | History restore returns the app to the original APK with a verified hash match. |
| AC-6 | `aapt dump permissions` of Veristaller shows no `INTERNET` permission. |
| AC-7 | All privileged commands appear verbatim in the job log. |
| AC-8 | Dry run performs no writes: verified by `find /data/app -newer <marker>` and unchanged `sha256` of the target. |

## 9. Open questions

| ID | Question | Owner | Default if unanswered |
|---|---|---|---|
| Q-1 | Minimum supported Android: 8 or 10? | Product | 8 (API 26) |
| Q-2 | Should re-signed split APKs also be swappable (patch all splits)? | Product | No, v1 swaps base only |
| Q-3 | Ship an "uninstall target app" escape hatch for signature conflicts? | Product | Yes, behind 2 confirmations |
| Q-4 | Locale set for first release besides EN? | Product | EN only |
| Q-5 | Branding: launcher name "Veristaller" vs "VeriStaller"? | Design | "Veristaller" everywhere |
