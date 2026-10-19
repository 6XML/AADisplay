#!/system/bin/sh
# Release via sys.usb.config=none (Samsung init path), then rebind g1
# with a new VID — proves whether g1+new-VID can reach configured on Mac.
OUT=/data/adb/aadusb/g1_via_none_result.txt
exec >"$OUT" 2>&1
set -x
date
UDC=a600000.dwc3
G=/config/usb_gadget/g1

setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null
OLDV=$(cat $G/idVendor)
OLDP=$(cat $G/idProduct)
echo "before vid=$OLDV pid=$OLDP udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state)"

setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.8
echo "after none: g1udc='$(cat $G/UDC)' state=$(cat /sys/class/udc/$UDC/state)"

# If still bound, force
if [ -n "$(cat $G/UDC 2>/dev/null)" ]; then
  echo -n > "$G/UDC" 2>/data/adb/aadusb/unbind_err.txt || true
  printf '\n' > "$G/UDC" 2>>/data/adb/aadusb/unbind_err.txt || true
  sleep 0.3
  echo "force unbind: g1udc='$(cat $G/UDC)' err=$(cat /data/adb/aadusb/unbind_err.txt 2>/dev/null)"
fi

# Keep adbd for TCP; try to get ffs ready again
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
# Wait for ffs.adb ready (adbd rewrites descriptors)
i=0
while [ $i -lt 20 ]; do
  r=$(cat $G/functions/ffs.adb/ready 2>/dev/null)
  echo "wait ffs.adb.ready='$r' t=$i"
  [ "$r" = "1" ] && break
  sleep 0.25
  i=$((i + 1))
done
echo "ffs.mtp.ready=$(cat $G/functions/ffs.mtp/ready 2>/dev/null)"

echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
# Ensure config links still present
ls -la "$G/configs/b.1"

dmesg -C 2>/dev/null || true
if echo "$UDC" > "$G/UDC" 2>/data/adb/aadusb/bind_err.txt; then
  echo "BIND_OK bound=$(cat $G/UDC)"
else
  echo "BIND_FAIL $(cat /data/adb/aadusb/bind_err.txt)"
  echo "$OLDV" > "$G/idVendor"; echo "$OLDP" > "$G/idProduct"
  setprop ctl.start vendor.usb-hal-1-3
  setprop sys.usb.config mtp,adb
  echo DONE_FAIL
  exit 1
fi

i=0
while [ $i -lt 16 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/$UDC/state) speed=$(cat /sys/class/udc/$UDC/current_speed) func=$(cat /sys/class/udc/$UDC/function) vid=$(cat $G/idVendor)"
  i=$((i + 1))
done

echo '===DMESG==='
dmesg | grep -iE 'g1|configfs|gadget|dwc3|USB|ffs|configured|error|fail|PULLUP' | tail -50

echo '===RESTORE==='
printf '\n' > "$G/UDC" 2>/dev/null
echo -n > "$G/UDC" 2>/dev/null
echo "$OLDV" > "$G/idVendor"
echo "$OLDP" > "$G/idProduct"
setprop ctl.start vendor.usb-hal-1-3
sleep 0.3
setprop sys.usb.config none
sleep 0.2
setprop sys.usb.config mtp,adb
sleep 1
echo "final vid=$(cat $G/idVendor) udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state) cfg=$(getprop sys.usb.config)"
echo DONE
