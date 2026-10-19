#!/system/bin/sh
# NCM-only with stock-g1-matching attrs + dmesg capture.
OUT=/data/adb/aadusb/ncm_g1attrs_result.txt
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
printf '\n' > /config/usb_gadget/g1/UDC 2>/dev/null
printf '\n' > /config/usb_gadget/aadusb/UDC 2>/dev/null
printf '\n' > /config/usb_gadget/aadtest/UDC 2>/dev/null
# best-effort cleanup
rm -rf /config/usb_gadget/aadusb 2>/dev/null
# unbind+rm aadtest leftovers carefully
printf '\n' > /config/usb_gadget/aadtest/UDC 2>/dev/null
rm -f /config/usb_gadget/aadtest/configs/b.1/ncm.0 2>/dev/null
rmdir /config/usb_gadget/aadtest/configs/b.1/strings 2>/dev/null
rmdir /config/usb_gadget/aadtest/configs/b.1 2>/dev/null
rmdir /config/usb_gadget/aadtest/configs 2>/dev/null
rmdir /config/usb_gadget/aadtest/functions/ncm.0 2>/dev/null
rmdir /config/usb_gadget/aadtest/functions 2>/dev/null
rmdir /config/usb_gadget/aadtest/strings/0x409 2>/dev/null
rmdir /config/usb_gadget/aadtest/strings 2>/dev/null
rmdir /config/usb_gadget/aadtest 2>/dev/null

setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
dmesg -C 2>/dev/null || true
sleep 0.3

mkdir -p "$G" || exit 1
# Match stock g1 identity attrs as closely as possible
echo 0x18d1 > "$G/idVendor"
echo 0x0001 > "$G/idProduct"
echo 0x0504 > "$G/bcdDevice"
echo 0x0201 > "$G/bcdUSB"
echo 0x40 > "$G/bMaxPacketSize0"
echo 0x00 > "$G/bDeviceClass"
echo 0x00 > "$G/bDeviceSubClass"
echo 0x00 > "$G/bDeviceProtocol"
echo super-speed-plus > "$G/max_speed" 2>/dev/null || echo high-speed > "$G/max_speed" 2>/dev/null
echo "max_speed=$(cat $G/max_speed 2>/dev/null)"
mkdir -p "$G/strings/0x409"
echo AADisplay > "$G/strings/0x409/manufacturer"
echo ncm-g1attrs > "$G/strings/0x409/product"
echo AADTEST001 > "$G/strings/0x409/serialnumber"
mkdir -p "$G/functions/ncm.0" || exit 1
mkdir -p "$G/configs/b.1/strings/0x409"
echo "config1" > "$G/configs/b.1/strings/0x409/configuration" 2>/dev/null
echo 0x80 > "$G/configs/b.1/bmAttributes"
echo 900 > "$G/configs/b.1/MaxPower"
# Stock uses f1/f2 names
ln -s "$G/functions/ncm.0" "$G/configs/b.1/f1"
# os_desc like stock (optional)
mkdir -p "$G/os_desc" 2>/dev/null
echo 1 > "$G/os_desc/use" 2>/dev/null
echo 0xcd > "$G/os_desc/b_vendor_code" 2>/dev/null
echo MSFT100 > "$G/os_desc/qw_sign" 2>/dev/null
ln -s "$G/configs/b.1" "$G/os_desc/b.1" 2>/dev/null

echo "=== pre-bind tree ==="
ls -la "$G" "$G/configs/b.1" "$G/functions"
echo "$UDC" > "$G/UDC"
echo "bind_rc=$? bound=$(cat $G/UDC)"

i=0
while [ $i -lt 15 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/$UDC/state) speed=$(cat /sys/class/udc/$UDC/current_speed) func=$(cat /sys/class/udc/$UDC/function)"
  i=$((i + 1))
done

echo '===DMESG==='
dmesg | grep -iE 'aadtest|configfs|gadget|dwc3|ncm|error|fail|USB' | tail -80

echo '===TEARDOWN==='
printf '\n' > "$G/UDC"
rm -f "$G/os_desc/b.1" "$G/configs/b.1/f1"
rmdir "$G/configs/b.1/strings/0x409" 2>/dev/null
rmdir "$G/configs/b.1/strings" 2>/dev/null
rmdir "$G/configs/b.1" 2>/dev/null
rmdir "$G/configs" 2>/dev/null
rmdir "$G/functions/ncm.0" 2>/dev/null
rmdir "$G/functions" 2>/dev/null
rmdir "$G/strings/0x409" 2>/dev/null
rmdir "$G/strings" 2>/dev/null
rmdir "$G/os_desc" 2>/dev/null
rmdir "$G" 2>/dev/null
sh /data/local/tmp/recover_usb.sh
echo DONE
