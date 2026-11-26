#!/system/bin/sh
# Veristaller — M0 read-only probe
#
# Collects every fact the app needs to resolve and swap an APK, without
# changing anything. Run from a root shell:
#
#   su -c "sh /sdcard/workspace/Veristaller/scripts/m0-probe.sh com.example.app"
#
# /sdcard is mounted noexec, so invoke it through `sh` explicitly.

PKG="$1"

if [ -z "$PKG" ]; then
  echo "usage: m0-probe.sh <package.name>" >&2
  exit 2
fi

echo "=============================================================="
echo " Veristaller M0 probe"
echo " package : $PKG"
echo " date    : $(date 2>/dev/null)"
echo "=============================================================="

echo
echo "--- identity -------------------------------------------------"
echo "uid      : $(id -u 2>/dev/null)"
echo "selinux  : $(getenforce 2>/dev/null || echo 'unknown')"
echo "sdk      : $(getprop ro.build.version.sdk)"
echo "release  : $(getprop ro.build.version.release)"
echo "device   : $(getprop ro.product.manufacturer) $(getprop ro.product.model)"
echo "abi      : $(getprop ro.product.cpu.abilist)"
echo "build    : $(getprop ro.build.fingerprint)"
echo "boot     : veritymode=$(getprop ro.boot.veritymode) vbmeta=$(getprop ro.boot.vbmeta.device_state)"

echo
echo "--- tools ----------------------------------------------------"
for t in pm cmd stat find restorecon chcon getenforce sha256sum lsattr getfattr df; do
  p=$(command -v "$t" 2>/dev/null)
  if [ -n "$p" ]; then echo "ok      $t -> $p"; else echo "MISSING $t"; fi
done

echo
echo "--- free space -----------------------------------------------"
df -k /data 2>/dev/null | tail -2

echo
echo "--- package --------------------------------------------------"
if ! pm path "$PKG" 2>/dev/null | grep -q .; then
  echo "NOT INSTALLED (pm path returned nothing)"
  exit 1
fi
echo "pm path:"
pm path "$PKG" | sed 's/^/  /'

CODE_PATH=$(cmd package dump "$PKG" 2>/dev/null | grep -m1 -o 'codePath=[^ ]*' | cut -d= -f2)
echo "codePath : ${CODE_PATH:-<not parsed>}"

BASE=$(pm path "$PKG" | sed -n 's/^package://p' | grep '/base\.apk$' | head -1)
if [ -z "$BASE" ]; then
  echo "base.apk not in pm path output — searching"
  BASE=$(find "${CODE_PATH:-/data/app}" -maxdepth 4 -name base.apk 2>/dev/null | head -1)
fi

if [ -z "$BASE" ]; then
  echo "RESULT: base.apk NOT FOUND (path layout unknown — capture dumpsys below)"
  exit 1
fi
DIR=$(dirname "$BASE")

echo
echo "--- resolved -------------------------------------------------"
echo "dirname  : $DIR"
echo "base     : $BASE"
echo "depth    : $(echo "$BASE" | awk -F/ '{print NF-1}') components under root"

echo
echo "--- attributes (this is the assertion baseline) --------------"
stat -c 'owner=%u:%g mode=%a size=%s' "$BASE" 2>/dev/null \
  || ls -ln "$BASE"
echo "selinux  : $(stat -c '%C' "$BASE" 2>/dev/null || ls -Z "$BASE" 2>/dev/null | awk '{print $1}')"
echo "readlink : $(readlink "$BASE" 2>/dev/null || echo 'not a symlink')"
echo "parent   :"
ls -ldZ "$DIR" 2>/dev/null

echo
echo "--- mount ----------------------------------------------------"
mount 2>/dev/null | grep -E ' /data | /data/app ' || echo "(no explicit /data mount line)"

echo
echo "--- verity hints ---------------------------------------------"
echo "lsattr base.apk:"
lsattr "$BASE" 2>/dev/null || echo "  (lsattr unavailable — expect on many toybox builds)"
echo "xattrs:"
getfattr -d -m - "$BASE" 2>/dev/null || echo "  (getfattr unavailable)"

echo
echo "--- splits ---------------------------------------------------"
pm path "$PKG" | sed -n 's/^package://p' | nl -ba | sed 's/^/  /'

echo
echo "--- package flags --------------------------------------------"
cmd package dump "$PKG" 2>/dev/null \
  | grep -E 'versionCode=|versionName=|codePath=|signatures|installerPackageName=|flags=|pkgFlags=' \
  | head -20 | sed 's/^/  /'

echo
echo "--- siblings in code path ------------------------------------"
ls -laZ "$DIR" 2>/dev/null | sed 's/^/  /'

echo
echo "=============================================================="
echo " Record the output in docs/SPIKE.md, then run the swap test."
echo "=============================================================="
