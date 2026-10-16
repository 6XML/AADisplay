#!/system/bin/sh
# aad-usb service — starts the fake-iPhone USB gadget daemon at boot.
# The daemon stays IDLE (vendor USB untouched) until the AADisplay app
# sends ARM over 127.0.0.1:48350 AND a cable is attached.
[ -f /data/adb/aadusb/aad-usb.pid ] && kill "$(cat /data/adb/aadusb/aad-usb.pid)" 2>/dev/null
sleep 1
/system/adb/aadusb/aad-usb --port 48350 >> /data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
