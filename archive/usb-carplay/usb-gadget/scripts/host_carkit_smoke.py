#!/usr/bin/env python3
"""Host-side carkit StartService smoke via macOS /var/run/usbmuxd.

Path exercised:
  ListDevices → Connect(62078) → QueryType → StartSession →
  StartService(com.apple.carkit.service) → Connect(Port) → write probe bytes

Expect phone daemon.log:
  lockdown: StartService com.apple.carkit.service -> mux port N
  mux bridge port N -> carkit publish
  bridge: carkit stream on 127.0.0.1:…
"""
from __future__ import annotations

import plistlib
import socket
import ssl
import struct
import sys
import time

USBMUX = "/var/run/usbmuxd"
LOCKDOWN_PORT = 62078
CARKIT = "com.apple.carkit.service"


def htons_as_int(port: int) -> int:
    # usbmux stores PortNumber as htons(port) in a 32-bit integer field
    return socket.htons(port) & 0xFFFF


class Usbmux:
    def __init__(self) -> None:
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.s.settimeout(8.0)
        self.s.connect(USBMUX)
        self.tag = 1

    def close(self) -> None:
        try:
            self.s.close()
        except OSError:
            pass

    def _send_plist(self, d: dict) -> None:
        body = plistlib.dumps(d, fmt=plistlib.FMT_XML)
        hdr = struct.pack("<IIII", 16 + len(body), 1, 8, self.tag)
        self.tag += 1
        self.s.sendall(hdr + body)

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise EOFError("usbmux closed")
            buf += chunk
        return buf

    def _recv_plist(self) -> dict:
        hdr = self._recv_exact(16)
        length, ver, mtype, tag = struct.unpack("<IIII", hdr)
        if length < 16:
            raise RuntimeError(f"bad usbmux length {length}")
        body = self._recv_exact(length - 16)
        if mtype != 8:
            raise RuntimeError(f"unexpected usbmux type {mtype} ver={ver} tag={tag}")
        return plistlib.loads(body)

    def list_devices(self) -> list[dict]:
        self._send_plist(
            {
                "MessageType": "ListDevices",
                "ClientVersionString": "aad-carkit-smoke",
                "ProgName": "aad-carkit-smoke",
                "kLibUSBMuxVersion": 3,
            }
        )
        resp = self._recv_plist()
        return list(resp.get("DeviceList") or [])

    def connect(self, device_id: int, port: int) -> None:
        self._send_plist(
            {
                "MessageType": "Connect",
                "ClientVersionString": "aad-carkit-smoke",
                "ProgName": "aad-carkit-smoke",
                "kLibUSBMuxVersion": 3,
                "DeviceID": device_id,
                "PortNumber": htons_as_int(port),
            }
        )
        resp = self._recv_plist()
        # Result / Number: 0 = OK
        num = resp.get("Number", resp.get("Result"))
        if num not in (0, None) and resp.get("MessageType") not in (None, "Result"):
            # Apple: MessageType=Result, Number=0
            pass
        if isinstance(num, int) and num != 0:
            raise RuntimeError(f"Connect failed: {resp}")
        if resp.get("MessageType") == "Result" and int(resp.get("Number", 1)) != 0:
            raise RuntimeError(f"Connect failed: {resp}")


class Lockdown:
    def __init__(self, sock: socket.socket) -> None:
        self.s = sock
        self.s.settimeout(8.0)

    def _send(self, d: dict) -> None:
        body = plistlib.dumps(d, fmt=plistlib.FMT_XML)
        self.s.sendall(struct.pack(">I", len(body)) + body)

    def _recv(self) -> dict:
        hdr = self.s.recv(4)
        if len(hdr) < 4:
            raise EOFError("lockdown closed")
        (n,) = struct.unpack(">I", hdr)
        body = b""
        while len(body) < n:
            chunk = self.s.recv(n - len(body))
            if not chunk:
                raise EOFError("lockdown body short")
            body += chunk
        return plistlib.loads(body)

    def request(self, d: dict) -> dict:
        self._send(d)
        return self._recv()


def main() -> int:
    deadline = time.time() + 25
    devices = []
    while time.time() < deadline:
        mux = Usbmux()
        try:
            devices = mux.list_devices()
        except Exception as e:
            print(f"ListDevices err: {e}", flush=True)
            devices = []
        finally:
            mux.close()
        if devices:
            break
        time.sleep(0.5)

    if not devices:
        print("FAIL: no usbmux devices", flush=True)
        return 2

    # Prefer first attached device
    dev = devices[0]
    props = dev.get("Properties") or {}
    did = int(dev.get("DeviceID") or props.get("DeviceID") or 0)
    udid = props.get("SerialNumber") or props.get("UDID") or "?"
    print(f"device id={did} udid={udid} props_keys={list(props.keys())[:8]}", flush=True)
    if did <= 0:
        print(f"FAIL: bad DeviceID in {dev}", flush=True)
        return 2

    # lockdown session
    mux = Usbmux()
    try:
        mux.connect(did, LOCKDOWN_PORT)
        print("Connect lockdown OK", flush=True)
        ld = Lockdown(mux.s)

        r = ld.request({"Label": "aad", "Request": "QueryType"})
        print(f"QueryType -> {r.get('Type')}", flush=True)

        r = ld.request({"Label": "aad", "Request": "StartSession", "HostID": "AAD-SMOKE-HOST"})
        print(
            f"StartSession -> SessionID={r.get('SessionID')} SSL={r.get('EnableSessionSSL')}",
            flush=True,
        )
        if r.get("Error"):
            print(f"StartSession error: {r}", flush=True)
        if r.get("EnableSessionSSL"):
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            mux.s = ctx.wrap_socket(mux.s, server_hostname="Device")
            ld = Lockdown(mux.s)
            print("lockdown TLS wrapped", flush=True)

        r = ld.request(
            {
                "Label": "aad",
                "Request": "StartService",
                "Service": CARKIT,
            }
        )
        print(f"StartService -> {r}", flush=True)
        if r.get("Error"):
            print(f"FAIL: StartService error {r.get('Error')}", flush=True)
            return 3
        port = int(r.get("Port") or 0)
        if port <= 0:
            print("FAIL: no Port in StartService response", flush=True)
            return 3
        print(f"carkit mux port={port}", flush=True)
    finally:
        mux.close()

    # dial the bridge port (triggers phone-side carkit publish)
    mux2 = Usbmux()
    try:
        mux2.connect(did, port)
        print("Connect carkit OK", flush=True)
        # probe bytes so bridge pump sees traffic
        try:
            mux2.s.sendall(b"\xff\x55\x02\x00AAD_CARKIT_SMOKE")
            time.sleep(0.4)
        except OSError as e:
            print(f"probe write: {e}", flush=True)
        print("PASS: StartService+Connect carkit", flush=True)
        return 0
    except Exception as e:
        print(f"FAIL: Connect carkit: {e}", flush=True)
        return 4
    finally:
        mux2.close()


if __name__ == "__main__":
    sys.exit(main())
