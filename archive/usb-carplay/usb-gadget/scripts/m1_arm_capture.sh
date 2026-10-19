#!/system/bin/sh
# One-shot: ARM, capture state, DISARM, force restore USB.
# Run detached (setsid) over wireless — USB adb dies during takeover.
OUT=/data/adb/aadusb/arm_result.txt
exec >"$OUT" 2>&1
set -x
date
nc1() {
  (printf "%s\n" "$1"; sleep 0.2) | toybox nc 127.0.0.1 48350 &
  NP=$!
  sleep 0.6
  kill $NP 2>/dev/null
  wait $NP 2>/dev/null
}
killall -9 aad-usb 2>/dev/null
sleep 1
for n in usbmux aadptp aadptp2 aadeth; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
printf '\n' > /config/usb_gadget/aadusb/UDC 2>/dev/null
BIN=/data/adb/aadusb/aad-usb
[ -x "$BIN" ] || BIN=/data/local/tmp/aad-usb
: > /data/adb/aadusb/daemon.log
"$BIN" --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
sleep 1
echo pid=$(pidof aad-usb)
echo udc_state_before=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)
nc1 ARM
i=0
while [ $i -lt 20 ]; do
  sleep 0.5
  AAD=$(cat /config/usb_gadget/aadusb/UDC 2>/dev/null)
  ST=$(nc1 STATE)
  echo "t=$i aad='$AAD' udc=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null) $ST"
  echo "$ST" | grep -q present && break
  echo "$ST" | grep -q error && break
  echo "$AAD" | grep -q dwc3 && break
  i=$((i+1))
done
echo '===FINAL==='
echo cfg=$(getprop sys.usb.config)
echo g1=$(cat /config/usb_gadget/g1/UDC 2>/dev/null)
echo aad=$(cat /config/usb_gadget/aadusb/UDC 2>/dev/null)
echo udc_state=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)
ls /config/usb_gadget/aadusb/configs 2>/dev/null
for c in /config/usb_gadget/aadusb/configs/b.*; do
  echo "-- $c"
  ls -l "$c" 2>/dev/null
done
echo '===daemon.log==='
cat /data/adb/aadusb/daemon.log
echo '===logcat==='
logcat -d | grep AAD_usb | tail -80
echo '===DISARM+RESTORE==='
nc1 DISARM
sleep 1
killall -9 aad-usb 2>/dev/null
printf '\n' > /config/usb_gadget/aadusb/UDC 2>/dev/null
sleep 0.5
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.5
setprop sys.usb.config mtp,adb
sleep 2
echo after_restore cfg=$(getprop sys.usb.config) g1=$(cat /config/usb_gadget/g1/UDC 2>/dev/null)
echo DONE
