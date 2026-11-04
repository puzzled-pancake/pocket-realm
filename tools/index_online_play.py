#!/usr/bin/env python3
"""Index the online-play screens and gate their wording.

Writes docs/online-play/INDEX.md from the FILES list below. For each file it
emits: the path, line count and purpose; every @Composable function; every
testTag literal; every player-visible string literal (Text(...), label/placeholder
 lambdas, title, const val ...COPY); every settings field read or written
(snapshot.<field> / it.copy(<field> = ...)); and every hit of the banned word
list. Output is one line per item, no code bodies.

With --check [SCOPE] the index is still written and the script exits 1 when a
banned word remains. SCOPE, when given, limits the failing hits to files whose
path contains that substring (e.g. `--check ui/online`), so a phase can gate
just the files it owns while the not-yet-migrated screens still carry old copy.

Banned-word scope: the gate covers the player-facing screens, i.e. the FILES
that live under a */ui/ directory. Non-UI sources are indexed but not
word-gated (they are not screens; their identifiers legitimately name the
transport). Files whose *name* contains "Advanced" may use technical terms and
are only gated on process wording. Comment lines are never counted.

Run from anywhere; paths are resolved against the repository root.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX_PATH = ROOT / "docs" / "online-play" / "INDEX.md"

# Every file created or touched by the online-play task. Repo-relative, forward
# slashes. Keep in the order the phases touch them.
FILES = [
    "android/app/src/main/java/com/pocketrealm/ui/HomeScreen.kt",
    "android/app/src/main/java/com/pocketrealm/ui/SettingsScreen.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/PlayersAdvanced.kt",
    # Phase 1: host online without LAN (D2)
    "android/app/src/main/java/com/pocketrealm/supervisor/RuntimeTopology.kt",
    "android/app/src/main/java/com/pocketrealm/supervisor/RuntimeSnapshotJournalCodec.kt",
    "android/app/src/main/java/com/pocketrealm/service/RealmService.kt",
    "android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt",
    # Phase 2: password and unlisted games (D3-D6)
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/JoinAdmission.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InviteCode.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/GatewayContract.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InternetHostGateway.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InternetJoinGateway.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/RealmListingEvent.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/HostListingPublisher.kt",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/ListingStore.kt",
    "android/app/src/main/java/com/pocketrealm/storage/Settings.kt",
    "android/app/src/main/java/com/pocketrealm/service/PublicListingHost.kt",
    "android/app/src/main/java/com/pocketrealm/supervisor/PublicHostModeration.kt",
    "desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/iroh/IrohGatewayManager.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/nostr/NostrListingPublisher.kt",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/GatewayMain.kt",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/HostGateway.kt",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/JoinGateway.kt",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/SignupChannel.kt",
    "desktop/shared-sources.json",
    # Phase 3: shared model, wizard, saved logins
    "android/app/src/main/java/com/pocketrealm/ui/online/OnlinePlayModel.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/JoinWizard.kt",
    "android/app/src/main/java/com/pocketrealm/storage/JoinedRealmLogins.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/JoinWizard.kt",
    # Phase 4: the Android Multiplayer screens
    "android/app/src/main/java/com/pocketrealm/ui/online/MultiplayerScreen.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/FindGameTab.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/HostTab.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/HostAdvanced.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/DirectJoinTab.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/DirectJoinAdvanced.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/JoinFlow.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/TransferTab.kt",
    "android/app/src/main/java/com/pocketrealm/ui/online/PlayersList.kt",
    "android/app/src/main/java/com/pocketrealm/onlineplay/GameBrowserBridge.kt",
    "android/app/src/main/java/com/pocketrealm/onlineplay/JoinBridge.kt",
    "android/app/src/main/java/com/pocketrealm/onlineplay/HostBridge.kt",
    "android/app/src/main/java/com/pocketrealm/ui/PocketRealmApp.kt",
    # Phase 5: the desktop Multiplayer screens + packaging
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/MultiplayerScreen.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/FindGameTab.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/HostTab.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/HostAdvanced.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/DirectJoinTab.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/DirectJoinAdvanced.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/JoinFlow.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/JoinLaneAdvanced.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/onlineplay/JoinBridge.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/onlineplay/HostBridge.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/Router.kt",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/Main.kt",
]

# One-line purpose per file (shown in the Files section).
PURPOSES = {
    "android/app/src/main/java/com/pocketrealm/ui/HomeScreen.kt":
        "Android home screen: play buttons and cards routing into the app",
    "android/app/src/main/java/com/pocketrealm/ui/SettingsScreen.kt":
        "Android settings, including the LAN play section",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt":
        "Desktop home screen: runtime mode cards, tunnel card, guests",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt":
        "Desktop settings, including the LAN play mode picker",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/PlayersAdvanced.kt":
        "Desktop joined-players screen with kick/ban",
    # Phase 1
    "android/app/src/main/java/com/pocketrealm/supervisor/RuntimeTopology.kt":
        "Shared endpoint/launch-spec/watch model (host grammar incl. the online loopback host)",
    "android/app/src/main/java/com/pocketrealm/supervisor/RuntimeSnapshotJournalCodec.kt":
        "Journal codec (accepts the online host's loopback LAN_HOST journal)",
    "android/app/src/main/java/com/pocketrealm/service/RealmService.kt":
        "Runtime service: start/host/join actions, incl. ACTION_HOST_ONLINE",
    "android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt":
        "Android runtime backend (preflight, bind address, interface proofs)",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt":
        "Desktop runtime backend (liveness twins)",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt":
        "Desktop app model (start/host-online entry points, loss monitor)",
    # Phase 2
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/JoinAdmission.kt":
        "Hello lane v2 (device token + password proof), device identity, passcode math",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InviteCode.kt":
        "The unlisted game's shareable invite code (PR1- + base64url of the join descriptor)",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/GatewayContract.kt":
        "App/gateway process line protocol (incl. the pass key over stdin)",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InternetHostGateway.kt":
        "In-app iroh host endpoint (hello admission, game stream mux)",
    "android/app/src/main/java/com/pocketrealm/discovery/iroh/InternetJoinGateway.kt":
        "In-app iroh join gateway (hello with proof, realm-list rewrite)",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/RealmListingEvent.kt":
        "The listing event/content schema (incl. the locked fact)",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/HostListingPublisher.kt":
        "The listing heartbeat (online/listed gates, unlisted state)",
    "android/app/src/main/java/com/pocketrealm/discovery/nostr/ListingStore.kt":
        "The browser's listing admission ladder and freshness store",
    "android/app/src/main/java/com/pocketrealm/storage/Settings.kt":
        "Android settings store (onlineHosting, listInBrowser, gamePassword)",
    "android/app/src/main/java/com/pocketrealm/service/PublicListingHost.kt":
        "Host glue: gateway + listing + sign-up + admission with the password gate",
    "android/app/src/main/java/com/pocketrealm/supervisor/PublicHostModeration.kt":
        "Shared bans/sign-up limits/password lockout/player-limit admission",
    "desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt":
        "Desktop settings twin (same online-play fields + migration)",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/iroh/IrohGatewayManager.kt":
        "Desktop gateway process manager (admit lines with the proof)",
    "desktop/src/main/kotlin/com/pocketrealm/desktop/nostr/NostrListingPublisher.kt":
        "Desktop listing publisher twin",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/GatewayMain.kt":
        "Gateway process entry (arg parsing incl. --passkey-from-stdin)",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/HostGateway.kt":
        "Gateway process host role (hello decode, admit channel)",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/JoinGateway.kt":
        "Gateway process join role (hello with the derived proof)",
    "gateway/src/main/kotlin/com/pocketrealm/gateway/SignupChannel.kt":
        "Gateway process stdio channel (admit lines carry the proof)",
    "desktop/shared-sources.json":
        "Which android files the desktop build compiles",
    # Phase 3
    "android/app/src/main/java/com/pocketrealm/ui/online/OnlinePlayModel.kt":
        "THE shared model: all multiplayer copy, host statuses, refusals, list sorting/filters, wizard machine",
    "android/app/src/main/java/com/pocketrealm/ui/online/JoinWizard.kt":
        "The first-join wizard composable (Android)",
    "android/app/src/main/java/com/pocketrealm/storage/JoinedRealmLogins.kt":
        "Saved game logins (one per game, owner-only file, migrated markers)",
    # Phase 4
    "android/app/src/main/java/com/pocketrealm/ui/online/MultiplayerScreen.kt":
        "The Multiplayer destination: the four tabs over the shared model",
    "android/app/src/main/java/com/pocketrealm/ui/online/FindGameTab.kt":
        "Find a game: search, filters, rows, details sheet, join entry",
    "android/app/src/main/java/com/pocketrealm/ui/online/HostTab.kt":
        "Host: the one-button online host form, live status, invite code, players",
    "android/app/src/main/java/com/pocketrealm/ui/online/HostAdvanced.kt":
        "Host Advanced: LAN players, friend access, guests, tunnel hosting, bans (moved panes)",
    "android/app/src/main/java/com/pocketrealm/ui/online/DirectJoinTab.kt":
        "Direct join: invite code + local address + probe",
    "android/app/src/main/java/com/pocketrealm/ui/online/DirectJoinAdvanced.kt":
        "Direct join Advanced: request an account with an invite (moved pane)",
    "android/app/src/main/java/com/pocketrealm/ui/online/JoinFlow.kt":
        "The shared join flow: password prompt + first-join wizard + session progress",
    "android/app/src/main/java/com/pocketrealm/ui/online/TransferTab.kt":
        "Transfer: the character and whole-realm cards",
    "android/app/src/main/java/com/pocketrealm/ui/online/PlayersList.kt":
        "The host's players list with kick/ban/device-ban (moved section)",
    "android/app/src/main/java/com/pocketrealm/onlineplay/GameBrowserBridge.kt":
        "Plain-named bridge to the game-list machinery (no technical names reach the screens)",
    "android/app/src/main/java/com/pocketrealm/onlineplay/JoinBridge.kt":
        "Plain-named join verbs (join a game, invite codes, local probe, account requests)",
    "android/app/src/main/java/com/pocketrealm/onlineplay/HostBridge.kt":
        "Plain-named host verbs and status reads",
    "android/app/src/main/java/com/pocketrealm/ui/PocketRealmApp.kt":
        "The app shell: the rail (Multiplayer entry) and routes",
}

# Words banned on main screens (case-insensitive, whole-word matches), plus
# regex patterns. Advanced-named screen files are only gated on the process
# wording (they may name the technology).
BANNED_MAIN_WORDS = [
    "ipv4", "ipv6", "cgnat", "3724", "8085", "port", "realmlist", "verifier",
    "srp", "relay", "nostr", "iroh", "tunnel", "tailscale", "zerotier",
    "cloudflared", "endpoint", "descriptor", "lane", "flavor", "honest",
    "pinned", "owner", "decision", "wave",
]
BANNED_MAIN_PATTERNS = [r"VAL-\d+", r"\bD\d{2,3}\b", r"\bcode-red\b"]
BANNED_PROCESS_WORDS = [
    "lane", "flavor", "honest", "pinned", "owner", "decision", "wave",
]
BANNED_PROCESS_PATTERNS = [r"VAL-", r"\bD\d{2,3}\b", r"\bcode-red\b"]

MAIN_WORD_RES = [(w, re.compile(rf"\b{w}\b", re.IGNORECASE)) for w in BANNED_MAIN_WORDS]
MAIN_PATTERN_RES = [(p, re.compile(p, re.IGNORECASE)) for p in BANNED_MAIN_PATTERNS]
PROCESS_WORD_RES = [(w, re.compile(rf"\b{w}\b", re.IGNORECASE)) for w in BANNED_PROCESS_WORDS]
PROCESS_PATTERN_RES = [(p, re.compile(p, re.IGNORECASE)) for p in BANNED_PROCESS_PATTERNS]

STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
COMPOSABLE_RE = re.compile(r"@Composable")
FUN_NAME_RE = re.compile(r"\bfun\s+([A-Za-z0-9_]+)")
TEST_TAG_RE = re.compile(r'testTag\(\s*"((?:[^"\\]|\\.)*)"(\s*\+)?')
TEXT_CALL_RE = re.compile(r"\bText\(")
LABEL_LAMBDA_RE = re.compile(r"\b(label|placeholder|title)\s*=\s*[{\"]")
LABEL_ASSIGN_RE = re.compile(r'\b(label|placeholder|title)\s*=\s*"')
CONST_COPY_RE = re.compile(r"\bconst\s+val\s+([A-Za-z0-9_]*COPY)\b")
SNAPSHOT_FIELD_RE = re.compile(r"\bsnapshot\.([A-Za-z0-9_]+)")
COPY_FIELD_RE = re.compile(r"\bit\.copy\(\s*([A-Za-z0-9_]+)\s*=")

MAX_STRING_LEN = 120


def read_lines(path: Path) -> list[str]:
    return path.read_bytes().decode("utf-8", errors="replace").splitlines()


def is_comment_line(line: str) -> bool:
    stripped = line.strip()
    return (
        stripped.startswith("//")
        or stripped.startswith("/*")
        or stripped.startswith("*")
        or stripped.startswith("*/")
    )


def is_advanced(path: str) -> bool:
    return "Advanced" in Path(path).name


def is_screen_file(path: str) -> bool:
    parts = Path(path).parts
    return "ui" in parts


def cut(text: str) -> str:
    text = text.replace("\n", " ").strip()
    return text if len(text) <= MAX_STRING_LEN else text[: MAX_STRING_LEN - 1] + "…"


def collect_composables(lines: list[str], path: str) -> list[tuple[str, int, str]]:
    out: list[tuple[str, int, str]] = []
    for i, line in enumerate(lines):
        if not COMPOSABLE_RE.search(line):
            continue
        # same line: @Composable fun Name, else look ahead a few lines
        m = FUN_NAME_RE.search(line)
        if m:
            out.append((path, i + 1, m.group(1)))
            continue
        for j in range(i + 1, min(i + 4, len(lines))):
            m = FUN_NAME_RE.search(lines[j])
            if m:
                out.append((path, i + 1, m.group(1)))
                break
            if lines[j].strip() and not lines[j].lstrip().startswith("@"):
                break
    return out


def collect_test_tags(lines: list[str], path: str) -> list[tuple[str, int, str]]:
    out: list[tuple[str, int, str]] = []
    for i, line in enumerate(lines):
        m = TEST_TAG_RE.search(line)
        if m:
            tag = m.group(1) + ("…" if m.group(2) else "")
            out.append((path, i + 1, tag))
    return out


def first_literal(text: str) -> str | None:
    m = STRING_LITERAL_RE.search(text)
    return m.group(1) if m else None


# argument-window markers: a Text("...") literal always precedes named
# arguments, so anything after these belongs to another call (e.g. a testTag)
ARG_WINDOW_MARKERS = [", modifier", ", style", ", color", ", fontSize", ", fontWeight"]


def text_call_literal(rest: str) -> str | None:
    window = rest
    for marker in ARG_WINDOW_MARKERS:
        idx = window.find(marker)
        if idx != -1:
            window = window[:idx]
    return first_literal(window)


def collect_player_strings(lines: list[str], path: str) -> list[tuple[str, int, str]]:
    out: list[tuple[str, int, str]] = []
    seen: set[tuple[int, str]] = set()

    def add(line_no: int, text: str | None) -> None:
        if text is None:
            return
        key = (line_no, text)
        if key in seen:
            return
        seen.add(key)
        out.append((path, line_no, cut(text)))

    for i, line in enumerate(lines):
        # Text("...")
        for m in TEXT_CALL_RE.finditer(line):
            rest = line[m.end():]
            literal = text_call_literal(rest)
            if literal is None and rest.strip() == "":
                # string starts on the next line
                if i + 1 < len(lines):
                    literal = text_call_literal(lines[i + 1].lstrip())
                    if literal is not None:
                        add(i + 2, literal)
                continue
            add(i + 1, literal)
        # label = { Text("...") } / label = "..."
        for m in LABEL_LAMBDA_RE.finditer(line):
            literal = first_literal(line[m.start():])
            if literal is None:
                # scan the next few lines until the lambda closes
                for j in range(i + 1, min(i + 4, len(lines))):
                    literal = first_literal(lines[j])
                    if literal is not None:
                        add(j + 1, literal)
                        break
                    if "}" in lines[j]:
                        break
            else:
                add(i + 1, literal)
        for m in LABEL_ASSIGN_RE.finditer(line):
            add(i + 1, first_literal(line[m.start():]))
        # const val ...COPY = "..." (possibly concatenated over lines)
        for m in CONST_COPY_RE.finditer(line):
            literal = first_literal(line[m.end():])
            pieces = [literal] if literal is not None else []
            j = i
            # keep consuming while the expression continues with '+'
            while j + 1 < len(lines):
                if not lines[j].rstrip().endswith("+"):
                    break
                j += 1
                nxt = first_literal(lines[j])
                if nxt is not None:
                    pieces.append(nxt)
            joined = " ".join(p for p in pieces if p is not None)
            add(i + 1, joined or None)
    return out


def collect_settings_fields(lines: list[str], path: str) -> list[tuple[str, int, str]]:
    out: list[tuple[str, int, str]] = []
    for i, line in enumerate(lines):
        for m in SNAPSHOT_FIELD_RE.finditer(line):
            out.append((path, i + 1, f"{m.group(1)} (snapshot)"))
        m = COPY_FIELD_RE.search(line)
        if m:
            out.append((path, i + 1, f"{m.group(1)} (copy)"))
    return out


def collect_banned(lines: list[str], path: str) -> list[tuple[str, int, str]]:
    if not is_screen_file(path):
        return []
    words, patterns = (
        (PROCESS_WORD_RES, PROCESS_PATTERN_RES)
        if is_advanced(path)
        else (MAIN_WORD_RES, MAIN_PATTERN_RES)
    )
    out: list[tuple[str, int, str]] = []
    for i, line in enumerate(lines):
        if is_comment_line(line):
            continue
        for word, rex in words:
            if rex.search(line):
                out.append((path, i + 1, word))
        for pat, rex in patterns:
            if rex.search(line):
                m = rex.search(line)
                out.append((path, i + 1, m.group(0)))
    return out


def parse_file(path: str) -> dict:
    full = ROOT / path
    if not full.exists():
        return {"path": path, "missing": True}
    lines = read_lines(full)
    return {
        "path": path,
        "missing": False,
        "line_count": len(lines),
        "composables": collect_composables(lines, path),
        "test_tags": collect_test_tags(lines, path),
        "strings": collect_player_strings(lines, path),
        "settings": collect_settings_fields(lines, path),
        "banned": collect_banned(lines, path),
    }


def build_index(parsed: list[dict]) -> str:
    md: list[str] = []
    md.append("# Online-play UI index (generated)")
    md.append("")
    md.append("Generated by `tools/index_online_play.py`. Do not edit by hand.")
    md.append("")

    md.append("## Files")
    md.append("")
    for entry in parsed:
        if entry["missing"]:
            md.append(f"{entry['path']} — MISSING (deleted or renamed)")
            continue
        purpose = PURPOSES.get(entry["path"], "")
        md.append(f"{entry['path']} · {entry['line_count']} lines · {purpose}")
    md.append("")

    md.append("## Composables")
    md.append("")
    composables = [c for e in parsed if not e["missing"] for c in e["composables"]]
    if composables:
        for path, line, name in composables:
            md.append(f"{path}:{line}  {name}")
    else:
        md.append("(none)")
    md.append("")

    md.append("## Test tags")
    md.append("")
    tags = [t for e in parsed if not e["missing"] for t in e["test_tags"]]
    if tags:
        for path, line, tag in tags:
            md.append(f"{tag} → {path}:{line}")
    else:
        md.append("(none)")
    md.append("")

    md.append("## Player-visible strings")
    md.append("")
    strings = [s for e in parsed if not e["missing"] for s in e["strings"]]
    if strings:
        for path, line, text in strings:
            md.append(f'{path}:{line}  "{text}"')
    else:
        md.append("(none)")
    md.append("")

    md.append("## Settings fields")
    md.append("")
    fields = [f for e in parsed if not e["missing"] for f in e["settings"]]
    if fields:
        for path, line, field in fields:
            md.append(f"{path}:{line}  {field}")
    else:
        md.append("(none)")
    md.append("")

    md.append("## Banned words")
    md.append("")
    md.append("Hits of the banned word list on main screens (`*/ui/*` files;")
    md.append("`Advanced`-named screen files are only checked for process wording).")
    md.append("")
    banned = [b for e in parsed if not e["missing"] for b in e["banned"]]
    if banned:
        for path, line, word in banned:
            md.append(f"{path}:{line}  {word}")
    else:
        md.append("(none)")
    md.append("")
    return "\n".join(md)


def main(argv: list[str]) -> int:
    check = False
    scope: str | None = None
    args = argv[1:]
    if args and args[0] == "--check":
        check = True
        if len(args) > 1:
            scope = args[1]
    elif args:
        print(f"usage: {Path(__file__).name} [--check [scope-substring]]", file=sys.stderr)
        return 2

    parsed = [parse_file(p) for p in FILES]
    index = build_index(parsed)
    INDEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    # write LF bytes explicitly (repo rule: no CRLF churn from text mode)
    INDEX_PATH.write_bytes(index.encode("utf-8"))

    banned = [b for e in parsed if not e["missing"] for b in e["banned"]]
    if scope:
        banned = [b for b in banned if scope in b[0]]
    files_ok = sum(1 for e in parsed if not e["missing"])
    print(
        f"wrote {INDEX_PATH.relative_to(ROOT)}: {len(FILES)} files "
        f"({files_ok} present), {len(banned)} banned-word hits"
    )
    if check and banned:
        for path, line, word in banned:
            print(f"BANNED {path}:{line}  {word}", file=sys.stderr)
        target = f" (scope: {scope})" if scope else ""
        print(f"--check failed{target}: banned words remain on main screens", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
