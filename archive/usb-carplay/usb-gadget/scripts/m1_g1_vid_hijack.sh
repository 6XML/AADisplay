#!/system/bin/sh
# Hijack stock g1 identity only (keep mtp/adb functions).
# If Mac configures with new VID, our separate aadusb gadget is the problem.
OUT=/data/adb/aadusb/g1_hijack_result.txt
exec >"$OUT" 2>&1
set -x
date
UDC=a600000.dwc3
G=/config/usb_gadget/g1

setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null

# Snapshot
echo "before vid=$(cat $G/idVendor) pid=$(cat $G/idProduct) udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state)"
OLDV=$(cat $G/idVendor)
OLDP=$(cat $G/idProduct)

# Freeze HAL so it doesn't rewrite g1 under us
setprop ctl.stop vendor.usb-hal-1-3
sleep 0.2

# Re-enum pulse with new IDs, same functions
printf '\n' > "$G/UDC" 2>/dev/null
sleep 0.3
echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
# Match stock power attrs if readable
echo 0x80 > "$G/configs/b.1/bmAttributes" 2>/dev/null
echo 900 > "$G/configs/b.1/MaxPower" 2>/dev/null
echo "$UDC" > "$G/UDC" || { echo BIND_FAIL; echo $OLDV > $G/idVendor; echo $OLDP > $G/idProduct; setprop ctl.start vendor.usb-hal-1-3; exit 1; }
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd

echo "after vid=$(cat $G/idVendor) pid=$(cat $G/idProduct) udc=$(cat $G/UDC)"
i=0
while [ $i -lt 18 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/$UDC/state) speed=$(cat /sys/class/udc/$UDC/current_speed) func=$(cat /sys/class/udc/$UDC/function) cfg=$(getprop sys.usb.config)"
  i=$((i + 1))
done

echo '===RESTORE==='
printf '\n' > "$G/UDC" 2>/dev/null
sleep 0.2
echo "$OLDV" > "$G/idVendor"
echo "$OLDP" > "$G/idProduct"
setprop ctl.start vendor.usb-hal-1-3
sleep 0.3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.2
setprop sys.usb.config mtp,adb
sleep 1
echo "final vid=$(cat $G/idVendor) pid=$(cat $G/idProduct) udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state) cfg=$(getprop sys.usb.config)"
echo DONE
