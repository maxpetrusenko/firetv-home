#!/usr/bin/env bash
# Compile reminders-helper with an embedded Info.plist (needed for the Reminders permission prompt).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/reminders-helper}
swiftc -O "$here/reminders-helper.swift" -o "$out" \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$here/Info.plist"
echo "built $out"
