#!/bin/bash
# Open CarPlay Simulator first, run this, THEN plug the iPhone.
set -euo pipefail
UDID="${1:-00008101-0019686026BA001E}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${DIR}/pair-ios.txt"
echo "Waiting for device $UDID … (plug iPhone now)"
until frida-ps -D "$UDID" >/dev/null 2>&1; do
  sleep 0.5
done
echo "Device up — attaching lockdownd"
exec frida -D "$UDID" -n lockdownd -l "$DIR/lockdown_pair_dump.js" -o "$OUT"
