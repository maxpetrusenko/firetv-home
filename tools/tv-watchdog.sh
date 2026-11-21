#!/usr/bin/env bash
# Periodically check that the TV is reachable and that a home app is in front, and relaunch it
# when the stock launcher (or nothing) is showing. Apps you started on purpose are left alone.
#
# usage: tools/tv-watchdog.sh [--once]
#
# Environment:
#   TV_ADDR          required. adb target, for example <tv-ip>:5555
#   HOME_PKG         required. Home package to relaunch
#   HOME_ACTIVITY    optional. Component to start, for example pkg/.MainActivity. Default: launcher entry
#   STOCK_PKGS       comma list of packages that count as "wrong home". Default: com.amazon.tv.launcher
#   INTERVAL         seconds between checks. Default: 180
#   MAX_UNKNOWN      relaunch after this many consecutive checks with no resumed app. Default: 3
#   ADB              adb binary. Default: adb
#   PAUSE_FILE       if this file exists, checks are skipped. Default: unset (no pause file)
#
# Run it from a machine that stays on, for example under launchd or cron with --once.
set -u

: "${TV_ADDR:?set TV_ADDR, for example <tv-ip>:5555}"
: "${HOME_PKG:?set HOME_PKG}"
STOCK_PKGS=${STOCK_PKGS:-com.amazon.tv.launcher}
INTERVAL=${INTERVAL:-180}
MAX_UNKNOWN=${MAX_UNKNOWN:-3}
ADB=${ADB:-adb}
once=0; [ "${1:-}" = "--once" ] && once=1
unknown=0

a() { "$ADB" -s "$TV_ADDR" "$@"; }
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') tv-watchdog: $*"; }

relaunch() {
  if [ -n "${HOME_ACTIVITY:-}" ]; then
    a shell am start -n "$HOME_ACTIVITY" >/dev/null 2>&1
  else
    a shell monkey -p "$HOME_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  fi
}

front_pkg() {
  a shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep -E 'mResumedActivity|topResumedActivity' | head -1 \
    | sed -n 's/.* \([A-Za-z0-9_.]*\)\/[^ ]*.*/\1/p'
}

check() {
  [ -n "${PAUSE_FILE:-}" ] && [ -e "$PAUSE_FILE" ] && return
  if [ "$(a get-state 2>/dev/null)" != device ]; then
    "$ADB" connect "$TV_ADDR" >/dev/null 2>&1
    if [ "$(a get-state 2>/dev/null)" != device ]; then log "TV unreachable at $TV_ADDR"; return; fi
  fi
  local front; front=$(front_pkg)
  if [ -z "$front" ]; then
    unknown=$((unknown + 1))
    log "no resumed app ($unknown/$MAX_UNKNOWN)"
    if [ "$unknown" -ge "$MAX_UNKNOWN" ]; then relaunch; unknown=0; log "relaunched $HOME_PKG"; fi
    return
  fi
  unknown=0
  case ",$STOCK_PKGS," in
    *",$front,"*) log "stock launcher ($front) in front, relaunching $HOME_PKG"; relaunch ;;
  esac
}

while :; do
  check
  [ "$once" = 1 ] && exit 0
  sleep "$INTERVAL"
done
