#!/bin/bash
# Mac-side recovery for fake-iPhone CarPlay Simulator bring-up.
# Run in Terminal.app (needs sudo for /var/db/lockdown + usbmuxd).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB_SERIAL="${ADB_SERIAL:-192.168.123.247:5555}"

wait_device() {
  local i
  for i in $(seq 1 45); do
    UDID="$(idevice_id -l 2>/dev/null | head -1 || true)"
    if [[ -n "${UDID}" ]]; then
      echo "UDID=${UDID}"
      return 0
    fi
    # After usbmuxd restart, force a host-visible reattach once.
    if [[ "$i" -eq 5 ]]; then
      echo "still empty — phone REENUM…"
      adb -s "${ADB_SERIAL}" shell "su -c '(echo REENUM; sleep 0.3)|timeout 1 toybox nc 127.0.0.1 48350'" 2>/dev/null || true
    fi
    if [[ "$i" -eq 15 ]]; then
      echo "still empty — restart aad-usb + ARM…"
      adb -s "${ADB_SERIAL}" shell "su -c '
        killall -9 aad-usb 2>/dev/null || true
        sleep 0.5
        /data/adb/aadusb/aad-usb --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
        sleep 1
        (echo ARM; sleep 0.4)|timeout 2 toybox nc 127.0.0.1 48350
      '" 2>/dev/null || true
    fi
    if [[ "$i" -eq 25 ]]; then
      echo "still empty — sudo killall usbmuxd again…"
      sudo killall usbmuxd 2>/dev/null || true
    fi
    sleep 1
  done
  echo "UDID=<none after 45s>"
  return 1
}

killall "CarPlay Simulator" 2>/dev/null || true

echo "== ensure phone aad-usb armed (usbmux-only) =="
adb connect "${ADB_SERIAL}" >/dev/null 2>&1 || true
adb -s "${ADB_SERIAL}" shell "su -c '
  if ! pidof aad-usb >/dev/null; then
    /data/adb/aadusb/aad-usb --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
    sleep 1
  fi
  st=\$((echo STATE; sleep 0.2)|timeout 1 toybox nc 127.0.0.1 48350 || true)
  echo pre:\$st
  if ! echo \"\$st\" | grep -qiE \"present|usbmux|lockdown|carkit\"; then
    (echo ARM; sleep 0.4)|timeout 2 toybox nc 127.0.0.1 48350 || true
    sleep 5
  fi
  if ls /config/usb_gadget/g1/configs/b.1/f2 >/dev/null 2>&1; then
    echo \"NCM composite — restarting usbmux-only\"
    killall -9 aad-usb 2>/dev/null || true
    sleep 0.5
    /data/adb/aadusb/aad-usb --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
    sleep 1
    (echo ARM; sleep 0.4)|timeout 2 toybox nc 127.0.0.1 48350 || true
    sleep 5
  fi
  ls /config/usb_gadget/g1/configs/b.1/
'" || echo "WARN: adb/su failed — continue if device already armed"

echo "== clear stale Mac pair records (needs sudo) =="
sudo rm -f /var/db/lockdown/*.plist 2>/dev/null || true
sudo rm -rf /var/db/lockdown/Remote 2>/dev/null || true
rm -rf "${HOME}/Library/Lockdown" 2>/dev/null || true

echo "== refresh usbmuxd after lockdown wipe =="
sudo killall usbmuxd 2>/dev/null || true
sleep 3

echo "== wait for usbmux device =="
if ! wait_device; then
  echo "FAIL: no usbmux device. Try manually:"
  echo "  sudo killall usbmuxd"
  echo "  adb -s ${ADB_SERIAL} shell \"su -c 'killall -9 aad-usb; sleep 1; /data/adb/aadusb/aad-usb --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 & sleep 1; (echo ARM; sleep 0.4)|timeout 2 toybox nc 127.0.0.1 48350'\""
  echo "  sleep 8; idevice_id -l"
  exit 1
fi

echo "== pair (retry) =="
ok=0
for i in 1 2 3 4 5; do
  idevicepair unpair >/dev/null 2>&1 || true
  if idevicepair pair 2>&1 && idevicepair validate 2>&1; then
    ok=1
    break
  fi
  echo "pair attempt $i failed — retry in 2s"
  sleep 2
  wait_device || true
done
if [[ "$ok" != 1 ]]; then
  echo "FAIL: pair after retries"
  exit 1
fi

ideviceinfo -k ProductName 2>&1 || true
ideviceinfo -k ActivationState 2>&1 || true

echo "== carkit smoke =="
python3 "${ROOT}/scripts/host_carkit_smoke.py"

echo "OK — reopen CarPlay Simulator:"
echo "  open -a \"CarPlay Simulator\""
