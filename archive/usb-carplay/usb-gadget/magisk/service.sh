#!/system/bin/sh
# aad-usb service — starts the fake-iPhone USB gadget daemon at boot.
# The daemon stays IDLE (vendor USB untouched) until the AADisplay app
# sends ARM over 127.0.0.1:48350.
#
# Binary lives under /data/adb/aadusb/ (copied by post-fs-data from the
# Magisk module files/). No /system overlay needed.
MODDIR=${0%/*}
BIN=/data/adb/aadusb/aad-usb
[ -x "$BIN" ] || BIN="$MODDIR/aad-usb"
[ -x "$BIN" ] || {
  echo "aad-usb: binary missing" >> /data/adb/aadusb/daemon.log
  exit 1
}
mkdir -p /data/adb/aadusb/pair
[ -f /data/adb/aadusb/aad-usb.pid ] && kill "$(cat /data/adb/aadusb/aad-usb.pid)" 2>/dev/null
killall -9 aad-usb 2>/dev/null
# Boot safety: clear any leftover fake gadget (crash / limbo) and restore MTP/ADB.
printf '\n' > /config/usb_gadget/aadusb/UDC 2>/dev/null
rm -rf /config/usb_gadget/aadusb 2>/dev/null
for n in usbmux aadptp aadptp2 aadeth; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
setprop ctl.start vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.3
setprop sys.usb.config mtp,adb
sleep 1
"$BIN" --port=48350 >> /data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
