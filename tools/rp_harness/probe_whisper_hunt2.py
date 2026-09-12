"""Whisper hunt v2: GM-teleport into the bot hub, round-robin whispers."""
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

UNCOMPRESSED = b"RPTest\x00" + bytes([0]) + struct.pack("<I", 0x4C1C776D) + struct.pack("<I", 0)
ADDON = struct.pack("<I", len(UNCOMPRESSED)) + zlib.compress(UNCOMPRESSED)

send_i = send_j = recv_i = recv_j = 0
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


def drain_for_reply(sock, seconds, target, my_text):
    """Collect non-echo chat lines; stop at a whisper from the target."""
    lines = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            cmd, payload = recv_packet(sock, timeout=5)
        except socket.timeout:
            continue
        if cmd == SMSG_MESSAGECHAT:
            msgtype = payload[0] if payload else -1
            line = printable_runs(payload)
            if my_text and my_text in line:
                continue  # the inform echo of our own whisper
            if msgtype == 6 and target in line:
                return line
            if line:
                lines.append((msgtype, line))
    return lines or []


def main():
    global KEY
    host = DesktopHost(REPO, bots=25,
                       stderr_path=REPO / "build" / "rp-desktop" / "hunt2-stderr.log")
    try:
        print("booting...", flush=True)
        host.wait_ready(timeout_s=900)
        host.send_raw({"op": "proto-login", "user": "rptest", "pass": "rptestpass"})
        host.send_raw({"op": "proto-world", "user": "rptest"})
        chars = host.send_raw({"op": "proto-chars"}).get("chars") or []
        davos = next(c for c in chars if c["name"] == "Davos")
        host.send_raw({"op": "proto-pick", "guid": davos["guid"]})
        print("Davos in world", flush=True)

        import os
        db = sqlite3.connect(str(DB))
        key_hex = db.execute("SELECT sessionkey FROM account WHERE username='RPTEST'").fetchone()[0]
        key = bytes.fromhex(key_hex)[::-1]
        KEY = key + b"\x00" * (40 - len(key))

        sock = socket.create_connection(("127.0.0.1", 8085), timeout=600)
        cmd, payload = recv_packet(sock, encrypted=False)
        server_seed = struct.unpack("<I", payload[:4])[0]
        client_seed = 0x12345678
        sha = hashlib.sha1()
        sha.update(b"RPTEST")
        sha.update(struct.pack("<I", 0))
        sha.update(struct.pack("<I", client_seed))
        sha.update(struct.pack("<I", server_seed))
        sha.update(KEY)
        body = struct.pack("<II", 5875, 0) + b"RPTEST\x00" + \
            struct.pack("<I", client_seed) + sha.digest() + ADDON
        send_packet(sock, CMSG_AUTH_SESSION, body, encrypted=False)
        # drain until AUTH_RESPONSE (0x1EE)
        while True:
            cmd, payload = recv_packet(sock, timeout=120)
            if cmd == SMSG_AUTH_RESPONSE:
                print(f"auth result=0x{payload[0]:02X}", flush=True)
                break

        send_packet(sock, CMSG_CHAR_ENUM, b"")
        cmd, payload = recv_packet(sock, timeout=90)
        guid = struct.unpack("<Q", payload[1:9])[0]
        send_packet(sock, CMSG_PLAYER_LOGIN, struct.pack("<Q", guid))
        drain_for_reply(sock, 20, "nobody", "")
        print("logged in", flush=True)

        summary = host.memory("")
        bots = sorted(summary.get("onlineBots") or [])
        host.reset("")
        print(f"bots({len(bots)})", flush=True)

        # GM teleport into the bot hub
        send_packet(sock, CMSG_MESSAGECHAT,
                    struct.pack("<II", 0, 0) + b".tele stormwind\x00")
        print("teleported (say .tele stormwind); settling 15s", flush=True)
        time.sleep(15)
        drain_for_reply(sock, 10, "nobody", "")

        for target in bots[1:7]:
            # GM-appear at the bot's side: proximity makes the bot active
            # (ForceActiveWhenNearPlayer) so the conversational lane opens
            send_packet(sock, CMSG_MESSAGECHAT,
                        struct.pack("<II", 0, 0) + f".appear {target}".encode() + b"\x00")
            print(f".appear {target}; settling 8s", flush=True)
            time.sleep(8)
            drain_for_reply(sock, 5, "nobody", "")
            text = f"Well met {target}, I am new to these lands. What news do you carry?"
            print(f"whisper -> {target}", flush=True)
            send_packet(sock, CMSG_MESSAGECHAT,
                        struct.pack("<II", 6, 0) + target.encode() + b"\x00" + text.encode() + b"\x00")
            result = drain_for_reply(sock, 45, target, text)
            if isinstance(result, str):
                print(f"REPLY from {target}: {result}", flush=True)
                break
            # also try a named SAY nearby
            say_text = f"{target}, do you know a smith who could appraise a blade?"
            print(f"say -> {target}", flush=True)
            send_packet(sock, CMSG_MESSAGECHAT,
                        struct.pack("<II", 0, 0) + say_text.encode() + b"\x00")
            result = drain_for_reply(sock, 45, target, say_text)
            if isinstance(result, str):
                print(f"REPLY(say) from {target}: {result}", flush=True)
                break
            print(f"  {target}: nothing", flush=True)
        print("done", flush=True)
    finally:
        host.close()


if __name__ == "__main__":
    main()
