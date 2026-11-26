# M0 — Feasibility Spike

> Status: **not run**. The M1–M4 code was written before this gate was closed,
> so the go/no-go decision below is still the release blocker, not a paperwork
> step. Everything below is the manual version of what `JobRunner` automates.

---

## 0. Prepare

```bash
# on a desktop
bundletool build-apks \
  --bundle=notespro.aab \
  --output=notespro-4.2.1.apks \
  --ks=debug.keystore --ks-key-alias=androiddebugkey \
  --ks-pass=pass:android

unzip -l notespro-4.2.1.apks | head -20   # confirm splits/ + toc.pb
```

Patched base APK: rebuild/re-sign the base with a **different** key so the signature
mismatch case is exercised, and keep `versionCode` identical.

Copy both files to `/sdcard/Download/` and run:

```bash
su -c "sh /sdcard/workspace/Veristaller/scripts/m0-probe.sh com.notespro.app"
```

Record the output in §4.

---

## 1. Baseline — stock install

Before swapping, capture the exact target state from a stock install:

```bash
P=com.notespro.app
BASE=$(pm path $P | sed -n 's/^package://p' | grep '/base.apk$')
DIR=$(dirname "$BASE")
stat -c 'owner=%u:%g mode=%a size=%s' "$BASE"
ls -Z "$BASE"
```

| Field | Value (fill in) |
|---|---|
| owner | `____:____` |
| mode | `______` |
| SELinux context | `______________________` |
| directory mode | `______` |
| `pm path` first line | `______________________` |

---

## 2. Install the bundle

```bash
cd /sdcard/Download
SIZE=$(stat -c %s notespro-4.2.1.apks)

# extract only splits
rm -rf /data/local/tmp/m0 && mkdir -p /data/local/tmp/m0
cd /data/local/tmp/m0
unzip -o /sdcard/Download/notespro-4.2.1.apks 'splits/*' >/dev/null
ls -l splits/

TOTAL=$(du -sk splits | cut -f1)
SESSION=$(pm install-create -r -i com.android.vending | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
echo "session=$SESSION"
for f in splits/*.apk; do
  pm install-write -S "$(stat -c %s "$f")" "$SESSION" "$(basename "$f")" "$f" || break
done
pm install-commit "$SESSION"
```

Result: `Success` / `Failure [INSTALL_FAILED_…]`
Notes: ______________________________________________

---

## 3. Swap — the actual experiment

```bash
P=com.notespro.app
BASE=$(pm path $P | sed -n 's/^package://p' | grep '/base.apk$')
DIR=$(dirname "$BASE")
PATCH=/sdcard/Download/notespro-patched.apk

am force-stop $P

# stage in the same directory (fresh inode, no verity)
cp "$PATCH" "$DIR/.base.apk.vst.tmp"
chown 1000:1000 "$DIR/.base.apk.vst.tmp"
chmod 0644 "$DIR/.base.apk.vst.tmp"
chcon u:object_r:apk_data_file:s0 "$DIR/.base.apk.vst.tmp"
ls -Z "$DIR/.base.apk.vst.tmp"

# replace  (watch the errno here — this is the whole spike)
rm -f "$BASE"
mv "$DIR/.base.apk.vst.tmp" "$BASE"
echo "mv exit=$?"

restorecon -F "$BASE" 2>/dev/null || chcon u:object_r:apk_data_file:s0 "$BASE"
rm -rf "$DIR/oat" "$DIR/oat_primary" "$DIR/oat_secondary"

stat -c 'owner=%u:%g mode=%a size=%s' "$BASE"
ls -Z "$BASE"
pm path $P
```

Then:

```bash
monkey -p $P -c android.intent.category.LAUNCHER 1
sleep 5
logcat -d -b crash | tail -40
logcat -d | grep -i "avc: denied" | tail -20
```

### Verdicts

| Question | Answer | Evidence |
|---|---|---|
| Does `cp` into the existing `base.apk` fail? | yes / no | |
| Does `rm` + `mv` succeed? | yes / no | |
| Exact errno on failure | | |
| Does the file carry `security.fsverity` after `mv`? | yes / no | |
| Does the app launch without crash? | yes / no | |
| Does split loading work with a differently-signed base? | yes / no | |
| Does `restorecon -F` exist? | yes / no | |
| Does `chcon` alone stick? | yes / no | |

---

## 4. Device results

| # | Device / ROM | Android | Root mgr | Kernel | Swap ok | Verity blocked | Boot ok | Split ok | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | | |
| 2 | | | | | | | | | |
| 3 | | | | | | | | | |

### Reboot test (R2)

```bash
reboot
# after boot:
pm path com.notespro.app
monkey -p com.notespro.app 1
logcat -d -b crash | tail
```

| Device | App present | Launches | PackageManager logged anything about signatures? |
|---|---|---|---|
| | | | |

### Failed-swap recovery (AC-3 / AC-5)

There is no backup, restore or rollback. If the experiment leaves `base.apk` missing
or the app unusable, reinstall the target app from its original bundle before
continuing.

| Device | Reinstall ok | App launches |
|---|---|---|
| | | |

---

## 5. Findings that change the architecture

Copy into `docs/ARCHITECTURE.md` and `docs/PLAN.md` when the spike closes.

- **Verity behaviour:**
- **Boot scan behaviour:**
- **Split signature behaviour:**
- **`chcon` vs `restorecon`:**
- **Path shapes observed:**
- **Recovery after a failed swap (reinstall only, no backup):**
- **Go / no-go for the file-swap approach:**
- **If no-go, chosen fallback:**
