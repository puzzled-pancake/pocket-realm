"""Runtime dialect: inventory tripwire + ODKU threshold fixture.

The self-maintaining dialect inventory scans the EFFECTIVE runtime source
(the playerbots patches overrides + the playerbots submodule minus the
overridden files + cmangos src minus the build-time modules mirror + the
cmangos patches replacements + both facade runtimes) for the MySQL
dialect classes enumerated. Every hit must be one of:
  - a #define in DatabaseEnv.h (the sanctioned backend macro layer),
  - inside the #else (DO_MYSQL) branch of an #ifdef DO_SQLITE guard - the
    registered rewrites keep their MySQL branch for DO_MYSQL builds,
  - a backend engine-implementation file (Database*/QueryResult* Mysql/
    Postgre/Sqlite) - each compiles only under its own engine,
  - a pristine site whose driver overlay replacement AND upstream anchor
    are verified present, where the hit LINE contains that exact anchor
    text and the per-file per-class hit count matches the registered
    count (file:line granularity).
Anything else fails CI: inventory rot becomes a build failure.

The ODKU fixture (tools/test_sqlite_odku.c) executes the exact rewritten
SQL against the PINNED amalgamation at the 10/30/60 tier boundaries and
includes the unfolded-rewrite negative control.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES_PLAYERBOTS = ROOT / "native" / "patches" / "playerbots"
PATCHES_CMANGOS = ROOT / "native" / "patches" / "cmangos"
PLAYERBOTS = ROOT / "native" / "playerbots"
CMANGOS_SRC = ROOT / "native" / "cmangos" / "src"
AMALGAMATION = ROOT / "native" / ".deps" / "src" / "sqlite" / "sqlite-amalgamation-3460100"
DRIVER = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
ODKU_C = (ROOT / "tools" / "test_sqlite_odku.c").read_text(encoding="utf-8")

# Files the build replaces wholesale from native/patches/playerbots/ (the
# driver's mirror step): the patches copy is the effective source, the
# submodule original is not compiled. The playerbots submodule contains
# NONE of these files - a stale entry with a deleted patches file would
# silently narrow the scan, so existence is asserted.
PATCHES_OVERRIDES = {
    "llm_banter_core.h",
    "PlayerbotLlamaRuntime.h",
    "PlayerbotLlamaRuntime.cpp",
    "PlayerbotLlmMemory.h",
    "PlayerbotLlmMemory.cpp",
    "PlayerbotLlmTools.h",
    "PlayerbotLlmTools.cpp",
    "PlayerbotLlmPersona.h",
    "PlayerbotLlmPersona.cpp",
}

DIALECT_CLASSES = {
    "insert-ignore": re.compile(r"INSERT\s+IGNORE"),
    "on-duplicate-key": re.compile(r"ON\s+DUPLICATE\s+KEY"),
    "unix-timestamp": re.compile(r"UNIX_TIMESTAMP\s*\("),
    "date-add-sub": re.compile(r"DATE_(?:ADD|SUB)\s*\("),
    "now": re.compile(r"NOW\s*\(\s*\)"),
    "curdate": re.compile(r"CURDATE\s*\("),
    "raw-truncate": re.compile(r'"TRUNCATE\s'),
    "delete-order-limit": re.compile(r"DELETE[^;\"']*ORDER\s+BY[^;\"']*LIMIT"),
    "set-names": re.compile(r"SET\s+NAMES\b", re.IGNORECASE),
    "group-concat-separator": re.compile(r"GROUP_CONCAT[^)]*SEPARATOR", re.IGNORECASE),
    "regexp-rlike": re.compile(r"\bREGEXP\b|\bRLIKE\b", re.IGNORECASE),
    # MySQL's SQL-level IF() has no SQLite equivalent (case-sensitive so
    # C++ `if (` never matches).
    "sql-if": re.compile(r"\bIF\s*\("),
}

# Classes whose matches may legitimately span C++ string-literal
# concatenation lines; the file-level DOTALL count must equal the per-line
# count or a multi-line instance evades the per-line scan.
_SPANNING_CLASSES = ("insert-ignore", "on-duplicate-key", "delete-order-limit")

# Pristine sites transformed by driver overlays at build time. A hit is
# anchored only when its LINE contains the exact upstream anchor text (the
# driver carries both the anchor and the replacement), and the per-file
# per-class hit count must equal the registered count: a THIRD raw
# TRUNCATE in ObjectMgr.cpp fails CI instead of riding the exemption.
ANCHORED_SITES = (
    # (path under cmangos src, class, driver replacement, upstream anchor, expected count)
    ("game/Globals/ObjectMgr.cpp", "raw-truncate",
     'DirectExecute(_TRUNCATE_ " creature_zone")',
     'DirectExecute("TRUNCATE creature_zone");', 1),
    ("game/Globals/ObjectMgr.cpp", "raw-truncate",
     'DirectExecute(_TRUNCATE_ " gameobject_zone")',
     'DirectExecute("TRUNCATE gameobject_zone");', 1),
    ("game/Anticheat/module/libanticheat.cpp", "delete-order-limit",
     "DELETE FROM system_fingerprint_usage WHERE rowid IN",
     'fingerprint = ? ORDER BY `time` ASC LIMIT ?");', 1),
)

# Backend engine-implementation files for the OTHER engines: each compiles
# only under its own engine (DatabaseMysql.cpp under DO_MYSQL, etc.) - the
# MySQL dialect there is dead code on the sqlite lane. The SQLITE-lane
# replacements (DatabaseSqlite/QueryResultSqlite) are deliberately NOT
# exempt: they are live effective source on this lane.
_BACKEND_IMPL = re.compile(r"^(Database|QueryResult)(Mysql|Postgre)\.(cpp|h)$")


def _effective_sources() -> list[Path]:
    sources: list[Path] = []
    if PATCHES_PLAYERBOTS.is_dir():
        files = [p for p in PATCHES_PLAYERBOTS.iterdir() if p.suffix in (".cpp", ".h")]
        assert PATCHES_OVERRIDES <= {p.name for p in files}, (
            "stale PATCHES_OVERRIDES entries (patches file deleted?): "
            f"{sorted(PATCHES_OVERRIDES - {p.name for p in files})}")
        sources.extend(files)
    if PLAYERBOTS.is_dir():
        for p in PLAYERBOTS.rglob("*"):
            rel = p.relative_to(PLAYERBOTS)
            if p.suffix not in (".cpp", ".h"):
                continue
            # The wholesale-overridden files ride the patches copy instead.
            if rel.name in PATCHES_OVERRIDES and rel.parent == Path("playerbot"):
                continue
            sources.append(p)
    if CMANGOS_SRC.is_dir():
        for p in CMANGOS_SRC.rglob("*"):
            # src/modules is the build-time PlayerBots mirror (gitignored,
            # recreated per build) - not effective source at rest. The
            # singular Anticheat/module/ path IS effective source.
            rel_parts = p.relative_to(CMANGOS_SRC).parts
            if "modules" in rel_parts:
                continue
            if p.suffix in (".cpp", ".h"):
                sources.append(p)
    # The cmangos patches replacements (effective sqlite-lane source) and
    # both facade runtimes (where future runtime SQL is most likely).
    for base in (PATCHES_CMANGOS, ROOT / "native" / "realm-runtime",
                 ROOT / "native" / "pocket-runtime"):
        if base.is_dir():
            sources.extend(p for p in base.rglob("*") if p.suffix in (".cpp", ".h"))
    # A future root addition that overlaps an existing one would double
    # every count (anchored totals, DOTALL consistency) - fail here instead.
    assert len(sources) == len(set(sources)), "duplicate paths in effective sources"
    return sources


def _do_sqlite_else_regions(lines: list[str]) -> set[int]:
    """Line numbers provably inside the DO_MYSQL branch of a DO_SQLITE
    guard. Takes COMMENT-STRIPPED lines (a commented directive must not
    corrupt the frame stack). Handles nested ``#if``/``#ifndef``/
    ``#ifdef`` (their ``#else`` mutates only their own frame) and
    ``#elif`` (unknown branch - never grants guarded status, so dialect
    there fails loudly)."""
    regions: set[int] = set()
    stack: list[list] = []  # frames: [is_do_sqlite, mysql_side]
    for lineno, line in enumerate(lines, start=1):
        s = line.strip()
        if re.match(r"#\s*ifdef\s+DO_SQLITE\b", s):
            stack.append([True, False])
            continue
        if re.match(r"#\s*ifndef\s+DO_SQLITE\b", s):
            stack.append([True, True])  # its then-branch is the MySQL side
            continue
        if re.match(r"#\s*(?:ifdef|ifndef|if)\b", s):
            stack.append([False, False])
            continue
        if re.match(r"#\s*elif\b", s):
            if stack:
                stack[-1][1] = False  # unknown branch: not provably MySQL
            continue
        if re.match(r"#\s*else\b", s):
            if stack:
                stack[-1][1] = not stack[-1][1]
            continue
        if re.match(r"#\s*endif\b", s):
            if stack:
                stack.pop()
            continue
        if any(is_sql and mysql_side for is_sql, mysql_side in stack):
            regions.add(lineno)
    return regions


def _strip_comments(text: str) -> list[str]:
    """Per-line comment stripping (line-number preserving): skips block-
    comment spans and trailing // comments that sit outside string
    literals, so dialect classes mentioned in prose cannot false-positive
    and matches see only executable text."""
    out: list[str] = []
    in_block = False
    for line in text.splitlines():
        worked = line
        if in_block:
            end = worked.find("*/")
            if end < 0:
                out.append("")
                continue
            worked = worked[end + 2:]
            in_block = False
        start = worked.find("/*")
        if start >= 0:
            end = worked.find("*/", start + 2)
            if end >= 0:
                worked = worked[:start] + " " + worked[end + 2:]
            else:
                worked = worked[:start]
                in_block = True
        slash = worked.find("//")
        if slash >= 0 and worked[:slash].count('"') % 2 == 0:
            worked = worked[:slash]
        out.append(worked)
    return out


def test_dialect_inventory_has_no_unguarded_mysqlisms() -> None:
    # A silently-narrowed scan (one submodule absent) is a vacuous green -
    # skip loudly so the SKIP count exposes it. The
    # patches dirs are in-repo but their absence narrows the scan too.
    if not (PLAYERBOTS.is_dir() and CMANGOS_SRC.is_dir()
            and PATCHES_PLAYERBOTS.is_dir() and PATCHES_CMANGOS.is_dir()):
        pytest.skip("submodules or patches dirs not available - the "
                    "inventory would scan a narrowed set")
    sources = _effective_sources()
    unguarded: list[str] = []
    guarded_classes: set[str] = set()
    anchored_seen: dict[tuple[str, str], int] = {}
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = _strip_comments(text)
        regions = _do_sqlite_else_regions(lines)
        posix = path.as_posix()
        for lineno, line in enumerate(lines, start=1):
            if not line.strip():
                continue
            for cls, pattern in DIALECT_CLASSES.items():
                if not pattern.search(line):
                    continue
                rel = path.relative_to(ROOT).as_posix()
                if path.name == "DatabaseEnv.h" and text.splitlines()[lineno - 1].lstrip().startswith("#define"):
                    continue  # the sanctioned backend macro layer
                if lineno in regions:
                    guarded_classes.add(cls)  # the DO_MYSQL branch of a rewrite
                    continue
                if _BACKEND_IMPL.search(path.name):
                    continue  # engine-native backend implementation file
                anchored = False
                for site_path, site_cls, replacement, upstream, _ in ANCHORED_SITES:
                    if (posix.endswith("native/cmangos/src/" + site_path)
                            and cls == site_cls and upstream in line
                            and replacement in DRIVER and upstream in DRIVER):
                        anchored_seen[(site_path, site_cls)] = (
                            anchored_seen.get((site_path, site_cls), 0) + 1)
                        anchored = True
                        break
                if anchored:
                    guarded_classes.add(cls)
                    continue
                unguarded.append(f"{rel}:{lineno}: [{cls}] {line.strip()[:120]}")
        # Multi-line evasion: a class instance split across lines evades
        # the per-line scan; the instance-level DOTALL count over
        # comment-stripped text must agree with the summed per-line
        # instance counts (both count INSTANCES, not lines).
        stripped_text = "\n".join(lines)
        for cls in _SPANNING_CLASSES:
            pattern = DIALECT_CLASSES[cls]
            text_hits = len(pattern.findall(stripped_text))
            line_hits = sum(len(pattern.findall(line)) for line in lines)
            if text_hits != line_hits:
                unguarded.append(
                    f"{path.relative_to(ROOT).as_posix()}: [{cls}] "
                    f"multi-line dialect instance ({text_hits} text vs "
                    f"{line_hits} per-line instances)")
    assert not unguarded, (
        "unguarded MySQL dialect in effective runtime source (new runtime "
        "statements must be backend-portable or guarded per backend):\n"
        + "\n".join(unguarded))
    # Every anchored file/class must appear exactly its REGISTERED TOTAL
    # count of anchored hits (file:line enumeration: a third raw
    # TRUNCATE in ObjectMgr fails here).
    expected_totals: dict[tuple[str, str], int] = {}
    for site_path, site_cls, _, _, expected in ANCHORED_SITES:
        expected_totals[(site_path, site_cls)] = (
            expected_totals.get((site_path, site_cls), 0) + expected)
    for (site_path, site_cls), expected in expected_totals.items():
        seen = anchored_seen.get((site_path, site_cls), 0)
        assert seen == expected, (
            f"anchored site count drift for {site_path} [{site_cls}]: "
            f"expected {expected}, found {seen} (a new statement in this "
            "file needs its own rewrite/registration)")
    # The registered rewrites must exist: their MySQL branches are exactly
    # these classes (a deleted guard fails the unguarded check above; a
    # deleted rewrite fails here).
    assert {"insert-ignore", "on-duplicate-key", "unix-timestamp",
            "date-add-sub", "now"} <= guarded_classes, (
        f"registered rewrite branches missing from the guarded set: "
        f"{sorted(guarded_classes)}")
    # The anchored classes' coverage exists only when the cmangos
    # submodule is present - require it there (silently-passing without
    # the submodule would hide anchor rot).
    if CMANGOS_SRC.is_dir():
        assert {"raw-truncate", "delete-order-limit"} <= guarded_classes, (
            f"anchored-site classes absent: {sorted(guarded_classes)}")


def test_upsert_schema_dependencies_are_pinned_for_p4() -> None:
    # The ON CONFLICT(`bot`,`player`) upsert REQUIRES the composite PK to
    # survive DDL translation: a non-unique translation (or a dropped
    # PK) makes every relationship write fail at PREPARE time ("ON
    # CONFLICT clause does not match any PRIMARY KEY or UNIQUE
    # constraint") - PExecute never surfaces it. Pin the source DDL; the
    # seeding fidelity harness must assert the translated schema keeps
    # these.
    ddl = (ROOT / "native" / "llm" / "sql" / "ai_playerbot_llm_memory.sql"
           ).read_text(encoding="utf-8")
    assert "PRIMARY KEY (`bot`,`player`)" in ddl, (
        "bot_player_relationship composite PK missing - the P3 upsert "
        "target depends on it (P3->P4 addendum)")
    assert "PRIMARY KEY (`bot`)" in ddl, (
        "bot_backstory PK missing - INSERT OR IGNORE depends on it")
    # AUTO_INCREMENT single-column INTEGER PKs must translate to INTEGER
    # PRIMARY KEY (the rowid alias): the facts/gossip INSERTs omit `id`
    # and depend on auto-assign - any other translation fails NOT NULL at
    # execute, and the runtime-statement shapes (ORDER BY `id` DESC) need
    # the column to keep existing.
    gossip = (ROOT / "native" / "llm" / "sql" / "world_gossip.sql"
              ).read_text(encoding="utf-8")
    for name, schema in (("bot_player_facts", ddl), ("world_gossip", gossip)):
        assert "AUTO_INCREMENT" in schema and "PRIMARY KEY (`id`)" in schema, (
            f"{name}: the id-omitting INSERTs need AUTO_INCREMENT + "
            "PRIMARY KEY(id) translated to INTEGER PRIMARY KEY (rowid "
            "alias) - P3->P4 addendum (d)")


def test_runtime_text_escape_is_backend_aware() -> None:
    # EscapeSql must NOT double backslashes under DO_SQLITE (SQLite
    # string literals have no backslash escapes - only the '' doubling);
    # the MySQL branch keeps the doubling. Pinned with PLACEMENT: the
    # doubling lines must sit inside the #else (DO_MYSQL) region of the
    # EscapeSql #ifdef - hoisting the branch back out (the exact
    # regression) fails here even though every substring still exists.
    cpp = (PATCHES_PLAYERBOTS / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    assert "no backslash escapes" in cpp  # the DO_SQLITE branch comment
    assert 'else if (c == \'\\\\\')' in cpp  # the MySQL branch kept verbatim
    assert cpp.count('out += "\\\\\\\\";') == 1  # exactly one doubling line
    lines = _strip_comments(cpp)
    mysql_regions = _do_sqlite_else_regions(lines)
    doubling_lines = [i for i, line in enumerate(lines, start=1)
                      if 'out += "\\\\\\\\";' in line]
    assert doubling_lines and all(i in mysql_regions for i in doubling_lines), (
        "the backslash-doubling lines must live only inside the DO_MYSQL "
        "#else region of EscapeSql")
    # And nothing doubling backslashes anywhere OUTSIDE a DO_MYSQL region
    # (comment-stripped lines; the primary pin above is exception-free).
    for i, line in enumerate(lines, start=1):
        if "\\\\" in line and i not in mysql_regions:
            raise AssertionError(
                f"backslash doubling outside a DO_MYSQL region at line {i}: "
                f"{line.strip()[:80]}")


def test_anchored_overlay_constants_are_actually_wired() -> None:
    # A dead constant passes the anchored exemption while a deleted
    # replace_anchor call ships pristine MySQL on the sqlite lane
    # (DirectExecute SQL is not compile-checked). Each anchored site's
    # replacement constant must be referenced by a replace_anchor call.
    import re as _re
    driver_source = (ROOT / "tools" / "build_o09_realm_runtime.py"
                     ).read_text(encoding="utf-8")
    for _, _, replacement, _, _ in ANCHORED_SITES:
        name = next(
            (m.group(1) for m in _re.finditer(r"(\w+) = '''(.*?)'''",
                                              driver_source, _re.DOTALL)
             if replacement in m.group(2)), None)
        assert name, f"replacement constant missing for: {replacement[:50]}"
        assert _re.search(r"replace_anchor\([^)]*\b" + name + r"\b",
                          driver_source, _re.DOTALL), (
            f"{name} is defined but never wired to a replace_anchor call - "
            "the sqlite lane would ship the pristine MySQL statement")


def test_connection_layer_escape_string_does_not_double_backslashes() -> None:
    # The OTHER escape surface (same class): the hardened
    # SQLiteConnection::escape_string must keep backslashes single
    # (SQLite literals have no backslash escapes).
    cpp = (PATCHES_CMANGOS / "DatabaseSqlite.cpp").read_text(encoding="utf-8")
    assert "case '\\\\': newTo += \"\\\\\";" in cpp, (
        "escape_string must emit a SINGLE backslash for a backslash input "
        "(SQLite has no backslash escapes)")


def test_pinned_ddl_files_match_the_migration_manifest_hashes() -> None:
    # Bind the pinned DDL files to manifest entries 0411/0412
    # by sql_sha256 - a shape-preserving DDL edit (passing the schema
    # pins) then fails HERE instead of drifting silently into the seeded
    # database.
    import hashlib
    import json

    manifest = json.loads((ROOT / "schemas" / "database-migrations.json")
                          .read_text(encoding="utf-8"))
    by_source = {e["source_path"]: e for e in manifest["entries"]}
    # A duplicate source_path append would silently key-collapse the dict
    # and weaken the binding to whichever entry lands last.
    assert len(by_source) == len(manifest["entries"]), (
        "duplicate source_path in the migration manifest")
    # Append-only discipline pinned mechanically: the exact entry count
    # and the 0412/0413 tail (a silent mid-array insertion fails here,
    # not at seed time).
    assert len(manifest["entries"]) == 413, (
        f"migration manifest has {len(manifest['entries'])} entries, "
        "expected the pinned 413")
    assert [e["migration_id"] for e in manifest["entries"][-2:]] == [
        "0412-playerbot-world-world_gossip",
        "0413-playerbot-characters-ai_playerbot_llm_memory_v2"], (
        "the manifest tail must stay 0412/0413 (append-only)")
    for rel in ("native/llm/sql/ai_playerbot_llm_memory.sql",
                "native/llm/sql/world_gossip.sql",
                "sql/migrations/ai_playerbot_llm_memory_v2.sql"):
        entry = by_source.get(rel)
        assert entry is not None, f"{rel} missing from the migration manifest"
        blob = (ROOT / rel).read_bytes()
        digest = hashlib.sha256(blob).hexdigest()
        assert digest == entry["sql_sha256"], (
            f"{rel} drifted from manifest {entry['migration_id']} "
            f"(sql_sha256 mismatch - regenerate the manifest entry)")
        assert len(blob) == entry["sql_size"]


def test_travelmgr_benign_ddl_is_unchanged_and_parses() -> None:
    travel = PLAYERBOTS / "playerbot" / "TravelMgr.cpp"
    if not travel.is_file():
        pytest.skip("playerbots submodule not available")
    text = travel.read_text(encoding="utf-8", errors="replace")
    assert "CREATE TABLE IF NOT EXISTS `ai_playerbot_zone_level`" in text
    # behavioral proof (parse + round-trip) lives in the fixture below
    assert "`id` bigint(20) NOT NULL" in text


def test_odku_fixture_matches_the_runtime_literals() -> None:
    cpp = (PATCHES_PLAYERBOTS / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    # whitespace-insensitive fragment parity between the C fixture and the
    # C++ runtime literals (the %%s vs %s difference is PExecute's printf
    # escaping and is asserted per-side below).
    fragments_cpp_fixture = [
        "ON CONFLICT(`bot`, `player`) DO UPDATE SET `points` = `points` + excluded.`points`,",
        "CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted'",
        "WHEN `points` + excluded.`points` >= 30 THEN 'ally'",
        "WHEN `points` + excluded.`points` >= 10 THEN 'acquaintance'",
        "INSERT OR IGNORE INTO `bot_backstory` (`bot`, `text`)",
        "datetime('now', '+7 day')",
        # C5: tier_since stamps only on a real crossing (the pre-update
        # tier compare), in both the runtime literal and the fixture
        "`tier_since` = CASE WHEN `tier` <> (CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted'",
    ]
    for fragment in fragments_cpp_fixture:
        assert _collapse(cpp).find(_collapse(fragment)) >= 0, f"runtime literal lost: {fragment}"
        assert _collapse(ODKU_C).find(_collapse(fragment)) >= 0, f"fixture drifted: {fragment}"
    # PExecute printf-escaping: the C++ template doubles the % of strftime.
    assert "strftime('%%s', `last_interaction_at`)" in _collapse(cpp)
    assert "strftime('%s', `last_interaction_at`)" in ODKU_C
    # The anticheat rewrite rides the driver overlay; fixture parity too.
    assert "DELETE FROM system_fingerprint_usage WHERE rowid IN" in DRIVER
    assert "fingerprint = ? ORDER BY `time` ASC LIMIT ?" in DRIVER
    assert "DELETE FROM system_fingerprint_usage WHERE rowid IN" in ODKU_C
    # The negative control must test the UNFOLDED shape (no excluded fold).
    assert "CASE WHEN `points` >= 60 THEN 'trusted'" in ODKU_C


def _collapse(text: str) -> str:
    return re.sub(r"\s+", " ", text)


def test_odku_threshold_fixture_against_the_pinned_amalgamation() -> None:
    """Compile the pinned amalgamation + the ODKU fixture for the host
    (core define set; the full production recipe adds FTS5/RTREE/
    metadata/json1/dbstat) and run it: the folded rewrite hits the
    10/30/60 boundaries exactly, the unfolded control lags, the
    benign TravelMgr DDL parses, and the anticheat rowid rewrite binds."""
    gcc = shutil.which("gcc") or shutil.which("clang") or shutil.which("cc")
    if gcc is None:
        pytest.skip("no host C compiler available")
    if not (AMALGAMATION / "sqlite3.c").is_file():
        pytest.skip("amalgamation not staged (run tools/stage_sqlite_amalgamation.py)")
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        binary = tmp_path / "odku_test.exe"
        build = subprocess.run(
            [gcc, "-O1",
             "-DSQLITE_THREADSAFE=2",
             "-DSQLITE_DEFAULT_WAL_SYNCHRONOUS=2",
             "-I", str(AMALGAMATION), "-o", str(binary),
             str(AMALGAMATION / "sqlite3.c"),
             str(ROOT / "tools" / "test_sqlite_odku.c")],
            capture_output=True, text=True, timeout=300)
        if build.returncode != 0:
            pytest.fail(f"host compile failed:\n{build.stderr[:2000]}")
        run = subprocess.run([str(binary), str(tmp_path / "odku.db")],
                             capture_output=True, text=True, timeout=120)
        assert run.returncode == 0, (
            f"odku fixture failed:\n{run.stdout}\n{run.stderr[:2000]}")
        assert "odku fixture passed" in run.stdout
