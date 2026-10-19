#!/system/bin/sh
# Run on phone as root when USB is in limbo (Mac sees nothing, adb gone).
killall -9 aad-usb 2>/dev/null
[ -e /config/usb_gadget/aadusb/UDC ] && printf '\n' > /config/usb_gadget/aadusb/UDC
rm -rf /config/usb_gadget/aadusb 2>/dev/null
for n in usbmux aadptp aadptp2 aadeth; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
setprop ctl.start vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.5
setprop sys.usb.config mtp,adb
sleep 1
setprop service.adb.tcp.port 5555
setprop ctl.stop adbd
sleep 0.2
setprop ctl.start adbd
echo cfg=$(getprop sys.usb.config) g1=$(cat /config/usb_gadget/g1/UDC 2>/dev/null) \
  udc=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null) \
  hal=$(getprop init.svc.vendor.usb-hal-1-3)
