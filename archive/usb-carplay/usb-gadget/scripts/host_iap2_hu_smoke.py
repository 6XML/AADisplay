#!/usr/bin/env python3
"""Host-side iAP2 HU (Vehicle/USBHost) smoke over usbmux carkit.

Keeps the carkit stream open and speaks enough accessory-role iAP2 for the
phone (fake iPhone / AADisplay Source) to advance past StartIdentification:

  phone 0x1D00 → HU 0x1D01 (Vehicle + USBHost + declare_wired)
  phone 0x1D02 → phone 0xAA00 → HU 0xAA01 → phone 0xAA02 → HU 0xAA03
  phone 0xAA05 → phone 0x4300 → HU 0x4301 (NCM IP + AirPlay port)

Does **not** run a real AirPlay receiver — only unblocks app dial of 0x4301.
Grounded in ncm_carplayd `iap2_auth.c` IdentifyInfo / CarPlayStartSession layout
and AADisplay `Iap2Framing` / `Iap2Csm` (device / Source role mirrored).

Usage (Flip4 ARMed, Mac sees 05AC, NCM up, App on cp-usb preferred):
  python3 usb-gadget/scripts/host_iap2_hu_smoke.py
  python3 usb-gadget/scripts/host_iap2_hu_smoke.py --ip 169.254.1.1 --port 7000 --hold 120
"""
from __future__ import annotations

import argparse
import os
import plistlib
import socket
import ssl
import struct
import sys
import time
from typing import Optional

USBMUX = "/var/run/usbmuxd"
LOCKDOWN_PORT = 62078
CARKIT = "com.apple.carkit.service"

# iAP2
SOP = b"\xff\x5a"
CTL_ACK = 0x40
CTL_SYN = 0x80
CSM_START = 0x4040

MSG_START_IDENT = 0x1D00
MSG_IDENT_INFO = 0x1D01
MSG_IDENT_ACCEPTED = 0x1D02
MSG_REQ_AUTH_CERT = 0xAA00
MSG_AUTH_CERT = 0xAA01
MSG_REQ_AUTH_CHALLENGE = 0xAA02
MSG_AUTH_RESPONSE = 0xAA03
MSG_AUTH_SUCCEEDED = 0xAA05
MSG_CARPLAY_AVAIL = 0x4300
MSG_CARPLAY_START = 0x4301


def htons_as_int(port: int) -> int:
    return socket.htons(port) & 0xFFFF


def cks(data: bytes) -> int:
    return (-sum(data)) & 0xFF


def be16(n: int) -> bytes:
    return struct.pack(">H", n & 0xFFFF)


def be32(n: int) -> bytes:
    return struct.pack(">I", n & 0xFFFFFFFF)


def tlv(param_id: int, value: bytes) -> bytes:
    return be16(4 + len(value)) + be16(param_id) + value


def tlv_str(param_id: int, s: str) -> bytes:
    return tlv(param_id, (s + "\x00").encode("utf-8"))


def tlv_u8(param_id: int, v: int) -> bytes:
    return tlv(param_id, bytes([v & 0xFF]))


def tlv_u16(param_id: int, v: int) -> bytes:
    return tlv(param_id, be16(v))


def tlv_none(param_id: int) -> bytes:
    return tlv(param_id, b"")


def encode_csm(message_id: int, params: bytes = b"") -> bytes:
    body = be16(CSM_START) + be16(6 + len(params)) + be16(message_id) + params
    return body


def encode_link(control: int, seq: int, ack: int, session: int, payload: bytes = b"") -> bytes:
    total = 9 if not payload else 10 + len(payload)
    hdr = SOP + be16(total) + bytes([control & 0xFF, seq & 0xFF, ack & 0xFF, session & 0xFF])
    out = bytearray(hdr)
    out.append(cks(hdr))
    if payload:
        out.extend(payload)
        out.append(cks(payload))
    return bytes(out)


def parse_csm(payload: bytes) -> Optional[tuple[int, bytes]]:
    if len(payload) < 6:
        return None
    if payload[0] != 0x40 or payload[1] != 0x40:
        return None
    declared = (payload[2] << 8) | payload[3]
    if declared != len(payload):
        return None
    mid = (payload[4] << 8) | payload[5]
    return mid, payload[6:]


def build_ident_info(cp_iface: int = 2) -> bytes:
    """Accessory-role 0x1D01: Vehicle + USBHost + declare_wired (0x4301 sent / 0x4300 recv)."""
    name = "AAD-HU-Smoke"
    params = bytearray()
    params += tlv_str(0, name)
    params += tlv_str(1, "AAD-USB-HU")
    params += tlv_str(2, "AADisplay")
    params += tlv_str(3, "2026.10.19.smoke")
    params += tlv_str(4, "1.0.0")
    params += tlv_str(5, "1.0.0")
    # messagesSentByAccessory (6) / messagesReceivedFromDevice (7)
    sent = be16(0x4154) + be16(0x5000) + be16(0x5002) + be16(0x5200) + be16(0x5203) + be16(0x4301)
    rcv = (
        be16(0x4E0A)
        + be16(0x4E0B)
        + be16(0x4E0E)
        + be16(0x4155)
        + be16(0x5001)
        + be16(0x5201)
        + be16(0x5202)
        + be16(0x4300)
        + be16(0xAA00)
        + be16(0xAA02)
        + be16(0xAA05)
        + be16(0x1D00)
        + be16(0x1D02)
    )
    params += tlv(6, sent)
    params += tlv(7, rcv)
    params += tlv_u8(8, 2)  # powerProvidingCapability
    params += tlv_u16(9, 0)
    params += tlv_str(12, "en")
    params += tlv_str(13, "en")
    # id16 USBHostTransportComponent
    usb = bytearray()
    usb += tlv_u16(0, 1)
    usb += tlv_str(1, "IAP2-USBHost")
    usb += tlv_none(2)
    usb += tlv_u8(3, cp_iface & 0xFF)
    usb += tlv_none(4)  # transportSupportsCarPlay
    params += tlv(16, bytes(usb))
    # id20 vehicleInformationComponent
    veh = bytearray()
    veh += tlv_u16(0, 2)
    veh += tlv_str(1, name)
    veh += tlv_u8(2, 0)
    veh += tlv_str(6, name)
    params += tlv(20, bytes(veh))
    return encode_csm(MSG_IDENT_INFO, bytes(params))


def build_auth_cert() -> bytes:
    # Lab path: phone accepts without MFi verify. Minimal DER-ish blob.
    leaf = b"\x30\x82\x01\x00" + (b"\x00" * 252)
    return encode_csm(MSG_AUTH_CERT, tlv(0, leaf) + tlv_u8(1, 0))


def build_auth_response(challenge: bytes) -> bytes:
    # Dummy signature same length as challenge (phone does not verify).
    sig = bytes((b ^ 0x5A) for b in challenge) if challenge else b"\x00" * 32
    return encode_csm(MSG_AUTH_RESPONSE, tlv(0, sig))


def build_cp_start(ip: str, port: int) -> bytes:
    wired = tlv_str(0, ip)
    params = tlv(0, wired) + tlv(2, be32(port))
    params += tlv_str(3, "AA:BB:CC:DD:EE:01")
    params += tlv(4, bytes(range(32)))  # placeholder Ed25519 LTPK
    params += tlv_str(5, "320.17")
    return encode_csm(MSG_CARPLAY_START, params)


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
        length, _ver, mtype, _tag = struct.unpack("<IIII", hdr)
        body = self._recv_exact(length - 16)
        if mtype != 8:
            raise RuntimeError(f"unexpected usbmux type {mtype}")
        return plistlib.loads(body)

    def list_devices(self) -> list[dict]:
        self._send_plist(
            {
                "MessageType": "ListDevices",
                "ClientVersionString": "aad-iap2-hu-smoke",
                "ProgName": "aad-iap2-hu-smoke",
                "kLibUSBMuxVersion": 3,
            }
        )
        return list(self._recv_plist().get("DeviceList") or [])

    def connect(self, device_id: int, port: int) -> None:
        self._send_plist(
            {
                "MessageType": "Connect",
                "ClientVersionString": "aad-iap2-hu-smoke",
                "ProgName": "aad-iap2-hu-smoke",
                "kLibUSBMuxVersion": 3,
                "DeviceID": device_id,
                "PortNumber": htons_as_int(port),
            }
        )
        resp = self._recv_plist()
        print(f"Connect resp: {resp}", flush=True)
        # Require an explicit Number/Result == 0 (missing key used to false-pass).
        num = resp.get("Number", resp.get("Result"))
        if num is None:
            raise RuntimeError(f"Connect missing Number/Result: {resp}")
        if int(num) != 0:
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


class Iap2Hu:
    def __init__(self, sock: socket.socket, airplay_ip: str, airplay_port: int, cp_iface: int) -> None:
        self.s = sock
        self.s.settimeout(1.0)
        self.ip = airplay_ip
        self.port = airplay_port
        self.cp_iface = cp_iface
        self.my_seq = 0
        self.peer_seq = 0
        self.session = 0x01
        self.buf = bytearray()
        self.saw_1d00 = False
        self.saw_1d02 = False
        self.saw_aa00 = False
        self.saw_aa02 = False
        self.saw_aa05 = False
        self.saw_4300 = False
        self.sent_4301 = False
        self.pending_challenge: Optional[bytes] = None

    def send_csm(self, csm: bytes) -> None:
        self.my_seq = (self.my_seq + 1) & 0xFF
        frame = encode_link(CTL_ACK, self.my_seq, self.peer_seq, self.session, csm)
        self.s.sendall(frame)
        mid = parse_csm(csm)
        label = f"0x{mid[0]:04x}" if mid else "?"
        print(f"TX CSM {label} seq={self.my_seq} {len(csm)}B", flush=True)

    def send_bare_ack(self) -> None:
        # Does not consume a sequence number (matches carplayd send_bare_ack).
        seq = (self.my_seq - 1) & 0xFF if self.my_seq else 0
        frame = encode_link(CTL_ACK, seq, self.peer_seq, 0, b"")
        self.s.sendall(frame)

    def feed(self, data: bytes) -> None:
        self.buf.extend(data)
        while True:
            if len(self.buf) < 2:
                return
            # detect marker (phone wired path usually skips; tolerate if present)
            if self.buf[0] == 0xFF and self.buf[1] == 0x55:
                if len(self.buf) < 6:
                    return
                print("RX detect", flush=True)
                del self.buf[:6]
                continue
            if self.buf[0] != 0xFF or self.buf[1] != 0x5A:
                del self.buf[0]
                continue
            if len(self.buf) < 9:
                return
            # checksumOk: sum of header incl. checksum byte == 0 (mod 256)
            if (sum(self.buf[:9]) & 0xFF) != 0:
                del self.buf[0]
                continue
            total = (self.buf[2] << 8) | self.buf[3]
            if total < 9 or total > 4106:
                del self.buf[0]
                continue
            if len(self.buf) < total:
                return
            pkt = bytes(self.buf[:total])
            del self.buf[:total]
            control = pkt[4]
            seq = pkt[5]
            _ack = pkt[6]
            session = pkt[7]
            payload = b""
            if total > 9:
                if (sum(pkt[9:total]) & 0xFF) != 0:
                    continue
                payload = pkt[9 : total - 1]
            self.peer_seq = seq
            if session:
                self.session = session
            if control & CTL_SYN:
                print(f"RX SYN sess={session} pay={len(payload)}B — ACK", flush=True)
                self.send_bare_ack()
                continue
            if not payload:
                continue
            self.send_bare_ack()
            parsed = parse_csm(payload)
            if not parsed:
                print(f"RX non-CSM pay={len(payload)}B", flush=True)
                continue
            mid, rest = parsed
            print(f"RX CSM 0x{mid:04x} {len(payload)}B", flush=True)
            self._on_csm(mid, rest, payload)

    def _on_csm(self, mid: int, rest: bytes, raw: bytes) -> None:
        if mid == MSG_START_IDENT and not self.saw_1d00:
            self.saw_1d00 = True
            self.send_csm(build_ident_info(self.cp_iface))
        elif mid == MSG_IDENT_ACCEPTED:
            self.saw_1d02 = True
            print("identification accepted", flush=True)
        elif mid == MSG_REQ_AUTH_CERT and not self.saw_aa00:
            self.saw_aa00 = True
            self.send_csm(build_auth_cert())
        elif mid == MSG_REQ_AUTH_CHALLENGE:
            self.saw_aa02 = True
            # challenge is param 0
            challenge = b""
            pos = 0
            while pos + 4 <= len(rest):
                size = (rest[pos] << 8) | rest[pos + 1]
                pid = (rest[pos + 2] << 8) | rest[pos + 3]
                if size < 4 or pos + size > len(rest):
                    break
                if pid == 0:
                    challenge = rest[pos + 4 : pos + size]
                    break
                pos += size
            self.pending_challenge = challenge
            self.send_csm(build_auth_response(challenge))
        elif mid == MSG_AUTH_SUCCEEDED:
            self.saw_aa05 = True
            print("auth succeeded (phone)", flush=True)
        elif mid == MSG_CARPLAY_AVAIL and not self.sent_4301:
            self.saw_4300 = True
            self.send_csm(build_cp_start(self.ip, self.port))
            self.sent_4301 = True
            print(f"CarPlayStartSession → {self.ip}:{self.port}", flush=True)
        else:
            print(f"  (no action for 0x{mid:04x})", flush=True)


def guess_ncm_ip() -> str:
    """Best-effort Mac IPv4 on a USB/NCM-looking iface (en1x / bridge)."""
    try:
        import subprocess

        out = subprocess.check_output(["ifconfig"], text=True, errors="replace")
    except Exception:
        return "169.254.1.1"
    cur_if = None
    candidates: list[tuple[str, str]] = []
    for line in out.splitlines():
        if line and not line[0].isspace() and ":" in line:
            cur_if = line.split(":", 1)[0]
        if "inet " in line and cur_if:
            parts = line.split()
            try:
                ip = parts[parts.index("inet") + 1]
            except (ValueError, IndexError):
                continue
            if ip.startswith("127.") or ip.startswith("169.254.169."):
                continue
            name = cur_if.lower()
            if name.startswith("en") or "bridge" in name or "usb" in name:
                if ip.startswith("169.254.") or ip.startswith("10.") or ip.startswith("172."):
                    candidates.append((cur_if, ip))
    for iface, ip in candidates:
        if ip.startswith("169.254."):
            print(f"NCM guess {iface} {ip}", flush=True)
            return ip
    if candidates:
        print(f"NCM guess {candidates[0][0]} {candidates[0][1]}", flush=True)
        return candidates[0][1]
    return "169.254.1.1"


def open_carkit(did: int, host_id: str) -> Usbmux:
    mux = Usbmux()
    mux.connect(did, LOCKDOWN_PORT)
    ld = Lockdown(mux.s)
    r = ld.request({"Label": "aad", "Request": "QueryType"})
    print(f"QueryType -> {r.get('Type')}", flush=True)
    r = ld.request({"Label": "aad", "Request": "StartSession", "HostID": host_id})
    print(
        f"StartSession HostID={host_id} -> SessionID={r.get('SessionID')} "
        f"SSL={r.get('EnableSessionSSL')} err={r.get('Error')}",
        flush=True,
    )
    if r.get("Error") and r.get("Error") != "InvalidHostID":
        # InvalidHostID still allows StartService on our daemon; real HostID preferred.
        print(f"StartSession warning: {r.get('Error')}", flush=True)
    if r.get("EnableSessionSSL"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        mux.s = ctx.wrap_socket(mux.s, server_hostname="Device")
        ld = Lockdown(mux.s)
        print("lockdown TLS wrapped", flush=True)
    r = ld.request({"Label": "aad", "Request": "StartService", "Service": CARKIT})
    print(f"StartService -> {r}", flush=True)
    if r.get("Error"):
        raise RuntimeError(f"StartService error {r.get('Error')}")
    port = int(r.get("Port") or 0)
    if port <= 0:
        raise RuntimeError("no Port in StartService")
    # Keep lockdown SSL session alive while dialing carkit (real HU style).
    # Closing it first used to RST the mux TCP right before the carkit SYN.
    time.sleep(0.15)
    mux2 = Usbmux()
    mux2.connect(did, port)
    print(f"Connect carkit OK mux_port={port}", flush=True)
    mux2.s.settimeout(1.0)
    # Stash lockdown so caller can close both; attach as attribute.
    mux2._lockdown_mux = mux  # type: ignore[attr-defined]
    return mux2


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ip", default="", help="AirPlay IPv4 advertised in 0x4301 (default: guess NCM)")
    ap.add_argument("--port", type=int, default=7000, help="AirPlay TCP port in 0x4301")
    ap.add_argument("--cp-iface", type=int, default=2, help="USBHost CarPlay interface number")
    ap.add_argument("--hold", type=float, default=90.0, help="seconds to keep carkit open")
    ap.add_argument(
        "--host-id",
        default=os.environ.get("AAD_HOST_ID", "DAAA6777-D8CB-4ADB-8A16-E72D25C2626B"),
        help="lockdown HostID (default: latest Flip4 pair / $AAD_HOST_ID)",
    )
    args = ap.parse_args()
    airplay_ip = args.ip or guess_ncm_ip()

    deadline = time.time() + 25
    devices: list[dict] = []
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

    dev = devices[0]
    props = dev.get("Properties") or {}
    did = int(dev.get("DeviceID") or props.get("DeviceID") or 0)
    udid = props.get("SerialNumber") or props.get("UDID") or "?"
    print(f"device id={did} udid={udid}", flush=True)
    if did <= 0:
        return 2

    try:
        mux = open_carkit(did, args.host_id)
    except Exception as e:
        print(f"FAIL: open carkit: {e}", flush=True)
        return 3

    hu = Iap2Hu(mux.s, airplay_ip, args.port, args.cp_iface)
    print(
        f"HU iAP2 listening hold={args.hold}s start={airplay_ip}:{args.port} "
        f"(App should be cp-usb + waiting 0x1D00 reply)",
        flush=True,
    )
    t0 = time.time()
    try:
        while time.time() - t0 < args.hold:
            try:
                chunk = mux.s.recv(8192)
            except socket.timeout:
                continue
            except OSError as e:
                print(f"carkit read: {e}", flush=True)
                break
            if not chunk:
                print("carkit EOF", flush=True)
                break
            hu.feed(chunk)
            if hu.sent_4301 and hu.saw_aa05:
                # Keep link up a bit so app can dial; then report PASS.
                if time.time() - t0 > 5:
                    pass
    finally:
        mux.close()
        ld_mux = getattr(mux, "_lockdown_mux", None)
        if ld_mux is not None:
            try:
                ld_mux.close()
            except OSError:
                pass

    ok = hu.saw_1d00 and hu.saw_1d02 and hu.saw_aa05 and hu.sent_4301
    print(
        f"summary 1d00={hu.saw_1d00} 1d02={hu.saw_1d02} aa00={hu.saw_aa00} "
        f"aa02={hu.saw_aa02} aa05={hu.saw_aa05} 4300={hu.saw_4300} 4301={hu.sent_4301}",
        flush=True,
    )
    if ok:
        print("PASS: iAP2 HU handshake through 0x4301", flush=True)
        return 0
    if hu.saw_1d00 and not hu.saw_1d02:
        print("PARTIAL: sent 0x1D01; phone did not Accept — check App/cp-usb", flush=True)
        return 5
    if not hu.saw_1d00:
        print("FAIL: no 0x1D00 from phone (is App armed on cp-usb?)", flush=True)
        return 6
    print("FAIL: handshake incomplete", flush=True)
    return 7


if __name__ == "__main__":
    sys.exit(main())
