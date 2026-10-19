#!/system/bin/sh
# Detached hold for carkit smoke: ARM ~90s, leave daemon log, then DISARM.
# Run over wifi adb. Host runs scripts/host_carkit_smoke.py while armed.
OUT=/data/adb/aadusb/hold_carkit.txt
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
GADGET=/config/usb_gadget/g1
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
setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.3
[ -e /config/usb_gadget/g1/UDC ] && printf '\n' > /config/usb_gadget/g1/UDC
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
sleep 1
BIN=/data/adb/aadusb/aad-usb
: > /data/adb/aadusb/daemon.log
echo "bin=$BIN"
"$BIN" --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
sleep 1
echo pid=$(pidof aad-usb)
nc1 ARM
i=0
while [ $i -lt 90 ]; do
  sleep 1
  echo "t=$i g=$(cat $GADGET/UDC 2>/dev/null) vid=$(cat $GADGET/idVendor 2>/dev/null) pid=$(cat $GADGET/idProduct 2>/dev/null) udc=$(cat /sys/class/udc/a600000.dwc3/state) usb0=$(ip link show usb0 2>/dev/null | head -1)"
  i=$((i + 1))
done
echo '===daemon==='
cat /data/adb/aadusb/daemon.log
echo '===DISARM==='
nc1 DISARM
sleep 1
killall -9 aad-usb 2>/dev/null
sh /data/local/tmp/recover_usb.sh
echo DONE
