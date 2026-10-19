#!/system/bin/sh
# 120s hold + TCP echo on usb0:19227 for host_ncm_tcp_smoke.py
OUT=/data/adb/aadusb/hold_ncm_tcp.txt
exec >"$OUT" 2>&1
set -x
date
nc1() {
  (printf "%s\n" "$1"; sleep 0.2) | toybox nc 127.0.0.1 48350 &
  NP=$!
  sleep 0.7
  kill $NP 2>/dev/null
  wait $NP 2>/dev/null
}
GADGET=/config/usb_gadget/g1
setprop service.adb.tcp.port 5555
killall -9 aad-usb 2>/dev/null
pkill -9 -f 'nc -6 -l -p 19227' 2>/dev/null
sleep 0.5
for n in usbmux aadptp aadptp2 aadeth; do
  umount /dev/usb-ffs/$n 2>/dev/null
  rmdir /dev/usb-ffs/$n 2>/dev/null
done
[ -e /config/usb_gadget/aadusb/UDC ] && printf '\n' > /config/usb_gadget/aadusb/UDC
rm -rf /config/usb_gadget/aadusb 2>/dev/null
setprop ctl.stop vendor.usb-hal-1-3
setprop sys.usb.configfs 1
setprop sys.usb.config none
sleep 0.3
[ -e /config/usb_gadget/g1/UDC ] && printf '\n' > /config/usb_gadget/g1/UDC
setprop ctl.stop adbd; sleep 0.2; setprop ctl.start adbd
sleep 1
BIN=/data/adb/aadusb/aad-usb
: > /data/adb/aadusb/daemon.log
"$BIN" --port=48350 >>/data/adb/aadusb/daemon.log 2>&1 &
echo $! > /data/adb/aadusb/aad-usb.pid
sleep 1
nc1 ARM
# wait for usb0 LOWER_UP then start echo
i=0
while [ $i -lt 20 ]; do
  st=$(cat /sys/class/net/usb0/operstate 2>/dev/null)
  [ "$st" = "up" ] && break
  sleep 1
  i=$((i + 1))
done
PL=$(ip -6 addr show usb0 | sed -n 's/.*inet6 \(fe80[^ ]*\)\/.*scope link.*/\1/p' | head -1)
echo "usb0_ll=$PL mac=$(cat /sys/class/net/usb0/address) route=$(ip -6 route show dev usb0)"
: > /data/adb/aadusb/ncm_nc_in.txt
# persistent listen (toybox may exit after one conn — loop)
(
  while true; do
    toybox nc -6 -l -p 19227 >>/data/adb/aadusb/ncm_nc_in.txt 2>>/data/adb/aadusb/ncm_nc_err.txt
    sleep 0.1
  done
) &
echo $! > /data/adb/aadusb/ncm_nc.pid
i=0
while [ $i -lt 180 ]; do
  sleep 1
  echo "t=$i udc=$(cat /sys/class/udc/a600000.dwc3/state) usb0=$(cat /sys/class/net/usb0/operstate 2>/dev/null)"
  i=$((i + 1))
done
echo '===DISARM==='
kill $(cat /data/adb/aadusb/ncm_nc.pid) 2>/dev/null
pkill -9 -f 'nc -6 -l -p 19227' 2>/dev/null
nc1 DISARM
sleep 1
killall -9 aad-usb 2>/dev/null
sh /data/local/tmp/recover_usb.sh
echo DONE
