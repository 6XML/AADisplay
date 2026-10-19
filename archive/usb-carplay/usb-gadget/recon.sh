#!/system/bin/sh
# recon.sh — M0 kernel liveness recon for the aad-usb fake-iPhone plan.
#
# Run ON THE PHONE as root (Magisk shell, or `adb shell su -c sh recon.sh`).
# It reports whether this kernel can host the configfs fake-iPhone composite.
# Nothing is modified. Output: copy-paste back to the repo (docs/USB_CARPLAY.md M0).
#
# Expected PASS criteria for a viable M1 target:
#   [1] CONFIG_USB_CONFIGFS + CONFIG_USB_F_FS + CONFIG_USB_F_NCM all = y (or m)
#   [2] at least one UDC in /sys/class/udc
#   [3] current UDC driver + usb config (what we must restore on teardown)
#   [4] configfs mounted + a test gadget binds to the UDC (and unbinds clean)
#   [5] ffs + ncm functions available under /config/usb_gadget/<t>/functions
set -x
echo "=== [1] kernel config ==="
( zcat /proc/kconfig 2>/dev/null || cat /proc/config.gz 2>/dev/null | gunzip 2>/dev/null || cat /proc/config 2>/dev/null ) \
  | grep -E "CONFIG_USB_(CONFIGFS|F_FS|F_NCM|COMPOSITE|GADGET|FUNCTIONFS|NET)" || echo "no /proc/kconfig (CONFIG_IKCONFIG not set)"

echo "=== [1b] module presence (if =m) ==="
ls /system/lib/modules 2>/dev/null
ls /vendor/lib/modules 2>/dev/null
for m in usb_f_fs usb_f_ncm usb_f_configfs usb_gadget_configfs; do
  lsmod 2>/dev/null | grep -q "^$m" && echo "$m: loaded" || echo "$m: not loaded"
done

echo "=== [2] UDCs ==="
ls -l /sys/class/udc/ 2>/dev/null || echo "no /sys/class/udc"
for u in /sys/class/udc/*; do
  [ -e "$u" ] || continue
  n=$(basename "$u")
  echo "-- UDC $n"
  echo "   state:  $(cat $u/state 2>/dev/null)"
  echo "   driver: $(readlink $u/driver 2>/dev/null || echo none)"
done

echo "=== [3] current USB config (restore target) ==="
getprop sys.usb.config
getprop sys.usb.state
getprop init.svc.usb
getprop vendor.usb.* 2>/dev/null | head
ls /config/usb_gadget/ 2>/dev/null
for g in /config/usb_gadget/*/; do
  [ -e "$g/UDC" ] || continue
  u=$(cat "$g/UDC" 2>/dev/null)
  [ -n "$u" ] && echo "configfs gadget $(basename $g) -> UDC $u (functions: $(ls $g/functions/ 2>/dev/null | tr '\n' ' '))"
done

echo "=== [4] configfs + bind test (safe: unbinds at the end) ==="
if [ ! -d /config/usb_gadget ]; then
  echo "configfs NOT mounted — kernel lacks CONFIG_USB_CONFIGFS"
else
  T=/config/usb_gadget/aadusb_test
  rm -rf $T
  mkdir $T 2>/dev/null && echo "mkdir gadget OK" || echo "mkdir gadget FAILED (perms?)"
  # which functions exist in this kernel?
  ls $T/functions/ 2>/dev/null | tr '\n' ' '; echo
  if mkdir $T/functions/ffs.t 2>/dev/null; then
    echo "ffs function: OK"
  else
    echo "ffs function: MISSING (CONFIG_USB_F_FS?)"
  fi
  if mkdir $T/functions/ncm.0 2>/dev/null; then
    echo "ncm function: OK"
  else
    echo "ncm function: MISSING (CONFIG_USB_F_NCM?)"
  fi
  # try a bind with an unbound UDC only
  for u in /sys/class/udc/*; do
    [ -e "$u" ] || continue
    n=$(basename "$u")
    cur=$(readlink $u/driver 2>/dev/null)
    if [ -z "$cur" ]; then
      mkdir -p $T/strings/0x409 $T/configs/b.1 2>/dev/null
      echo $n > $T/UDC 2>/dev/null && echo "bind test: OK ($n was unbound)" \
        || echo "bind test: FAILED"
      echo "" > $T/UDC 2>/dev/null
      break
    fi
  done
  rm -rf $T
fi

echo "=== [5] ffs/ncm kernel modules loaded? ==="
lsmod 2>/dev/null | grep -E "usb_f|configfs" || echo "(lsmod unavailable or nothing)"

echo "=== [6] phone identity for the docs ==="
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.build.version.sdk
getprop ro.board.platform
getprop persist.vendor.usb.* 2>/dev/null | head

echo "=== done ==="
