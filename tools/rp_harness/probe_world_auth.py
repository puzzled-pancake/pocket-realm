"""Minimal Python replica of the world-auth probe (wire ground truth)."""
import hashlib
import socket
import sqlite3
import struct
import sys
import time
import zlib
from pathlib import Path

REPO = Path("C:/pocket_realm_complete")
sys.path.insert(0, str(REPO / "tools"))
from rp_harness.desktop_host import DesktopHost  # noqa: E402

DB = Path(__import__("os").environ["LOCALAPPDATA"]) / "PocketRealm" / "database" / "sqlite-datadir" / "classicrealmd.sqlite3"

SMSG_AUTH_CHALLENGE = 0x1EC
CMSG_AUTH_SESSION = 0x1ED
SMSG_AUTH_RESPONSE = 0x1EE
CMSG_CHAR_ENUM = 0x037
SMSG_CHAR_ENUM = 0x03B
CMSG_PLAYER_LOGIN = 0x03D
CMSG_MESSAGECHAT = 0x095
SMSG_MESSAGECHAT = 0x096
CMSG_PING = 0x1DC

# one clean addon record: "RPTest", flags 0, standard Blizzard modulus CRC
UNCOMPRESSED = b"RPTest\x00" + bytes([0]) + struct.pack("<I", 0x4C1C776D) + struct.pack("<I", 0)
ADDON = struct.pack("<I", len(UNCOMPRESSED)) + zlib.compress(UNCOMPRESSED)

send_i = send_j = 0
recv_i = recv_j = 0
KEY = None


def crypt_send(data):
    global send_i, send_j
    out = bytearray(data)
    for t in range(len(out)):
        send_i %= len(KEY)
        x = ((out[t] ^ KEY[send_i]) + send_j) & 0xFF
        send_i += 1
        send_j = x
        out[t] = x
    return bytes(out)


def crypt_recv(data):
    global recv_i, recv_j
    out = bytearray(data)
    for t in range(len(out)):
        recv_i %= len(KEY)
        c = out[t]
        x = ((c - recv_j) & 0xFF) ^ KEY[recv_i]
        recv_i += 1
        recv_j = c
        out[t] = x
    return bytes(out)


def read_exactly(sock, n, timeout=60):
    sock.settimeout(timeout)
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise OSError("closed")
        data += chunk
    return data


def recv_packet(sock, timeout=60, encrypted=True):
    header = bytearray(read_exactly(sock, 4, timeout))
    if encrypted and KEY:
        header = bytearray(crypt_recv(header))
    size = (header[0] << 8) | header[1]
    cmd = header[2] | (header[3] << 8)
    payload = read_exactly(sock, size - 2, timeout)
    return cmd, payload


def send_packet(sock, cmd, body, encrypted=True):
    header = bytearray(struct.pack(">H", len(body) + 4) + struct.pack("<I", cmd))
    if encrypted and KEY:
        header = bytearray(crypt_send(bytes(header)))
    sock.sendall(bytes(header) + body)


def printable_runs(payload):
    runs, cur = [], []
    for b in payload:
        if 0x20 <= b <= 0x7E:
            cur.append(chr(b))
        else:
            if len(cur) >= 4:
                runs.append("".join(cur))
            cur = []
    if len(cur) >= 4:
        runs.append("".join(cur))
    return " | ".join(runs)


def main():
    global KEY
    host = DesktopHost(REPO, bots=25,
                       stderr_path=REPO / "build" / "rp-desktop" / "probe2-stderr.log")
    try:
        print("booting...", flush=True)
        print("ready:", host.wait_ready(timeout_s=900), flush=True)
        login = host.send_raw({"op": "proto-login", "user": "rptest", "pass": "rptestpass"})
        print("login:", login, flush=True)

        import os
        db = sqlite3.connect(str(DB))
        key_hex = db.execute("SELECT sessionkey FROM account WHERE username='RPTEST'").fetchone()[0]
        print("sessionkey:", key_hex[:16], "...", flush=True)
        key = bytes.fromhex(key_hex)[::-1]  # minimal little-endian
        key = key + b"\x00" * (40 - len(key))
        KEY = key

        sock = socket.create_connection(("127.0.0.1", 8085), timeout=600)
        cmd, payload = recv_packet(sock, encrypted=False)
        print(f"challenge: cmd=0x{cmd:04X} seed=0x{struct.unpack('<I', payload[:4])[0]:08X}", flush=True)
        server_seed = struct.unpack("<I", payload[:4])[0]
        client_seed = 0x12345678

        sha = hashlib.sha1()
        sha.update(b"RPTEST")
        sha.update(struct.pack("<I", 0))
        sha.update(struct.pack("<I", client_seed))
        sha.update(struct.pack("<I", server_seed))
        sha.update(key)
        digest = sha.digest()

        body = struct.pack("<II", 5875, 0) + b"RPTEST\x00" + \
            struct.pack("<I", client_seed) + digest + ADDON
        send_packet(sock, CMSG_AUTH_SESSION, body, encrypted=False)
        KEY = key  # arm the crypt only AFTER the handshake packet is raw
        print("auth session sent; crypt armed; reading...", flush=True)

        got_auth = False
        deadline = time.time() + 120
        while time.time() < deadline:
            try:
                cmd, payload = recv_packet(sock, timeout=30)
            except socket.timeout:
                print("  (30s idle)", flush=True)
                continue
            print(f"packet: cmd=0x{cmd:04X} len={len(payload)} first={payload[:1].hex()}", flush=True)
            if cmd == SMSG_AUTH_RESPONSE:
                got_auth = True
                print(f"AUTH_RESPONSE result=0x{payload[0]:02X}", flush=True)
                break
        if not got_auth:
            print("NO AUTH_RESPONSE in 120s", flush=True)
            return

        send_packet(sock, CMSG_CHAR_ENUM, b"")
        cmd, payload = recv_packet(sock, timeout=90)
        print(f"char enum: cmd=0x{cmd:04X} len={len(payload)} count={payload[0]}", flush=True)
        if payload[0] == 0:
            create_body = b"Davos\x00" + bytes([1, 1, 0, 0, 0, 0, 0, 0, 0, 0])
            send_packet(sock, 0x036, create_body)
            cmd, payload = recv_packet(sock, timeout=90)
            print(f"char create: cmd=0x{cmd:04X} result=0x{payload[0]:02X}", flush=True)
            send_packet(sock, CMSG_CHAR_ENUM, b"")
            cmd, payload = recv_packet(sock, timeout=90)
            print(f"char enum2: count={payload[0]}", flush=True)
        guid = struct.unpack("<Q", payload[1:9])[0]
        name_end = payload.index(0, 9)
        name = payload[9:name_end].decode()
        print(f"char: guid={guid} name={name}", flush=True)

        send_packet(sock, CMSG_PLAYER_LOGIN, struct.pack("<Q", guid))
        print("player login sent; draining 15s...", flush=True)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                cmd, payload = recv_packet(sock, timeout=5)
            except socket.timeout:
                continue
            if cmd == SMSG_MESSAGECHAT:
                print(f"CHAT: {printable_runs(payload)[:150]}", flush=True)

        summary = host.memory("")
        bots = sorted(summary.get("onlineBots") or [])
        print(f"bots online: {len(bots)}: {bots[:6]}", flush=True)
        if len(bots) >= 2:
            target = bots[1]
            host.reset("")
            text = f"Well met {target}, I am new to these lands. How goes your watch?"
            print(f"whispering {target}: {text}", flush=True)
            send_packet(sock, CMSG_MESSAGECHAT,
                        struct.pack("<II", 6, 0) + target.encode() + b"\x00" + text.encode() + b"\x00")
            deadline = time.time() + 150
            while time.time() < deadline:
                try:
                    cmd, payload = recv_packet(sock, timeout=10)
                except socket.timeout:
                    continue
                if cmd == SMSG_MESSAGECHAT:
                    line = printable_runs(payload)
                    print(f"CHAT[0x{cmd:04X}]: {line[:200]}", flush=True)
                    if target in line and len(line.split()) >= 5:
                        print("REPLY CAPTURED", flush=True)
                        break
            mem = host.memory(target)
            print("target memory:", str(mem)[:400], flush=True)
        print("done", flush=True)
    finally:
        host.close()


if __name__ == "__main__":
    main()
