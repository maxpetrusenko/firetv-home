#!/usr/bin/env bash
# Install an APK on a Fire TV / Android TV over adb, verify it, roll back to the previous
# APK if verification fails, and always bring the configured HOME package back to front.
#
# usage: tools/tv-deploy.sh <apk>
#
# Environment:
#   TV_ADDR          required. adb target, for example <tv-ip>:5555
#   HOME_PKG         required. Package to leave in front (your normal home app)
#   HOME_ACTIVITY    optional. Component to start for HOME_PKG, for example pkg/.MainActivity.
#                    Default: the package's launcher entry (via monkey).
#   APP_PKG          package being deployed. Default: app.firetvhome
#   APP_ACTIVITY     activity used to smoke-test APP_PKG. Default: $APP_PKG/.MainActivity
#   VERIFY_SECS      how long the new build must stay alive without a crash. Default: 20
#   ADB              adb binary. Default: adb
#   BACKUP_DIR       where the previous APK is kept. Default: a temporary directory
#
# Rollback needs the previous APK. If none was installed, a failed deploy uninstalls the new one.
set -u

apk=${1:-}
[ -n "$apk" ] && [ -f "$apk" ] || { echo "usage: $0 <apk>" >&2; exit 2; }
: "${TV_ADDR:?set TV_ADDR, for example <tv-ip>:5555}"
: "${HOME_PKG:?set HOME_PKG to the package that should be in front when this finishes}"
APP_PKG=${APP_PKG:-app.firetvhome}
APP_ACTIVITY=${APP_ACTIVITY:-$APP_PKG/.MainActivity}
VERIFY_SECS=${VERIFY_SECS:-20}
ADB=${ADB:-adb}
tmp=${BACKUP_DIR:-$(mktemp -d)}
backup=""

a() { "$ADB" -s "$TV_ADDR" "$@"; }
log() { echo "tv-deploy: $*"; }

# shellcheck disable=SC2329  # invoked from the EXIT trap
bring_home_front() {
  if [ -n "${HOME_ACTIVITY:-}" ]; then
    a shell am start -n "$HOME_ACTIVITY" >/dev/null 2>&1
  else
    a shell monkey -p "$HOME_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  fi
}

# Runs on every exit path, including Ctrl-C and errors.
# shellcheck disable=SC2329  # invoked from the EXIT trap
cleanup() {
  a get-state >/dev/null 2>&1 || "$ADB" connect "$TV_ADDR" >/dev/null 2>&1
  if bring_home_front; then log "$HOME_PKG started"; else log "could not start $HOME_PKG"; fi
}
trap cleanup EXIT
trap 'log interrupted; exit 130' INT TERM

"$ADB" connect "$TV_ADDR" >/dev/null 2>&1
[ "$(a get-state 2>/dev/null)" = device ] || { log "TV not reachable at $TV_ADDR"; exit 1; }

# Keep the currently installed APK so a failure can be undone.
prev=$(a shell pm path "$APP_PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://')
if [ -n "$prev" ]; then
  backup="$tmp/previous.apk"
  if a pull "$prev" "$backup" >/dev/null 2>&1; then log "backed up previous APK"; else backup=""; log "backup failed"; fi
fi

rollback() {
  log "rolling back"
  a shell am force-stop "$APP_PKG" >/dev/null 2>&1
  if [ -n "$backup" ] && [ -f "$backup" ]; then
    if a install -r -d "$backup" >/dev/null 2>&1; then log "previous APK restored"; else log "restore failed"; fi
  else
    a uninstall "$APP_PKG" >/dev/null 2>&1 && log "new APK uninstalled (no previous version)"
  fi
}

a logcat -c >/dev/null 2>&1
if ! a install -r -d "$apk" 2>&1 | tr -d '\r' | grep -q '^Success'; then
  log "install failed"
  rollback
  exit 1
fi
a shell pm path "$APP_PKG" >/dev/null 2>&1 || { log "package not present after install"; rollback; exit 1; }

# Smoke test: start it, let it run, then require a live process and no crash or ANR in the log.
a shell am start -n "$APP_ACTIVITY" >/dev/null 2>&1
sleep "$VERIFY_SECS"
crashed=$(a logcat -b crash -d 2>/dev/null | grep -c "$APP_PKG")
anr=$(a logcat -d -s ActivityManager:E 2>/dev/null | grep -c "ANR in $APP_PKG")
alive=$(a shell pidof "$APP_PKG" 2>/dev/null | tr -d '\r')
if [ "${crashed:-0}" -gt 0 ] || [ "${anr:-0}" -gt 0 ] || [ -z "$alive" ]; then
  log "verification failed (crash=$crashed anr=$anr alive=${alive:-no})"
  rollback
  exit 1
fi
log "verified $APP_PKG (pid $alive)"
exit 0
