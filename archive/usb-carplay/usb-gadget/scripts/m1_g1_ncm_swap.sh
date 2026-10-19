#!/system/bin/sh
# After config=none, rebuild g1 configs to ncm-only + Google VID, then bind g1.
# Uses the stock gadget name "g1" so Samsung usb_notify/phy stay happy.
OUT=/data/adb/aadusb/g1_ncm_swap_result.txt
exec >"$OUT" 2>&1
set -x
date
UDC=a600000.dwc3
G=/config/usb_gadget/g1

setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null
OLDV=$(cat $G/idVendor)
OLDP=$(cat $G/idProduct)

setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 1
echo "after none g1udc='$(cat $G/UDC)' state=$(cat /sys/class/udc/$UDC/state)"
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd

# Clear existing config function links (f1..f9)
for f in "$G/configs/b.1"/f*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  rm -f "$f"
  echo "removed $f"
done
rm -f "$G/os_desc/b.1" 2>/dev/null

# Ensure ncm.0 function exists (stock has it)
if [ ! -d "$G/functions/ncm.0" ]; then
  mkdir "$G/functions/ncm.0" || { echo 'ncm.0 missing'; exit 1; }
fi
ln -s "$G/functions/ncm.0" "$G/configs/b.1/f1"
echo 0x80 > "$G/configs/b.1/bmAttributes"
echo 900 > "$G/configs/b.1/MaxPower"
ln -s "$G/configs/b.1" "$G/os_desc/b.1" 2>/dev/null

echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
echo "vid=$(cat $G/idVendor) links:"; ls -la "$G/configs/b.1"

dmesg -C 2>/dev/null || true
if echo "$UDC" > "$G/UDC" 2>/data/adb/aadusb/bind_err.txt; then
  echo "BIND_OK $(cat $G/UDC)"
else
  echo "BIND_FAIL $(cat /data/adb/aadusb/bind_err.txt)"
fi

i=0
while [ $i -lt 16 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/$UDC/state) speed=$(cat /sys/class/udc/$UDC/current_speed) func=$(cat /sys/class/udc/$UDC/function) vid=$(cat $G/idVendor)"
  i=$((i + 1))
done

echo '===DMESG==='
dmesg | grep -iE 'g1|configfs|gadget|dwc3|USB|ncm|configured|PULLUP|error|fail' | tail -60

echo '===RESTORE via HAL==='
printf '\n' > "$G/UDC" 2>/dev/null
rm -f "$G/configs/b.1/f1"
echo "$OLDV" > "$G/idVendor"
echo "$OLDP" > "$G/idProduct"
setprop ctl.start vendor.usb-hal-1-3
sleep 0.5
setprop sys.usb.config none
sleep 0.3
setprop sys.usb.config mtp,adb
sleep 2
echo "final vid=$(cat $G/idVendor) udc=$(cat $G/UDC) state=$(cat /sys/class/udc/$UDC/state) cfg=$(getprop sys.usb.config)"
ls -la "$G/configs/b.1"
echo DONE
