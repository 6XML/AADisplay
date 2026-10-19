#!/system/bin/sh
# Detached hold: ARM ~25s, log phone state, DISARM. Safe over wifi adb.
# Flip4 path hijacks stock g1 (not a private aadusb gadget).
OUT=/data/adb/aadusb/hold_result.txt
exec >"$OUT" 2>&1
set -x
date
nc1() {
  (printf "%s\n" "$1"; sleep 0.2) | toybox nc 127.0.0.1 48350 &
  NP=$!
  sleep 0.7
  kill $NP 2>/dev/null
  wait $NP 2>/dev/null
}
GADGET=
if [ -d /config/usb_gadget/g1 ]; then
  GADGET=/config/usb_gadget/g1
else
  GADGET=/config/usb_gadget/aadusb
fi
echo "watch_gadget=$GADGET"
setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null
sleep 0.5
for n in usbmux aadptp aadptp2 aadeth; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
[ -e /config/usb_gadget/aadusb/UDC ] && printf '\n' > /config/usb_gadget/aadusb/UDC
rm -rf /config/usb_gadget/aadusb 2>/dev/null
# Free UDC without relying on HAL
setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.3
[ -e /config/usb_gadget/g1/UDC ] && printf '\n' > /config/usb_gadget/g1/UDC
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
sleep 1
BIN=/data/adb/aadusb/aad-usb
: > /data/adb/aadusb/daemon.log
# Optional: TEST_VID=0x18d1 TEST_PID=0x4ee1 for non-Apple enum probe
EXTRA=
[ -n "$TEST_VID" ] && EXTRA="$EXTRA --vid=$TEST_VID"
[ -n "$TEST_PID" ] && EXTRA="$EXTRA --pid=$TEST_PID"
echo "bin=$BIN extra=$EXTRA"
"$BIN" --port=48350 $EXTRA >>/data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
sleep 1
echo pid=$(pidof aad-usb)
nc1 ARM
i=0
while [ $i -lt 40 ]; do
  sleep 1
  echo "t=$i g=$(cat $GADGET/UDC 2>/dev/null) vid=$(cat $GADGET/idVendor 2>/dev/null) pid=$(cat $GADGET/idProduct 2>/dev/null) manuf=$(cat $GADGET/strings/0x409/manufacturer 2>/dev/null) prod=$(cat $GADGET/strings/0x409/product 2>/dev/null) udc=$(cat /sys/class/udc/a600000.dwc3/state) speed=$(cat /sys/class/udc/a600000.dwc3/current_speed) hal=$(getprop init.svc.vendor.usb-hal-1-3) adbd=$(getprop init.svc.adbd)"
  i=$((i + 1))
done
echo '===FINAL==='
ls "$GADGET/configs" 2>/dev/null
for c in "$GADGET"/configs/b.*; do echo -- $c; ls -la $c 2>/dev/null; done
for f in idVendor idProduct bcdUSB bcdDevice bMaxPacketSize0 bDeviceClass; do echo $f=$(cat $GADGET/$f 2>/dev/null); done
echo serial=$(cat $GADGET/strings/0x409/serialnumber 2>/dev/null)
echo '===ffs==='
ls -la /dev/usb-ffs/usbmux/ 2>/dev/null
echo ready=$(cat $GADGET/functions/ffs.usbmux/ready 2>/dev/null)
echo '===daemon==='
cat /data/adb/aadusb/daemon.log
echo '===logcat==='
logcat -d -s AAD_usb:V | tail -80
echo '===DISARM==='
nc1 DISARM
sleep 1
killall -9 aad-usb 2>/dev/null
sh /data/local/tmp/recover_usb.sh
echo DONE
