"""E2 register pins (plan RP workstream E2, v2.3 s6): the texts.sql
register audit.

The drift the plan names lives in the legacy surfaces: texts.sql's
hello/goodbye/hello_follow pools (modern chat-speak, office-speak, and
the dangling-initiative family that asserts an unproposed action) plus a
handful of inline BOT_TEXT2 sentence literals (GuildManagementActions
worst offenders). The audit rides the 0414 append-only tail migration -
idempotent row-content UPDATEs only - because the shipped 0394 entry is
sha-pinned by the manifest AND the on-device ledger (the s0.11 law;
editing it fail-closes the world). This battery pins:

- the byte-identity of the shipped 0394 texts.sql entry (s0.11, made
  mechanical: the manifest sha must equal the pristine file sha),
- the 0414 shape law (row-content UPDATEs only, WHERE-keyed, no DDL, no
  INSERT/DELETE, and it is the manifest tail),
- the WHERE-key resolution law (every old-text key resolves to a real
  texts.sql row - idempotence on fresh provisions AND upgraded ledgers),
- the register lint over the replacement literals (banned tokens, chat
  acronyms, digits, ASCII, word-count bands - the E1 content-contract
  pattern, not FNV goldens),
- the key-coverage pin (every BOT_TEXT("k") literal in the native tree
  resolves to >= 1 texts.sql row), and
- the six GuildManagement inline-literal fixes (old offenders gone, new
  wording present).

The 533 dead rows (taunt/loot/aoe pools with no reader) stay excluded,
and no key renames happened - A9's once-per-name miss-log diagnostic
keeps its ground.
"""
from __future__ import annotations

import hashlib
import io
import json
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
TEXTS_SQL = ROOT / "native" / "playerbots" / "sql" / "world" / "ai_playerbot_texts.sql"
MIGRATION = ROOT / "sql" / "migrations" / "playerbot-texts-e2-register.sql"
MANIFEST = ROOT / "schemas" / "database-migrations.json"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
GUILD_CPP = (ROOT / "native" / "playerbots" / "playerbot" / "strategy"
             / "actions" / "GuildManagementActions.cpp")
SUBMODULE = ROOT / "native" / "playerbots" / "playerbot"
PATCHES = ROOT / "native" / "patches" / "playerbots"

TEXTS_ENTRY_ID = "0394-playerbot-world-ai_playerbot_texts"
MIGRATION_ENTRY_ID = "0414-playerbot-world-playerbot-texts-e2-register"
AUDITED_FAMILIES = ("hello", "goodbye", "hello_follow")
STATEMENT_COUNT = 30

# The E0/E1 register-lint law (word-boundary acronyms; "|cff"/"gearscore"
# and friends are hard-banned bytes).
BANNED_TOKENS = ("|cff", "gearscore", "http", "www", "|")
CHAT_ACRONYMS = ("lol", "brb", "omg", "wtf", "lmao", "afk", "idk", "btw",
                 "gg", "thx", "pls")
DIGIT_RE = re.compile(r"\d")

# Word-count bands per audited family, derived from the shipped
# replacement vector with a word of headroom either side (the E0 street
# band is 5-24; the texts.sql families are shorter-form by register).
WORD_BOUNDS = {
    "hello": (4, 14),
    "goodbye": (2, 10),
    "hello_follow": (4, 12),
}

# One-arg BOT_TEXT("k") call sites that do NOT resolve to a texts.sql row
# in the pristine upstream tree. Pre-existing upstream residue, frozen
# here so the pin fails on any NEW miss while documenting the old ones -
# A9's LogMissingTextNameOnce diagnostic is the runtime witness for
# exactly this class. This set must never grow.
KNOWN_UPSTREAM_MISSES = frozenset({
    "wait_travel_combat",   # FollowActions.cpp, no row upstream
    "wandering",            # ChatShortcutActions.cpp, no row upstream
})

# One SQL-quoted string body: backslash escapes admitted, kept raw so
# both sides of the WHERE-resolution compare literal-for-literal.
_SQL_STR = r"'((?:[^'\\]|\\.)*)'"
# texts.sql rows: ('name','text',...) with an optional space after the
# first comma (the file mixes both spellings).
FAMILY_ROW_RE = re.compile(r"^\('([a-z_0-9]+)',[ \t]?" + _SQL_STR, re.I)
# The 0414 statement shape: UPDATE ... SET `text` = 'new' WHERE `name` =
# 'family' AND `text` = 'old';
STMT_RE = re.compile(
    r"^UPDATE `ai_playerbot_texts` SET `text` = " + _SQL_STR +
    r" WHERE `name` = '([a-z_0-9]+)' AND `text` = " + _SQL_STR + r";$")


def _read(path: Path) -> str:
    return io.open(path, "r", encoding="utf-8", errors="replace", newline="").read()


def _texts_rows() -> dict[str, set[str]]:
    """name -> set of raw text bodies, escape-aware (both comma spellings)."""
    rows: dict[str, set[str]] = {}
    for line in _read(TEXTS_SQL).split("\n"):
        m = FAMILY_ROW_RE.match(line)
        if m:
            rows.setdefault(m.group(1), set()).add(m.group(2))
    assert rows, "texts.sql row parse came up empty - parser drift"
    return rows


def _migration_statements() -> list[tuple[str, str, str]]:
    """(family, new_text_raw, old_text_raw) per UPDATE statement."""
    stmts = []
    for line in _read(MIGRATION).split("\n"):
        if line.startswith("UPDATE"):
            m = STMT_RE.match(line)
            assert m, f"0414 statement does not match the row-content UPDATE shape: {line[:90]!r}"
            stmts.append((m.group(2), m.group(1), m.group(3)))
    return stmts


def _unescape(s: str) -> str:
    return s.replace("\\'", "'").replace('\\\\', "\\")


def _word_count(line: str) -> int:
    return len([w for w in re.split(r"[^A-Za-z0-9']+", line) if w])


@pytest.fixture(scope="module")
def manifest_entries() -> list[dict]:
    doc = json.loads(io.open(MANIFEST, encoding="utf-8").read())
    return doc["entries"]


@pytest.fixture(scope="module")
def statements() -> list[tuple[str, str, str]]:
    return _migration_statements()


# ------------------------------------------------- the s0.11 pivot law ----
def test_shipped_texts_entry_stays_byte_identical(manifest_entries):
    # The audit must not touch the shipped 0394 entry: the manifest sha
    # has to keep matching the pristine submodule file byte-for-byte
    # (the on-device ledger fail-closes on any byte change).
    entry = next(e for e in manifest_entries
                 if e["migration_id"] == TEXTS_ENTRY_ID)
    raw = TEXTS_SQL.read_bytes()
    assert entry["sql_sha256"] == hashlib.sha256(raw).hexdigest()
    assert entry["sql_size"] == len(raw)


def test_the_audit_is_the_append_only_manifest_tail(manifest_entries):
    # 0414 rides the migration lane and is the LAST entry: anything
    # appended after a release ships must come after it in turn.
    assert manifest_entries[-1]["migration_id"] == MIGRATION_ENTRY_ID
    entry = manifest_entries[-1]
    raw = MIGRATION.read_bytes()
    assert entry["sql_sha256"] == hashlib.sha256(raw).hexdigest()
    assert entry["sql_size"] == len(raw)


def test_the_audit_is_row_content_dml_only():
    # No DDL, no INSERT/DELETE, no key renames: idempotent UPDATEs keyed
    # on (name, old-text) so a fresh provision (manifest replay) and an
    # upgraded ledger both land corrected exactly once. Only statement
    # lines are scanned - the header comment is prose, not DML.
    stmt_lines = [l for l in _read(MIGRATION).split("\n")
                  if l.strip() and not l.lstrip().startswith("--")]
    for line in stmt_lines:
        lowered = line.lower()
        assert lowered.startswith("update "), \
            f"0414 carries a non-UPDATE statement line: {line[:80]!r}"
        for verb in ("insert", "delete", "alter ", "drop ", "create ", "rename"):
            assert verb not in lowered, \
                f"0414 must stay row-content UPDATE DML; found {verb!r}"
    assert len(stmt_lines) == STATEMENT_COUNT
    assert len(_migration_statements()) == STATEMENT_COUNT


def test_no_key_renames_the_families_stay_put(statements):
    families = {family for family, _, _ in statements}
    assert families <= set(AUDITED_FAMILIES), (
        f"0414 touched families outside the audited set: {families - set(AUDITED_FAMILIES)}")
    names = _texts_rows()
    for family, _, _ in statements:
        assert family in names, f"0414 renames or invents the key {family!r}"


# ----------------------------------------------- the idempotence proof ----
def test_every_where_key_resolves_to_a_real_texts_row(statements):
    # The mechanical pre-commit verification, made permanent: each
    # old-text key must exist verbatim (escape-for-escape) in the
    # pristine texts.sql, or the UPDATE silently no-ops on upgraded
    # databases and the drift survives.
    rows = _texts_rows()
    for family, _, old in statements:
        assert old in rows.get(family, ()), (
            f"0414 WHERE key does not resolve to a texts.sql row: "
            f"{family} / {old!r}")


def test_the_plan_named_dangling_initiative_rows_are_covered(statements):
    # The six hello_follow rows assert an unproposed action at master
    # acquisition (consumed @ PlayerbotAI.cpp:2185); the plan names the
    # "Hi, lead the way!" line explicitly.
    follow_olds = {old for family, _, old in statements
                   if family == "hello_follow"}
    assert follow_olds == {
        "Hello, I follow you!",
        "Hello, lead the way!",
        "Hi, lead the way!",
        "Hey, I\\'m following you now!",
        "Ready when you are, I\\'ll follow!",
        "Lead on, I\\'m right behind you!",
    }
    news = {_unescape(new) for family, new, _ in statements
            if family == "hello_follow"}
    # The dangling-initiative family: the row asserts an unproposed
    # action - commands the player to lead. Self-descriptive wording
    # ("I am right behind you", "lead as you like") is the sanctioned
    # register; the imperative is not.
    for bad in ("lead the way", "ready when you are"):
        assert not any(bad in n.lower() for n in news), (
            f"a hello_follow replacement still asserts the dangling initiative: {bad!r}")


def test_no_toodledoo_family_survives(statements):
    # The named worst goodbye offenders must be keyed out.
    olds = {_unescape(old) for _, _, old in statements}
    for gone in ("Toodledoo", "Ciao", "Ta ta for now", "alligator",
                 "absolutely nothing", "See you in court",
                 "no longer required", "Cheers", "Cheerio"):
        assert any(gone in o for o in olds), (
            f"the drifted goodbye line containing {gone!r} is not audited out")


# ----------------------------------------------------- the register lint ----
def test_register_lint_on_every_replacement_literal(statements):
    for family, new, _ in statements:
        line = _unescape(new)
        low = line.lower()
        for token in BANNED_TOKENS:
            assert token not in low, f"{family} leaks {token!r}: {line!r}"
        for acronym in CHAT_ACRONYMS:
            assert not re.search(rf"\b{acronym}\b", low), \
                f"{family} has a chat acronym ({acronym}): {line!r}"
        assert line.isascii(), f"{family} replacement is not ASCII: {line!r}"
        assert not DIGIT_RE.search(line), f"{family} replacement has a digit: {line!r}"


def test_replacement_word_count_bands(statements):
    for family, new, _ in statements:
        line = _unescape(new)
        lo, hi = WORD_BOUNDS[family]
        n = _word_count(line)
        assert lo <= n <= hi, \
            f"{family} replacement word count {n} outside [{lo},{hi}]: {line!r}"


def test_replacements_change_the_row_and_stay_distinct(statements):
    # Every UPDATE must actually change its row, and the new texts must
    # not duplicate each other or a surviving row of the same family
    # (PlayerbotTextMgr draws uniformly per name - duplicates warp the
    # draw, they do not add voice).
    rows = _texts_rows()
    seen_news: set[str] = set()
    for family, new, old in statements:
        assert _unescape(new) != _unescape(old), \
            f"0414 no-op UPDATE: {family} / {new!r}"
        assert _unescape(new) not in seen_news, \
            f"duplicate replacement text: {new!r}"
        seen_news.add(_unescape(new))
        for existing in rows.get(family, ()):
            assert _unescape(new) != _unescape(existing), (
                f"replacement collides with a surviving row in {family}: {new!r}")


# ------------------------------------------------------- key coverage ----
def test_every_bot_text_literal_resolves_to_a_texts_row():
    # The key-coverage pin: BOT_TEXT("k") has no fallback path (a miss
    # returns "" after A9's once-per-name log), so every literal in the
    # native tree must resolve to >= 1 texts.sql row - modulo the frozen
    # pre-existing upstream misses. Scans the three authored surfaces:
    # the pristine submodule, the overlay tree, and the driver's anchor
    # payloads (the build mirror is regenerated from exactly these and
    # is not scanned).
    names = set(_texts_rows())
    pat = re.compile(r'BOT_TEXT\("((?:[^"\\]|\\.)*)"\)')
    scanned: dict[str, set[str]] = {}
    surfaces = list(SUBMODULE.rglob("*.cpp")) + list(SUBMODULE.rglob("*.h"))
    surfaces += list(PATCHES.rglob("*.cpp")) + list(PATCHES.rglob("*.h"))
    surfaces.append(DRIVER)
    for path in surfaces:
        for m in pat.finditer(_read(path)):
            scanned.setdefault(m.group(1), set()).add(path.name)
    assert len(scanned) >= 40, "the BOT_TEXT literal scan came up suspiciously thin"
    misses = sorted(k for k in scanned if k not in names)
    fresh = [k for k in misses if k not in KNOWN_UPSTREAM_MISSES]
    assert not fresh, (
        "new unresolved BOT_TEXT key(s): "
        + ", ".join(f"{k} ({','.join(sorted(scanned[k]))})" for k in fresh))


# ------------------------------------------- the six inline literal fixes ----
GUILD_OLD_OFFENDERS = (
    "Hey man you wanna join my guild",
    "number 1 of the server",
    "raid Molten",
    "gild",
    "watch your dog",
    "lonenly",
)
GUILD_NEW_WORDING = (
    "Well met, %name. Would you join my guild?",
    "We are %members strong and growing.",
    "We march on Molten Core...",
    "Hail, the lot of you! Would anyone join my guild?",
    "..and we would guard your hearth and mend your boots...",
    "..and spare a thought for a lonely soul who could use the company...",
)


def test_guildmanagement_worst_offenders_are_gone():
    text = _read(GUILD_CPP)
    for offender in GUILD_OLD_OFFENDERS:
        assert offender not in text, (
            f"the worst-offender inline literal is still shipped: {offender!r}")


def test_guildmanagement_replacement_wording_is_present():
    text = _read(GUILD_CPP)
    for wording in GUILD_NEW_WORDING:
        assert wording in text, f"missing replacement inline literal: {wording!r}"
    # and the replacements pass the same register lint
    for wording in GUILD_NEW_WORDING:
        low = wording.lower()
        for token in BANNED_TOKENS:
            assert token not in low, f"guild replacement leaks {token!r}"
        assert wording.isascii()
