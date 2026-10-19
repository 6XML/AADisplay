#!/system/bin/sh
# Minimal configfs probe: Google VID + kernel NCM only (no FunctionFS).
# If Mac reaches configured here, our ffs descriptors are the problem.
OUT=/data/adb/aadusb/ncm_only_result.txt
exec >"$OUT" 2>&1
set -x
date
UDC=a600000.dwc3
G=/config/usb_gadget/aadtest

setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null
setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.3
[ -e /config/usb_gadget/g1/UDC ] && printf '\n' > /config/usb_gadget/g1/UDC
[ -e /config/usb_gadget/aadusb/UDC ] && printf '\n' > /config/usb_gadget/aadusb/UDC
rm -rf /config/usb_gadget/aadusb /config/usb_gadget/aadtest 2>/dev/null
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
sleep 0.5

mkdir -p "$G" || exit 1
echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
echo 0x0100 > "$G/bcdDevice"
echo 0x0200 > "$G/bcdUSB" 2>/dev/null
echo 0x40 > "$G/bMaxPacketSize0" 2>/dev/null
echo 0x00 > "$G/bDeviceClass" 2>/dev/null
echo 0x00 > "$G/bDeviceSubClass" 2>/dev/null
echo 0x00 > "$G/bDeviceProtocol" 2>/dev/null
mkdir -p "$G/strings/0x409"
echo AADisplay > "$G/strings/0x409/manufacturer"
echo ncm-only > "$G/strings/0x409/product"
echo AADTEST001 > "$G/strings/0x409/serialnumber" 2>/dev/null \
  || echo AADTEST001 > "$G/strings/0x409/serial"
mkdir -p "$G/functions/ncm.0" || { echo 'ncm.0 mkdir FAIL'; exit 1; }
mkdir -p "$G/configs/b.1"
echo 250 > "$G/configs/b.1/MaxPower"
ln -s "$G/functions/ncm.0" "$G/configs/b.1/ncm.0"
ls -la "$G/configs/b.1"
echo "$UDC" > "$G/UDC" || { echo 'bind FAIL'; cat "$G/UDC"; exit 1; }
echo bound=$(cat "$G/UDC")
i=0
while [ $i -lt 20 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/$UDC/state) speed=$(cat /sys/class/udc/$UDC/current_speed) func=$(cat /sys/class/udc/$UDC/function)"
  i=$((i + 1))
done
echo '===TEARDOWN==='
printf '\n' > "$G/UDC"
rm -rf "$G"
sh /data/local/tmp/recover_usb.sh
echo DONE
