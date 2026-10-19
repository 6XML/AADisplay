#!/system/bin/sh
# Run on phone as root. Arms aad-usb for ~20s then DISARMs.
set +x
killall -9 aad-usb 2>/dev/null
sleep 1
# Clear stale ffs mountpoints from prior failed runs
for n in usbmux aadptp aadptp2 aadeth probe; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n/ep0 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
BIN=/data/adb/aadusb/aad-usb
[ -x "$BIN" ] || BIN=/data/local/tmp/aad-usb
mkdir -p /data/adb/aadusb/pair
"$BIN" --port=48350 >> /data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
sleep 1
nc1() { (printf "$1\n"; sleep 0.2) | toybox nc 127.0.0.1 48350 &
  NP=$!; sleep 0.5; kill $NP 2>/dev/null; wait $NP 2>/dev/null; }
(
  sleep 22
  nc1 DISARM
  sleep 1
  setprop sys.usb.config mtp,adb
) &
nc1 ARM
sleep 2
nc1 STATE
sleep 18
nc1 STATE
logcat -d -s AAD_usb:V | tail -80 > /data/adb/aadusb/arm_test.log
echo DONE
