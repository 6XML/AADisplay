#!/bin/bash
# Run in Terminal while CarPlay Simulator is open (Dock icon).
# Issues REENUM (1.5s USB detach) so Mac usbmuxd / Simulator see a fresh plug.
set -uo pipefail

pick_adb() {
  if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
  if adb connect 192.168.123.247:5555 >/dev/null 2>&1; then
    if adb -s 192.168.123.247:5555 shell true >/dev/null 2>&1; then
      echo "adb -s 192.168.123.247:5555"; return
    fi
  fi
  local serial
  serial="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
  [[ -n "$serial" ]] && echo "adb -s $serial"
}

ADB_CMD="$(pick_adb)"
if [[ -z "$ADB_CMD" ]]; then
  echo "ERROR: no adb device. adb connect 192.168.123.247:5555"
  exit 1
fi
echo "Using: $ADB_CMD"

echo "1) CarPlay Simulator open (Dock OK)"
echo "2) Real iPhone unplugged / Wi‑Fi sync off"
echo "3) Ensure usbmux-only gadget + REENUM…"

$ADB_CMD shell "su -c 'logcat -c'" || { echo "ERROR: adb/su failed"; exit 1; }

# Mac Sequoia: usbmux+NCM → CDC claims device, idevice_id stays empty.
# Restart daemon without AAD_CARPLAY_FIRST so ARM uses usbmux-only.
sig="$($ADB_CMD shell "su -c 'cat /sys/devices/virtual/android_usb/android0/iSerial 2>/dev/null; ls /config/usb_gadget/g1/configs/b.1/ 2>/dev/null'" 2>/dev/null || true)"
if echo "$sig" | grep -q 'f2'; then
  echo "composite (f2/NCM) detected — restarting aad-usb usbmux-only…"
  $ADB_CMD shell "su -c '
    (echo DISARM; sleep 0.2)|timeout 1 toybox nc 127.0.0.1 48350 >/dev/null 2>&1 || true
    killall -9 aad-usb 2>/dev/null || true
    sleep 0.5
    /data/adb/aadusb/aad-usb --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
    sleep 1
    (echo ARM; sleep 0.3)|timeout 2 toybox nc 127.0.0.1 48350
  '" || true
  sleep 4
fi

# Ensure armed
st="$($ADB_CMD shell "su -c '(echo STATE; sleep 0.2)|timeout 1 toybox nc 127.0.0.1 48350'" 2>/dev/null || true)"
echo "pre: $st"
if ! echo "$st" | grep -qiE 'present|usbmux|lockdown|carkit'; then
  echo "ARM…"
  $ADB_CMD shell "su -c '(echo ARM; sleep 0.2)|timeout 2 toybox nc 127.0.0.1 48350'" || true
  sleep 4
fi

# nc/timeout often exits 124 while the control socket stays open — that is OK
# if we already got ACK REENUM. Daemon needs ≥1.5s unbound after ACK.
reenum_out="$($ADB_CMD shell "su -c '(echo REENUM; sleep 0.3)|timeout 1 toybox nc 127.0.0.1 48350'" 2>&1 || true)"
echo "$reenum_out"
if ! echo "$reenum_out" | grep -q 'ACK REENUM'; then
  echo "ERROR: no ACK REENUM — is aad-usb new enough? (need REENUM cmd)"
  exit 1
fi
echo "(waiting ~2s detach + 6s Mac re-attach…)"
sleep 8

echo "==== phone ===="
$ADB_CMD shell "su -c 'echo state=\$(cat /sys/class/udc/a600000.dwc3/state); echo VID=\$(cat /config/usb_gadget/g1/idVendor); (echo STATE; sleep 0.2)|timeout 1 toybox nc 127.0.0.1 48350'" || true
echo "==== AAD_usb ===="
$ADB_CMD shell "su -c 'logcat -d | grep AAD_usb | grep -iE \"REENUM|reenum|GetValue|Pair|TLS|StartService|Request=|ssl|Error|Missing|ffs \" | tail -100'" || true
echo "==== Mac usbmux ===="
# After REENUM, Apple usbmuxd often keeps a dead exclusive claim on FF/FE/02
# until restarted — ListDevices stays empty while ioreg still shows iPhone.
if ! idevice_id -l 2>/dev/null | grep -q .; then
  echo "idevice_id empty — restarting usbmuxd (needs sudo once)…"
  sudo killall usbmuxd 2>/dev/null || true
  sleep 2
fi
idevice_id -l 2>&1 || true
ideviceinfo -k ActivationState 2>&1 || true
ideviceinfo -k ProductName 2>&1 || true
# TLS UNKNOWN_CA from CarPlay Simulator → clear Mac pair store once:
#   ./scripts/mac_sim_recover.sh
echo "Done. If Dock-only: ./scripts/mac_sim_recover.sh then reopen CarPlay Simulator."
