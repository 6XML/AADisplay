#!/usr/bin/env python3
"""Pair the fake iPhone via Apple MobileDevice.framework (not libimobiledevice).

CarPlay Simulator uses MobileDevice / SecureTransport trust from /var/db/lockdown
records written by AMDevicePair — idevicepair alone is not enough (UNKNOWN_CA).

  python3 scripts/mac_mobiledevice_pair.py
"""
from __future__ import annotations

import ctypes
import ctypes.util
import sys
import time

MD_PATH = (
    "/Library/Apple/System/Library/PrivateFrameworks/"
    "MobileDevice.framework/Versions/A/MobileDevice"
)
CF_PATH = (
    "/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation"
)

# Common AMDError codes
kAMDSuccess = 0
kAMDUndefinedError = 0xE8000001
kAMDPasswordProtected = 0xE800001A


def main() -> int:
    md = ctypes.CDLL(MD_PATH)
    cf = ctypes.CDLL(CF_PATH)

    CFStringGetCString = cf.CFStringGetCString
    CFStringGetCString.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_long,
        ctypes.c_uint32,
    ]
    CFStringGetCString.restype = ctypes.c_bool

    CFArrayGetCount = cf.CFArrayGetCount
    CFArrayGetCount.argtypes = [ctypes.c_void_p]
    CFArrayGetCount.restype = ctypes.c_long

    CFArrayGetValueAtIndex = cf.CFArrayGetValueAtIndex
    CFArrayGetValueAtIndex.argtypes = [ctypes.c_void_p, ctypes.c_long]
    CFArrayGetValueAtIndex.restype = ctypes.c_void_p

    CFRelease = cf.CFRelease
    CFRelease.argtypes = [ctypes.c_void_p]

    # AMDCreateDeviceList(void) -> CFArrayRef
    AMDCreateDeviceList = md.AMDCreateDeviceList
    AMDCreateDeviceList.restype = ctypes.c_void_p

    AMDeviceCopyDeviceIdentifier = md.AMDeviceCopyDeviceIdentifier
    AMDeviceCopyDeviceIdentifier.argtypes = [ctypes.c_void_p]
    AMDeviceCopyDeviceIdentifier.restype = ctypes.c_void_p

    AMDeviceConnect = md.AMDeviceConnect
    AMDeviceConnect.argtypes = [ctypes.c_void_p]
    AMDeviceConnect.restype = ctypes.c_int32

    AMDeviceDisconnect = md.AMDeviceDisconnect
    AMDeviceDisconnect.argtypes = [ctypes.c_void_p]
    AMDeviceDisconnect.restype = ctypes.c_int32

    AMDeviceIsPaired = md.AMDeviceIsPaired
    AMDeviceIsPaired.argtypes = [ctypes.c_void_p]
    AMDeviceIsPaired.restype = ctypes.c_int32

    AMDeviceValidatePairing = md.AMDeviceValidatePairing
    AMDeviceValidatePairing.argtypes = [ctypes.c_void_p]
    AMDeviceValidatePairing.restype = ctypes.c_int32

    AMDevicePair = md.AMDevicePair
    AMDevicePair.argtypes = [ctypes.c_void_p]
    AMDevicePair.restype = ctypes.c_int32

    AMDeviceUnpair = md.AMDeviceUnpair
    AMDeviceUnpair.argtypes = [ctypes.c_void_p]
    AMDeviceUnpair.restype = ctypes.c_int32

    AMDeviceDeleteHostPairingRecordForUDID = md.AMDeviceDeleteHostPairingRecordForUDID
    AMDeviceDeleteHostPairingRecordForUDID.argtypes = [ctypes.c_void_p]
    AMDeviceDeleteHostPairingRecordForUDID.restype = ctypes.c_int32

    AMDeviceStartSession = md.AMDeviceStartSession
    AMDeviceStartSession.argtypes = [ctypes.c_void_p]
    AMDeviceStartSession.restype = ctypes.c_int32

    AMDeviceStopSession = md.AMDeviceStopSession
    AMDeviceStopSession.argtypes = [ctypes.c_void_p]
    AMDeviceStopSession.restype = ctypes.c_int32

    def cfstr(ref) -> str:
        if not ref:
            return ""
        buf = ctypes.create_string_buffer(256)
        # kCFStringEncodingUTF8 = 0x08000100
        if CFStringGetCString(ref, buf, 256, 0x08000100):
            return buf.value.decode("utf-8", "replace")
        return ""

    deadline = time.time() + 20
    devices = None
    while time.time() < deadline:
        devices = AMDCreateDeviceList()
        if devices and CFArrayGetCount(devices) > 0:
            break
        if devices:
            CFRelease(devices)
            devices = None
        time.sleep(0.5)

    if not devices or CFArrayGetCount(devices) == 0:
        print("FAIL: MobileDevice sees no USB devices", flush=True)
        print("  Check: idevice_id -l ; ioreg | grep iPhone", flush=True)
        return 2

    n = CFArrayGetCount(devices)
    print(f"MobileDevice devices: {n}", flush=True)
    ok = 0
    for i in range(n):
        dev = CFArrayGetValueAtIndex(devices, i)
        udid_ref = AMDeviceCopyDeviceIdentifier(dev)
        udid = cfstr(udid_ref)
        print(f"[{i}] UDID={udid}", flush=True)

        rc = AMDeviceConnect(dev)
        print(f"  Connect => 0x{rc & 0xFFFFFFFF:08x}", flush=True)
        if rc != kAMDSuccess:
            if udid_ref:
                CFRelease(udid_ref)
            continue

        # Always clear stale host records first — ValidatePairing on a bad
        # EscrowBag/Root pair hangs or returns kAMDInvalidResponseError.
        print(f"  Unpair => 0x{AMDeviceUnpair(dev) & 0xFFFFFFFF:08x}", flush=True)
        if udid_ref:
            print(
                f"  DeleteHostPairingRecord => "
                f"0x{AMDeviceDeleteHostPairingRecordForUDID(udid_ref) & 0xFFFFFFFF:08x}",
                flush=True,
            )

        paired = AMDeviceIsPaired(dev)
        print(f"  IsPaired => {paired}", flush=True)

        rc = AMDevicePair(dev)
        print(f"  Pair => 0x{rc & 0xFFFFFFFF:08x}", flush=True)

        if rc == kAMDSuccess:
            rc = AMDeviceValidatePairing(dev)
            print(f"  ValidatePairing => 0x{rc & 0xFFFFFFFF:08x}", flush=True)
            if rc == kAMDSuccess:
                # Pair's own StartSession+TLS already proved trust. A second
                # AMDeviceStartSession often times out (0xe800000c) even when
                # ideviceinfo works — Validate is the Simulator gate.
                print("  PASS: MobileDevice Pair+ValidatePairing", flush=True)
                ok += 1
            else:
                print("  FAIL: ValidatePairing", flush=True)
        else:
            print("  FAIL: Pair", flush=True)

        AMDeviceDisconnect(dev)
        if udid_ref:
            CFRelease(udid_ref)

    CFRelease(devices)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
