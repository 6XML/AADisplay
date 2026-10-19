#!/usr/bin/env python3
"""NCM L3 TCP smoke: Mac ↔ phone link-local over the USB Ethernet iface.

Phone side (root, while armed):
  toybox nc -6 -l -p 19227 -s <phone_ll>

Host:
  python3 host_ncm_tcp_smoke.py --iface en12 --peer fe80::… --port 19227
"""
from __future__ import annotations

import argparse
import socket
import sys
import time


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--iface", default="en12")
    ap.add_argument("--peer", required=True, help="peer fe80:: address without scope")
    ap.add_argument("--port", type=int, default=19227)
    ap.add_argument("--timeout", type=float, default=5.0)
    args = ap.parse_args()

    peer = args.peer.split("%")[0]
    # scoped link-local: (host, port, flowinfo, scopeid)
    ifn = socket.if_nametoindex(args.iface)
    addr = (peer, args.port, 0, ifn)
    payload = b"AAD_NCM_TCP_SMOKE\n"
    deadline = time.time() + args.timeout
    last_err: Exception | None = None
    while time.time() < deadline:
        s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
        s.settimeout(1.5)
        try:
            s.connect(addr)
            s.sendall(payload)
            try:
                reply = s.recv(64)
            except socket.timeout:
                reply = b""
            print(f"PASS: connected {peer}%{args.iface}:{args.port} reply={reply!r}", flush=True)
            s.close()
            return 0
        except Exception as e:
            last_err = e
            s.close()
            time.sleep(0.3)
    print(f"FAIL: {last_err}", flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
