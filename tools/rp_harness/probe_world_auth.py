"""The W4.2 3x3 wire-level auth matrix instrument (desktop rp_harness).

For one SERVER flavor lane (a booted rpHost) this probe drives every
REGISTERED client build through the two wire gates and fails loud on any
drift:

  - REALMD logon (SRP6, port 3724) through rpHost's `proto-logon` op
    (VsrpClient, the Kotlin wire client): the MATCHING build must
    authenticate and the measured proof-response frame must equal the
    flavor's schemas/flavor.json `auth_frame.proof_response_bytes` pin
    (26 / 32 / 32 - drift is a loud failure, never a warning); every
    MISMATCHED build must be REFUSED at the challenge
    (AUTH_LOGON_FAILED_VERSION_INVALID, 0x09).
  - WORLD auth (port 8085, raw sockets): the challenge opcode must be
    the ONE shared 0x1EC (any other value is a loud failure - the C3
    contract); the MATCHING build must reach AUTH_OK (0x0C) and a live
    char enum; every MISMATCHED build must get AUTH_VERSION_MISMATCH
    (0x14) from the world's own IsAcceptableClientBuild gate.

Usage (one server lane, all three client builds):
    python tools/rp_harness/probe_world_auth.py --flavor tbc
Full 3x3 (three boots):
    python tools/rp_harness/probe_world_auth.py --matrix
"""
import argparse
import hmac
import json
import hashlib
import socket
import contextlib
import struct
import sys
import time
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import common  # noqa: E402
from rp_harness.desktop_host import DesktopHost  # noqa: E402

MANIFEST = REPO / "schemas" / "flavor.json"

# Correction C3: ONE shared auth-challenge opcode in all three branches.
SMSG_AUTH_CHALLENGE = 0x1EC
CMSG_AUTH_SESSION = 0x1ED
SMSG_AUTH_RESPONSE = 0x1EE
CMSG_CHAR_ENUM = 0x037
SMSG_CHAR_ENUM = 0x03B

AUTH_OK = 0x0C
AUTH_VERSION_MISMATCH = 0x14
LOGON_FAILED_VERSION_INVALID = 0x09

# one clean addon record: "RPTest", flags 0, standard Blizzard modulus CRC
UNCOMPRESSED = b"RPTest\x00" + bytes([0]) + struct.pack("<I", 0x4C1C776D) + struct.pack("<I", 0)
ADDON = struct.pack("<I", len(UNCOMPRESSED)) + zlib.compress(UNCOMPRESSED)

RPTEST_USER = "RPTEST"
RPTEST_PASS = "rptestpass"


SETTINGS_SELECTION_KEY = "selectedFlavorId"


@contextlib.contextmanager
def selected_flavor(flavor_id):
    """The persisted Home-switcher contract, driven by the harness: write
    `selectedFlavorId` into the product settings (exactly the field the
    flavor switcher's updateSettings persists), boot that lane, restore
    the original file afterwards. The boot-lane resolver reads this key -
    an absent selection under several qualified flavors refuses loud by
    design (ActiveFlavorState.resolve), so the matrix pins each lane the
    way the UI does."""
    settings = common.product_data_root() / "settings" / "settings.json"
    original = settings.read_bytes()
    data = json.loads(original.decode("utf-8"))
    data[SETTINGS_SELECTION_KEY] = flavor_id
    settings.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    try:
        yield
    finally:
        try:
            settings.write_bytes(original)
        except OSError as error:
            print(f"SETTINGS-RESTORE-FAILED: {error}", file=sys.stderr)


def manifest_pins():
    """The per-flavor wire pins from schemas/flavor.json (the source of
    record the matrix trusts): build, realmd db name, proof-response
    frame bytes, and the shared challenge opcode."""
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    opcode = data.get("auth", {}).get("smsg_auth_challenge_opcode")
    if opcode != "0x1EC":
        raise SystemExit(
            f"AUTH-MATRIX-DRIFT: auth.smsg_auth_challenge_opcode is {opcode!r}, "
            "the one shared constant is 0x1EC (correction C3)")
    pins = {}
    for flavor in data["flavors"]:
        frame = (flavor.get("auth_frame") or {}).get("proof_response_bytes")
        if not isinstance(frame, int) or frame <= 0:
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT: flavor {flavor['id']} has no verified "
                "auth_frame.proof_response_bytes pin")
        pins[flavor["id"]] = {
            "build": int(flavor["build"]),
            "realmd_db": flavor["databases"]["realmd"],
            "frame_bytes": frame,
        }
    if not pins:
        # R2-5-1 (W4 code-red round 5): a manifest with NO registered
        # flavors must fail loud here - the old shape let main() certify a
        # 0/0-cells matrix run as green (exit 0), the fail-open boundary.
        raise SystemExit(
            "AUTH-MATRIX-DRIFT: the manifest registers no flavors - "
            "the 3x3 matrix certifies nothing from an empty registry")
    return pins


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


class LegacyCrypt:
    """The vanilla/tbc world header cipher (AuthCrypt's old i/j style):
    vanilla keys it with the raw session key; tbc keys it with
    HMAC-SHA1(tbc_seed, K) — 4 header bytes server->client, 6 client->server
    (CRYPTED_SEND_LEN/CRYPTED_RECV_LEN)."""

    def __init__(self, key):
        self.key = key
        self.si = self.sj = self.ri = self.rj = 0

    def encrypt_send(self, data):
        out = bytearray(data)
        for t in range(len(out)):
            self.si %= len(self.key)
            x = ((out[t] ^ self.key[self.si]) + self.sj) & 0xFF
            self.si += 1
            self.sj = x
            out[t] = x
        return bytes(out)

    def decrypt_recv(self, data):
        out = bytearray(data)
        for t in range(len(out)):
            self.ri %= len(self.key)
            c = out[t]
            x = ((c - self.rj) & 0xFF) ^ self.key[self.ri]
            self.ri += 1
            self.rj = c
            out[t] = x
        return bytes(out)


class Rc4:
    """The wotlk world header cipher (AuthCrypt's SARC4): RC4 keyed with
    HMAC-SHA1(per-direction seed, K), 1024 zero bytes dropped before use."""

    def __init__(self, key):
        s = list(range(256))
        j = 0
        for i in range(256):
            j = (j + s[i] + key[i % len(key)]) & 0xFF
            s[i], s[j] = s[j], s[i]
        self.s = s
        self.i = 0
        self.j = 0
        self.apply(bytes(1024))

    def apply(self, data):
        s, i, j = self.s, self.i, self.j
        out = bytearray(data)
        for t in range(len(out)):
            i = (i + 1) & 0xFF
            j = (j + s[i]) & 0xFF
            s[i], s[j] = s[j], s[i]
            out[t] ^= s[(s[i] + s[j]) & 0xFF]
        self.i, self.j = i, j
        return bytes(out)

    # the probe's server-side call name (RC4 is symmetric XOR)
    decrypt_recv = apply
    encrypt_send = apply


# The ACTIVE server branch's crypt (set per world cell; None = the legacy
# globals path the vanilla lane keeps). R2-r3-3 (round 3): the tbc/wotlk
# servers initialize their header crypt DURING HandleAuthSession — the
# AUTH_OK / post-auth responses are ENCRYPTED, so a plaintext read never
# sees them (observed live: the tbc world logged "authenticated
# successfully" while the probe sat in a 60s recv timeout).
SERVER_CRYPT = None
CLIENT_CRYPT = None

# the per-branch crypt seeds (the branches' own AuthCrypt.cpp constants)
TBC_CRYPT_SEED = bytes.fromhex("38A78315F8922530719867B18C04E2AA")
WOTLK_SERVER_ENCRYPT_SEED = bytes.fromhex("CC98AE04E897EACA12DDC09342915357")
WOTLK_SERVER_DECRYPT_SEED = bytes.fromhex("C2B3723CC6AED9B5343C53EE2F4367CE")


def derive_world_crypt(server_flavor, key_hex):
    """The server branch's header crypt from the session key: None for
    vanilla (the legacy globals keyed with K stay), a LegacyCrypt for tbc,
    Rc4 pair for wotlk."""
    global SERVER_CRYPT, CLIENT_CRYPT
    SERVER_CRYPT = CLIENT_CRYPT = None
    if not key_hex:
        return
    key_be = bytes.fromhex(key_hex.zfill(80))[::-1]  # big-endian K
    if server_flavor == "tbc":
        tbc_key = hmac.new(TBC_CRYPT_SEED, key_be, hashlib.sha1).digest()
        SERVER_CRYPT = CLIENT_CRYPT = LegacyCrypt(tbc_key)
    elif server_flavor == "wotlk":
        SERVER_CRYPT = Rc4(hmac.new(
            WOTLK_SERVER_ENCRYPT_SEED, key_be, hashlib.sha1).digest())
        CLIENT_CRYPT = Rc4(hmac.new(
            WOTLK_SERVER_DECRYPT_SEED, key_be, hashlib.sha1).digest())


def read_exactly(sock, n, timeout=60):
    sock.settimeout(timeout)
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise OSError("closed")
        data += chunk
    return data


WORLD_AUTH_DEBUG = bool(__import__("os").environ.get("WORLD_AUTH_DEBUG"))


def recv_packet(sock, timeout=60, encrypted=True):
    header = bytearray(read_exactly(sock, 4, timeout))
    if WORLD_AUTH_DEBUG:
        print(f"    [debug] raw header: {bytes(header).hex()}", flush=True)
    if encrypted and SERVER_CRYPT is not None:
        header = bytearray(SERVER_CRYPT.decrypt_recv(bytes(header)))
    elif encrypted and KEY:
        header = bytearray(crypt_recv(header))
    if WORLD_AUTH_DEBUG:
        print(f"    [debug] decrypted header: {bytes(header).hex()}", flush=True)
    size = (header[0] << 8) | header[1]
    cmd = header[2] | (header[3] << 8)
    payload = read_exactly(sock, size - 2, timeout)
    return cmd, payload


def send_packet(sock, cmd, body, encrypted=True):
    header = bytearray(struct.pack(">H", len(body) + 4) + struct.pack("<I", cmd))
    if encrypted and CLIENT_CRYPT is not None:
        header = bytearray(CLIENT_CRYPT.encrypt_send(bytes(header)))
    elif encrypted and KEY:
        header = bytearray(crypt_send(bytes(header)))
    sock.sendall(bytes(header) + body)


def addon_block_for(client_flavor: str) -> bytes:
    """The tbc/wotlk Anticheat ReadAddonInfo block: u32 uncompressedSize +
    zlib(the packed addon structure) — the parser reads exactly ONE size
    field and inflates from rpos to the packet end. The DECOMPRESSED
    record form is per client branch (the branches' own parsers):
      tbc: a while(rpos<size) loop of (zstr name, u32 crc, u32 unk7,
           u8 unk6) records — the same raw record shape the vanilla lane
           sends, minus the leading count;
      wotlk: u32 addonCount, then (u8-len string name, u8 enabled,
           u32 crc, u32 unk7) — a vanilla-form record here throws
           ByteBufferException AFTER the session authenticated (observed
           live: the world logged 'authenticated successfully', then died
           on the addon parse and never answered).
    The crc is the branches' standard-Blizzard-addon constant
    (0x1C776D01) for wotlk so the echo skips the 256-byte blob."""
    if client_flavor == "wotlk":
        # the wotlk ByteBuffer reads std::string as u16-length prefixed
        # (observed live: a u8-length record threw ByteBufferException
        # with a u32 read landing exactly 1 byte into the trailing u32)
        record = (struct.pack("<I", 1) + struct.pack("<H", len(b"RPTest")) +
                  b"RPTest" + bytes([0]) + struct.pack("<I", 0x1C776D01) +
                  struct.pack("<I", 0))
    else:
        record = UNCOMPRESSED
    return struct.pack("<I", len(record)) + zlib.compress(record)


def world_cell(label, client_flavor, build, key_hex, expect_ok,
               server_flavor="vanilla"):
    """One raw world-auth cell; returns the verdict dict. Fails loud when
    the challenge opcode drifts from 0x1EC or the observed verdict
    contradicts the expectation. server_flavor selects the SERVER branch's
    crypt + challenge layout (the header ciphers are per-branch)."""
    global KEY, send_i, send_j, recv_i, recv_j
    send_i = send_j = recv_i = recv_j = 0
    if key_hex:
        # BigInteger.toString(16): big-endian digits, possibly with the
        # leading zeros dropped - zfill restores the fixed 40-byte width
        # before the little-endian reversal.
        key = bytes.fromhex(key_hex.zfill(80))[::-1]
    else:
        # A mismatched cell that ran before the lane's matching logon:
        # the world's IsAcceptableClientBuild gate fires BEFORE any SRP
        # verification (WorldSocket.cpp), so the refusal verdict does not
        # depend on the key material - a zero key exercises exactly that
        # gate.
        key = bytes(40)
    KEY = key
    # the SERVER branch's header crypt derives from the same session key
    # (vanilla keeps the legacy globals; tbc/wotlk refuse to speak
    # plaintext after HandleAuthSession initializes their crypt)
    derive_world_crypt(server_flavor, key_hex)

    sock = socket.create_connection(("127.0.0.1", 8085), timeout=120)
    try:
        try:
            cmd, payload = recv_packet(sock, encrypted=False)
        except OSError as closed:
            # The world closed the connection at accept/handshake: on a
            # MISMATCHED cell that is a fail-closed refusal (no session);
            # on the MATCHING cell it is a loud failure.
            if not expect_ok:
                return {"cell": label, "build": build,
                        "world": "refused(connection-closed)",
                        "challenge": "not-sent(closed)"}
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING world connection "
                f"was closed before the challenge: {closed}")
        if cmd != SMSG_AUTH_CHALLENGE:
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT [{label}]: world challenge opcode "
                f"0x{cmd:04X} != the shared 0x1EC (correction C3)")
        # the wotlk challenge carries u32(1) + m_seed + 32B encryption
        # seeds (WorldSocket.cpp SendStartupPacket) — the seed rides at
        # offset 4 there; vanilla/tbc send the bare 4-byte seed
        server_seed = struct.unpack(
            "<I", payload[4:8] if len(payload) >= 8 else payload[:4])[0]
        client_seed = 0x12345678

        sha = hashlib.sha1()
        sha.update(b"RPTEST")
        sha.update(struct.pack("<I", 0))
        sha.update(struct.pack("<I", client_seed))
        sha.update(struct.pack("<I", server_seed))
        sha.update(key)
        digest = sha.digest()

        # The CMSG_AUTH_SESSION body is PER-BRANCH (the branches' own
        # WorldSocket::HandleAuthSession read orders); the layout keys off
        # the MANIFEST-derived client flavor, never a build literal:
        #   vanilla + tbc: build u32, unk2 u32, account zstr, clientSeed
        #              u32, digest(20), then the addon info;
        #   wotlk:     build u32, unk2 u32, account zstr, skip u32,
        #              clientSeed u32, skip u32 x3, skip u64, digest(20),
        #              then the addon info.
        # The tbc/wotlk cores route the addon info through the Anticheat
        # module's ReadAddonInfo: a u32 uncompressedSize + zlib stream -
        # the vanilla per-record raw form fails that parse and the session
        # is kicked before any AUTH_RESPONSE. The vanilla world reads the
        # raw per-addon records.
        addon_info = (ADDON if client_flavor == "vanilla"
                      else addon_block_for(client_flavor))
        if client_flavor == "wotlk":
            body = (struct.pack("<II", build, 0) + b"RPTEST\x00" +
                    struct.pack("<I", 0) +
                    struct.pack("<I", client_seed) +
                    struct.pack("<IIIQ", 0, 0, 0, 0) +
                    digest + addon_info)
        else:
            body = (struct.pack("<II", build, 0) + b"RPTEST\x00" +
                    struct.pack("<I", client_seed) + digest + addon_info)
        send_packet(sock, CMSG_AUTH_SESSION, body, encrypted=False)
        KEY = key  # arm the crypt only AFTER the handshake packet is raw

        deadline = time.time() + 60
        result = None
        nudged = False
        while time.time() < deadline:
            try:
                cmd, payload = recv_packet(
                    sock, timeout=10 if WORLD_AUTH_DEBUG else 30)
            except socket.timeout:
                if WORLD_AUTH_DEBUG and not nudged:
                    # the wotlk world authenticated but wrote nothing: nudge
                    # with an encrypted CMSG_PING to force any queued write
                    nudged = True
                    print("    [debug] nudging with CMSG_PING", flush=True)
                    try:
                        send_packet(sock, 0x1AD, struct.pack("<I", 17))
                    except OSError as ping_closed:
                        print(f"    [debug] ping write closed: {ping_closed}",
                              flush=True)
                continue
            except OSError as closed:
                if not expect_ok:
                    result = -1  # the world closed: fail-closed refusal
                    break
                raise SystemExit(
                    f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING world closed "
                    f"mid-auth: {closed}")
            if cmd == SMSG_AUTH_RESPONSE:
                result = payload[0]
                break
        if result is None:
            raise SystemExit(f"AUTH-MATRIX [{label}]: no AUTH_RESPONSE in 60s "
                             f"(nudged={nudged})")
        if result == -1:
            if expect_ok:
                raise SystemExit(
                    f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING world closed mid-auth")
            return {"cell": label, "build": build,
                    "world": "refused(connection-closed)",
                    "challenge": "0x1EC"}
        if result < 0:
            result = 0  # normalize the sentinel (mismatch path handled above)

        verdict = "ok" if result == AUTH_OK else (
            "version-mismatch" if result == AUTH_VERSION_MISMATCH
            else f"result-0x{result:02X}")
        if expect_ok and result != AUTH_OK:
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING pair must "
                f"authenticate (AUTH_OK 0x0C), got {verdict}")
        if not expect_ok and result != AUTH_VERSION_MISMATCH:
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT [{label}]: the MISMATCHED pair must be "
                f"refused (AUTH_VERSION_MISMATCH 0x14), got {verdict}")
        if expect_ok:
            send_packet(sock, CMSG_CHAR_ENUM, b"")
            # the post-auth login burst queues several packets before the
            # char enum (SMSG_CLIENT_CACHE_VERSION, tutorials...) — drain
            # until the enum or the deadline, never a single read
            enum_deadline = time.time() + 90
            while True:
                cmd, payload = recv_packet(
                    sock, timeout=30 if not WORLD_AUTH_DEBUG else 15)
                if cmd == SMSG_CHAR_ENUM:
                    break
                if time.time() > enum_deadline:
                    raise SystemExit(
                        f"AUTH-MATRIX-DRIFT [{label}]: post-auth char enum "
                        f"never arrived (last 0x{cmd:04X})")
        return {"cell": label, "build": build, "world": verdict,
                "challenge": "0x1EC"}
    finally:
        sock.close()


def run_lane(flavor_id, pins, host, results):
    """Boot one server flavor lane and drive all three client builds."""
    pin = pins[flavor_id]
    lane = {"serverFlavor": flavor_id, "pin": pin, "cells": []}
    print(f"== lane {flavor_id} (build {pin['build']}, frame "
          f"{pin['frame_bytes']}B) ==", flush=True)
    print("booting...", flush=True)
    # W4: a first boot on a new lane applies the whole per-flavor migration
    # ledger (the world dump included) BEFORE ready - a legitimate boot
    # phase; the wait covers it.
    print("ready:", host.wait_ready(timeout_s=3600), flush=True)
    # the LIVE host: reboots replace the local reference, and main()'s
    # finally must close the one that is actually running (R2-r3-2)
    results["liveHost"] = host
    account = host.send_raw({"op": "account", "user": RPTEST_USER,
                             "pass": RPTEST_PASS, "gm": 3})
    print("account:", account, flush=True)
    if not account.get("ok"):
        raise SystemExit(f"AUTH-MATRIX [{flavor_id}]: account op failed")

    def reboot_lane(reason):
        """R2-r3-2 (code-red round 3): the tbc/wotlk Anticheat module
        accepts addon information ONCE per world boot (AddonHandler.cpp:92
        fingerprint law) — every world cell after the first on a
        non-vanilla lane rides a FRESH world boot, or the cell would be
        kicked by the already-consumed fingerprint instead of proving its
        build gate. The rpHost JVM boots the world at startup, so a fresh
        world is a fresh rpHost. The RPTEST account persists in the lane's
        MariaDB family across the restart, and the matching logon's
        session key is re-read by the new world from the same account row
        — no re-account, no re-logon needed."""
        host.close()
        fresh = DesktopHost(
            REPO, bots=0, flavor=flavor_id,
            stderr_path=REPO / "build" / "rp-desktop" /
            f"auth-matrix-{flavor_id}-stderr.log",
        )
        print(f"rebooting lane ({reason})...", flush=True)
        print("ready:", fresh.wait_ready(timeout_s=3600), flush=True)
        results["liveHost"] = fresh
        return fresh

    lane_key = None
    # the lane's OWN cell runs first: every lane proves its matching pair
    # before the mismatched cells, and the matching logon's session key is
    # then available to the wrong-build world cells.
    ordered = sorted(pins.items(), key=lambda kv: 0 if kv[0] == flavor_id else 1)
    first_world_cell = True
    for client_flavor, client_pin in ordered:
        build = client_pin["build"]
        label = f"{client_flavor}-client@{flavor_id}-server"
        expect_ok = client_flavor == flavor_id
        if flavor_id != "vanilla" and not first_world_cell:
            host = reboot_lane(
                "anticheat accepts the addon fingerprint once per boot")
        first_world_cell = False

        # REALMD cell: the SRP6 logon through VsrpClient with the explicit
        # build; the frame-length pin is asserted on the matching cell.
        logon = host.send_raw({"op": "proto-logon", "user": RPTEST_USER,
                               "pass": RPTEST_PASS, "build": build})
        cell = {"cell": label, "build": build, "expect": "ok" if expect_ok else "refused"}
        if expect_ok:
            if not logon.get("ok"):
                raise SystemExit(
                    f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING logon was "
                    f"refused: {logon.get('reason')}")
            measured = logon.get("frameBytes")
            if measured != pin["frame_bytes"]:
                raise SystemExit(
                    f"AUTH-MATRIX-DRIFT [{label}]: proof-response frame "
                    f"measured {measured}B on the wire, the manifest pins "
                    f"{pin['frame_bytes']}B (auth_frame.proof_response_bytes)")
            cell["logon"] = "ok"
            cell["frameBytes"] = measured
            lane_key = logon.get("sessionKey")
            print(f"  {label}: logon OK, frame={measured}B", flush=True)
        else:
            # The MISMATCHED realmd leg is recorded, not pinned: SRP6 is
            # identity-level and build-agnostic, so a wrong-build challenge
            # against the SAME account may complete the logon while the
            # server serves the PRESENTED build's frame layout (observed on
            # the vanilla lane: a tbc challenge drew the 32-byte
            # tbc-form frame, which the 26-byte vanilla parse reports as a
            # frame-drift refusal). The AUTHENTICATION gate for a mismatched
            # pair is the WORLD's IsAcceptableClientBuild check - pinned
            # hard in the world cell below (AUTH_VERSION_MISMATCH) - and
            # the realmbuilds advertisement keeps the realm hidden from
            # that build's client (the client-side visibility gate). A
            # refusal HERE is still a valid verdict; any outcome other
            # than a clean matching-style session is recorded verbatim.
            reason = str(logon.get("reason", ""))
            if logon.get("ok"):
                cell["logon"] = "accepted(srp6-build-agnostic)"
                print(f"  {label}: logon accepted (SRP6 is build-agnostic); "
                      "the session gate is the world build check", flush=True)
            elif "challenge rejected with result 9" in reason:
                cell["logon"] = "refused(version-invalid)"
                print(f"  {label}: logon refused (version-invalid)", flush=True)
            elif "proof-response" in reason or "frame" in reason:
                cell["logon"] = "refused(frame-drift)"
                print(f"  {label}: logon refused (frame drift: the server "
                      "served the presented build's layout)", flush=True)
            else:
                cell["logon"] = f"refused({reason[:80]})"
                print(f"  {label}: logon refused ({reason[:80]})", flush=True)
        lane["cells"].append(cell)
        results["cells"].append(cell)

        # WORLD cell: raw socket, the shared 0x1EC asserted, the verdict
        # pinned per expectation. The session key rides the matching
        # logon's proto-logon (provider-neutral); the mismatched cells
        # present the SAME key material with the wrong build - the
        # world's IsAcceptableClientBuild gate must refuse them before
        # any session logic.
        # the realmd -> MariaDB sessionkey write can land after the proof
        # response the client already consumed; settle before the world
        # cell reads the account row, and retry the MATCHING cell once.
        time.sleep(3.0)
        verdict = world_cell(label, client_flavor, build, lane_key,
                             expect_ok=expect_ok, server_flavor=flavor_id)
        if expect_ok and verdict.get("world") not in ("ok",):
            time.sleep(5.0)
            if flavor_id != "vanilla":
                # the retry re-sends the addon block: a fresh boot on the
                # anticheat lanes, or the fingerprint law kicks it
                host = reboot_lane("matching-cell retry (fresh fingerprint)")
            verdict = world_cell(label + " (retry)", client_flavor, build,
                                 lane_key,
                                 expect_ok=expect_ok, server_flavor=flavor_id)
        cell.update(verdict)
        print(f"  {label}: world {verdict['world']}", flush=True)
        # R2-1 (code-red round 1) + R2-r3-1 (round 3): after a MISMATCHED
        # cell whose world verdict was connection-closed, re-prove WORLD
        # LIVENESS with the lane's matching cell — if that now fails, the
        # close was a world CRASH (drift), not a refusal, and the lane
        # FAILS. The old helper swallowed the SystemExit into a recorded
        # string, so a crashed world certified 3 cells and exit 0. No
        # swallow: a liveness SystemExit propagates out of run_lane and
        # main() counts the lane failed (exit 1). The re-probe world cell
        # re-sends the addon block, so on a non-vanilla lane it rides its
        # OWN fresh boot (R2-r3-2).
        if (not expect_ok
                and "connection-closed" in str(verdict.get("world", ""))):
            # the mismatched LOGON may have COMPLETED server-side (SRP6 is
            # build-agnostic; the frame-drift refusal is a client-side
            # parse) and rewritten the account row's sessionkey — the
            # liveness cell's digest must use the CURRENT row, so refresh
            # the matching logon on EVERY lane (observed live: the vanilla
            # liveness after a frame-drift-refused tbc logon failed the
            # world's digest check with the stale key).
            refresh = host.send_raw(
                {"op": "proto-logon", "user": RPTEST_USER,
                 "pass": RPTEST_PASS, "build": pins[flavor_id]["build"]})
            if refresh.get("ok") and refresh.get("sessionKey"):
                lane_key = refresh["sessionKey"]
            if flavor_id != "vanilla":
                # the liveness world cell re-sends the addon block: a fresh
                # boot on the anticheat lanes (the fingerprint law)
                host = reboot_lane(
                    "the liveness re-probe re-sends the addon block")
            live = world_cell(
                label + " liveness", flavor_id, pins[flavor_id]["build"],
                lane_key, expect_ok=True, server_flavor=flavor_id,
            )
            cell["livenessAfterRefusal"] = live.get("world")
            print(f"  {label}: liveness after close -> {live.get('world')}",
                  flush=True)
    return lane


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--flavor", choices=sorted(manifest_pins()),
                        help="one server lane; default runs the full matrix")
    parser.add_argument("--matrix", action="store_true",
                        help="boot all three lanes (the full 3x3)")
    parser.add_argument("--out", default="build/rp-desktop/auth-matrix.json")
    args = parser.parse_args(argv)

    pins = manifest_pins()
    flavors = list(pins) if (args.matrix or args.flavor is None) else [args.flavor]
    report = {"matrix": [[c, s] for c in pins for s in pins], "lanes": [],
              "cells": [], "drift": None}
    failures = 0
    for flavor_id in flavors:
        with selected_flavor(flavor_id):
            host = DesktopHost(REPO, bots=0, flavor=flavor_id,
                               stderr_path=REPO / "build" / "rp-desktop" /
                               f"auth-matrix-{flavor_id}-stderr.log")
            try:
                report["lanes"].append(run_lane(flavor_id, pins, host, report))
            except SystemExit as drift:
                report["drift"] = str(drift)
                print(drift, flush=True)
                failures += 1
            except Exception as error:  # noqa: BLE001 - the lane dies, the matrix continues
                report["drift"] = f"{flavor_id}: {error}"
                print(f"AUTH-MATRIX [{flavor_id}]: {error}", flush=True)
                failures += 1
            finally:
                try:
                    # pop + close the LIVE host (reboots replace the initial
                    # one); the bookkeeping key must not reach the report
                    (report.pop("liveHost", None) or host).close()
                except Exception:  # noqa: BLE001
                    pass

    out = REPO / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    cells = report["cells"]
    expected_cells = 3 * len(flavors)
    print(f"auth-matrix: {len(cells)}/{expected_cells} cells verdicts recorded, "
          f"{failures} failed lane(s) -> {out}")
    # R2-2: a single-lane run demands 3 cells (all verdicts recorded, no
    # drift); the full matrix demands 9. A green run is exit 0 either way.
    return 1 if (failures or len(cells) != expected_cells) else 0


if __name__ == "__main__":
    raise SystemExit(main())
