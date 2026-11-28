# Veristaller — UI Specification

Interactive prototype: `design/prototype.html` (open in the phone browser).
Design board: same file, toggle **Board** in the top bar to see every screen at once.

> **Superseded (v0.2 flow):** S2 (Bundle), S3 (Patch) and S4 (Review) are now one
> `InstallScreen` — two picker tiles, one Install button, preflight checks behind a
> collapsed "Details" row. S8 (first-run disclosure) and every acknowledgement step
> are gone; only hard preflight FAILs disable Install. The sections below are kept as
> the original design record.
>
> **Implementation note:** every screen below exists as a Compose screen under
> `app/src/main/java/dev/veristaller/ui/screens/`. The type scale in §2 is applied
> in `ui/theme/Type.kt`, the palette in `ui/theme/Color.kt`, and static a11y work
> (labels, `onClickLabel`, ≥48 dp targets, horizontal scroll for long command
> blocks) is in place. The physical TalkBack/contrast pass remains in M5; see
> `docs/COVERAGE.md`.

---

## 1. Design principles

1. **Show the machine.** This is a tool that writes to `/data/app`. The user should
   always be able to see the path, the exact command, and the result — not a spinner
   and a green tick with nothing behind it.
2. **Failure is explicit, not hidden.** No backup or undo exists; the result and Home
   say that recovery means reinstalling the target app.
3. **Two files, one decision.** The flow is: pick A → pick B → review → run. No
   wizards, no optional fields before the first run.
4. **Warnings are sentences.** Never "Warning: 0x7F". Say what will happen and what
   the user can do.
5. **Dark by default, terminal-adjacent.** Monospace only for paths, commands, logs.
   Everything else is calm Material 3.

---

## 2. Design tokens

### Colour (dark, default)

| Token | Value | Use |
|---|---|---|
| `background` | `#0E1116` | App background |
| `surface` | `#14181F` | Cards |
| `surfaceHigh` | `#1C222B` | Raised cards, sheets, log panel |
| `outline` | `#2C3542` | Hairlines, card borders |
| `onSurface` | `#E6EAF2` | Primary text |
| `onSurfaceVariant` | `#9AA6B8` | Secondary text, labels |
| `primary` | `#7CC4FF` | Primary actions, active step |
| `onPrimary` | `#00243D` | Text on primary |
| `success` | `#5BE49B` | Verified steps, PASS |
| `warning` | `#F5C451` | WARN, acknowledgement needed |
| `danger` | `#FF7A7A` | FAIL, destructive confirm |
| `info` | `#B69CFF` | Format badges, dry-run |
| `terminal` | `#0A0D12` bg / `#A8FFC8` text | Log panel |

Light theme is the Material 3 tonal inverse; dynamic colour is opt-in on API 31+.

### Type

| Role | Size / line | Weight | Family |
|---|---|---|---|
| Display (result headline) | 28 / 34 | 600 | System sans |
| Title | 20 / 26 | 600 | System sans |
| Section label | 12 / 16, +0.08 em, uppercase | 600 | System sans |
| Body | 15 / 22 | 400 | System sans |
| Caption | 13 / 18 | 400 | System sans |
| Mono (paths, commands, log) | 12.5 / 19 | 400 | `ui-monospace, Roboto Mono` |

### Spacing / shape

- 4 pt grid; screen padding 20 dp; card padding 16 dp; gaps 12 dp.
- Cards: 18 dp radius, 1 dp `outline` border, no shadow (dark UI).
- Buttons: 52 dp height, 16 dp radius. Primary filled, secondary tonal.
- Touch targets ≥ 48 dp. Bottom bar 64 dp + safe area.

---

## 3. Screens

### S1 — Home

```
┌────────────────────────────────────────┐
│  Veristaller              [Root ● ]    │  ← chip: granted(green)/denied(red)
│  Root app installer                    │
│                                        │
│  ┌──────────────────────────────────┐  │
│  │  ⬆  New install                  │  │  ← primary card, 96 dp
│  │     Bundle + patched APK         │  │
│  └──────────────────────────────────┘  │
│                                        │
│  DEVICE                                │
│  Magisk 29.1 · Android 15 · aarch64    │
│  SELinux enforcing · 41.2 GB free      │
│                                        │
│  LAST RESULT                           │
│  ✓ NotesPro 4.2.1 · 2 h ago  [Details] │
│                                        │
│  ── SAFETY ──────────────────────────  │
│  Only /data/app is modified. No backup │
│  is taken; reinstall the app to        │
│  recover from a failed swap.           │
│                                        │
│  [ Home ]                [ Settings ]  │
└────────────────────────────────────────┘
```

- Root chip is live (green/red/amber "checking"). Tap → root details sheet with the
  probe output.
- "New install" disabled with an explanation when root is denied.
- "Details" opens the last result; there is no History screen and no restore action.

### S2 — Pick bundle (step 1 of 3)

- Header: `Step 1 of 3 · Bundle`.
- Drop/pick card: "Choose .apks, .apkm, .xapk".
- After selection: `FileCard` — name, size, format badge (`BUNDLETOOL`), package,
  version, split count, and a compact split list with qualifier chips
  (`master`, `arm64-v8a`, `xxhdpi`, `en`).
- Incompatible splits are shown dimmed with "skipped on this device".
- Inline error box for corrupt/unsupported files, with "Pick another".
- Bottom: `Continue` enabled only on a parsed, compatible bundle.

### S3 — Pick patch (step 2 of 3)

- Same pick card, `.apk` only.
- After selection: package, version, size, min/target SDK, ABI, cert fingerprint
  (truncated `a91f3c…`), and a live comparison strip against the bundle:
  `package ✓ same` / `version ✓ 4210 = 4210` / `signature ⚠ differs (expected)`.
- Bottom: `Review` → S4.

### S4 — Review / preflight

```
Step 3 of 3 · Review
Target   com.notespro.app 4.2.1
Path     /data/app/~~kQ==/com.notespro.app-Xy==
Action   replace base.apk · install 7 splits

CHECKS
✓ Root granted (Magisk)
✓ Tools available
✓ Package names match
✓ Version codes match
⚠ Signing key differs — expected for patched APKs
⚠ 1 split skipped (x86_64 not supported)
✓ 41.2 GB free (need 0.5 GB)
✓ App is stopped

▸ Show commands (6)

[ Back ]                    [ Start install ]
```

- FAIL rows are red and collapse the Start button into "Fix required".
- WARN rows come with a checkbox "I understand" that gates Start (per warning).
- Command preview is a mono block listing the exact scripts in order — this is the
  same text that lands in the log.
- Dry-run mode changes the CTA to "Dry run (no changes)" and adds an info banner.

### S5 — Progress

- Horizontal stepper: `Stage → Install → Locate → Swap → Fix → Verify`
  (6 dots, active pulsing, done = green check, failed = red x).
- Current stage headline + one-line explanation ("Writing 7 splits to a pm session…").
- Determinate bar during install-write (`3/7 splits`), indeterminate elsewhere.
- Live log panel (mono, 8 lines, auto-scroll, tap to expand full screen, pause on
  manual scroll).
- `Cancel` is only enabled between stages; mid-swap it becomes "Finishing safely…"
  and is disabled (cancelling a `mv` is worse than waiting 400 ms).
- Keep-screen-on while running.

### S6 — Result

Three variants, same skeleton:

| Variant | Headline | Accent | Actions |
|---|---|---|---|
| Success | "Patched APK is live" | success | Open app · Export log |
| Failed | "Swap failed" | danger | View log · Copy diagnostics · Try again |

Success detail block (mono, copyable):

```
path       /data/app/~~kQ==/com.notespro.app-Xy==/base.apk
owner      1000:1000 (system)
mode       0644
selinux    u:object_r:apk_data_file:s0
size       92.4 MB  (sha256 3f9c…)
recovery   reinstall app — no backup was taken
```

- "Smoke test" row appears if the launch check ran: "Launched, no crash in 5 s".
- Failure copy states that no backup was taken and that the target app must be
  reinstalled if it no longer launches.

### S7 — Settings

- **Execution**: Dry run (switch), split filtering (segmented: Smart / ABI only / All),
  clear `oat` after swap (switch), installer package (text, default
  `com.android.vending`).
- **Safety**: confirm-destructive (switch, forced on); a note states that no backup
  is taken and recovery means reinstalling the app.
- **Diagnostics**: root probe (re-run, shows table), export diagnostics, log verbosity.
- **Appearance**: theme, dynamic colour.
- **About**: version, no-network statement, licences, "this tool modifies installed
  apps" disclosure link.

### S8 — First run disclosure (modal, once)

Title "Before you start". Three bullets (root required, only `/data/app` is touched,
no backup is taken — reinstall the app to recover) + a red line "A modified app may
stop working after an update. Reinstall it if it fails." Button "I understand"
(disabled for 2 s to force reading is optional; prefer not to).

---

## 4. States and copy rules

| Situation | Copy pattern |
|---|---|
| Blocking check | "{What} — {why}. {Fix}." e.g. "Bundle and patch are different apps — com.a vs com.b. Pick the matching APK." |
| Warning | "{Fact}. {Consequence}. {What to do if it fails}." |
| Unknown | "Veristaller can't tell whether this worked. Open the app to check." |
| Destructive | Verb + object + consequence: "Replace base.apk? The app will be stopped. No backup is taken; reinstall to recover." |
| Error with raw output | Friendly sentence, then `▸ Technical details` disclosure. |

Never: "Error 0x…", "Something went wrong", "Oops".

---

## 5. Compose mapping

| Prototype element | Compose |
|---|---|
| Phone frame + scroll | `Scaffold` + `Column(Modifier.verticalScroll())` |
| Root chip | `AssistChip` + `Badge` |
| New install card | `ElevatedCard(onClick=)`, 96 dp |
| File card | `Card` + `Row` + `AssistChip` for qualifiers |
| Check row | `ListItem` with leading icon (`Check`/`Warning`/`Error`) and trailing severity |
| Command preview | `Card` + `Text(fontFamily = FontFamily.Monospace)` + `SelectionContainer` |
| Stepper | `Row` of dots + `LinearProgressIndicator` |
| Log tail | `LazyColumn` (8 visible rows) + `LaunchedEffect` autoscroll + `animateScrollToItem` |
| Result badge | `Surface(shape = CircleShape, color = …)` + icon |
| Detail block | `Text` mono + `LocalClipboardManager` copy button |
| Bottom nav | `NavigationBar` (Home / Settings) |
| Destructive confirm | `AlertDialog` with typed confirmation for system packages |

Component names used consistently across prototype and code:
`VeristallerScaffold`, `RootStatusChip`, `NewInstallCard`, `FileCard`,
`QualifierChip`, `CheckRow`, `CommandPreview`, `StageStepper`, `LogPanel`,
`ResultBadge`, `DetailBlock`, `WarningGate`.

---

## 6. Accessibility

- Every icon button has `contentDescription`; log panel is `LiveRegion.Polite`.
- Stepper announces "Step 3 of 6, Locate, in progress".
- Colour is never the only signal: check rows carry icon + text.
- Mono text respects `fontScale` (no fixed `sp` off-scale, no `maxLines` truncation
  for paths — use horizontal scroll instead).
- Focus order follows the visual order; the log is focusable and scrollable with
  TalkBack.
