#!/system/bin/sh
# aad-usb post-fs-data — data dirs + install binary before late_start service.
MODDIR=${0%/*}
mkdir -p /data/adb/aadusb/pair
# Prefer Magisk module payload (updated on each flash) over a stale copy.
if [ -f "$MODDIR/aad-usb" ]; then
  cp -f "$MODDIR/aad-usb" /data/adb/aadusb/aad-usb
  chmod 755 /data/adb/aadusb/aad-usb
fi
