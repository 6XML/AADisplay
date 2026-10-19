#!/system/bin/sh
# Rebind stock g1 with new VID while keeping ffs.adb/mtp ready (do NOT kill adbd).
OUT=/data/adb/aadusb/g1_rebind_result.txt
exec >"$OUT" 2>&1
set -x
date
UDC=a600000.dwc3
G=/config/usb_gadget/g1

setprop service.adb.tcp.port 5555
# Freeze HAL rewriter only
setprop ctl.stop vendor.usb-hal-1-3
sleep 0.3

echo "before vid=$(cat $G/idVendor) pid=$(cat $G/idProduct) udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state)"
echo "ffs.adb.ready=$(cat $G/functions/ffs.adb/ready 2>/dev/null)"
echo "ffs.mtp.ready=$(cat $G/functions/ffs.mtp/ready 2>/dev/null)"
OLDV=$(cat $G/idVendor)
OLDP=$(cat $G/idProduct)
dmesg -C 2>/dev/null || true

# Unbind g1
printf '\n' > "$G/UDC"
sleep 0.5
echo "unbound udc_file='$(cat $G/UDC)' state=$(cat /sys/class/udc/$UDC/state)"
echo "ffs.adb.ready=$(cat $G/functions/ffs.adb/ready 2>/dev/null)"

echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
echo "ids now vid=$(cat $G/idVendor) pid=$(cat $G/idProduct)"

# Rebind
if echo "$UDC" > "$G/UDC" 2>/data/adb/aadusb/g1_rebind_err.txt; then
  echo "BIND_OK bound=$(cat $G/UDC)"
else
  echo "BIND_FAIL err=$(cat /data/adb/aadusb/g1_rebind_err.txt) errno_state=$(cat /sys/class/udc/$UDC/state)"
  # restore ids and try recover
  echo "$OLDV" > "$G/idVendor"
  echo "$OLDP" > "$G/idProduct"
  setprop ctl.start vendor.usb-hal-1-3
  setprop sys.usb.config none
  sleep 0.2
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
dmesg | grep -iE 'g1|configfs|gadget|dwc3|USB|ffs|configured|error|fail' | tail -60

echo '===RESTORE==='
printf '\n' > "$G/UDC"
sleep 0.2
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
