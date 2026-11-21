#!/usr/bin/env bash
# Usage: check-no-switchhome.sh <apk> [absent|present]   (default: absent)
# absent: fail if any switch-home component is in the APK manifest.
# present: fail unless all of them are.
set -euo pipefail
apk=${1:?apk path}; mode=${2:-absent}
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
aapt=$(ls -d "$sdk"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)
[ -x "$aapt" ] || { echo "aapt not found under $sdk/build-tools" >&2; exit 2; }
tree=$("$aapt" dump xmltree "$apk" AndroidManifest.xml)
bad=0
vn=$("$(dirname "$aapt")/aapt" dump badging "$apk" | grep -o "versionName='[^']*'" || true)
if [ "$mode" = absent ] && grep -qi switchhome <<<"$vn"; then echo "FAIL: $vn"; bad=1; fi
if [ "$mode" = present ] && ! grep -qi switchhome <<<"$vn"; then echo "FAIL: versionName lacks switchhome ($vn)"; bad=1; fi
for n in app.firetvhome.Keeper app.firetvhome.BootReceiver app.firetvhome.TakeHomeActivity \
         app.firetvhome.ReleaseHomeActivity android.permission.PACKAGE_USAGE_STATS \
         android.permission.FOREGROUND_SERVICE_SPECIAL_USE; do
  if grep -q "$n" <<<"$tree"; then found=1; else found=0; fi
  if [ "$mode" = absent ] && [ $found = 1 ]; then echo "FAIL: $n present"; bad=1; fi
  if [ "$mode" = present ] && [ $found = 0 ]; then echo "FAIL: $n missing"; bad=1; fi
done
[ $bad = 0 ] && echo "OK ($mode): $apk"
exit $bad
