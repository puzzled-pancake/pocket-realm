#!/usr/bin/env python3
"""Seed the four SQLite realm databases from the PINNED 412-entry
migration manifest.

Single source of truth: schemas/database-migrations.json — the same
ordered, hash-verified entries the MariaDB lane applies. Each entry's
bytes are verified against the manifest sql_sha256 BEFORE translation,
translated by
tools/sqlite_seed_translator.py (string-aware; no line-based splitting
anywhere), and executed into per-database SQLite files named by the
manifest's `database` field (classicrealmd/classiccharacters/classiclogs/
classicmangos).

Fail-loud: any statement error aborts the seed (zero tolerated errors).
Emits a deterministic translation report
(schemas/sqlite-seed-baseline.json when --write-baseline) plus optional
.sql transcripts (one per database, in replay order) for the
pinned-amalgamation harness leg and the future dual-provider APK seed
assets (sibling artifacts - the MariaDB manifest stays untouched).

Usage:
    python3 tools/seed_sqlite_from_manifest.py --out <dir> [--transcripts <dir>]
        [--write-baseline] [--max-errors N]
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import sqlite_seed_translator as translator  # noqa: E402

MANIFEST = ROOT / "schemas" / "database-migrations.json"
# Append-only per-database seed augmentations (see
# apply_seed_augments); module-level so tests can isolate it.
AUGMENT_DIR = ROOT / "schemas" / "seed-augment"
BASELINE = ROOT / "schemas" / "sqlite-seed-baseline.json"


def entry_bytes(entry: dict) -> bytes:
    path = ROOT / entry["source_path"]
    data = (gzip.open(path, "rb").read() if path.suffix == ".gz"
            else path.read_bytes())
    digest = hashlib.sha256(data).hexdigest()
    if digest != entry["sql_sha256"] or len(data) != entry["sql_size"]:
        raise RuntimeError(
            f"manifest hash binding violated for {entry['migration_id']} "
            f"({entry['source_path']}): sha256 {digest} vs pinned "
            f"{entry['sql_sha256']} - regenerate the manifest first")
    return data


_TIMING_KEYS = {"exec_seconds", "translate_seconds"}


def sanitize_summary(summary: dict) -> dict:
    """Strip wall-clock timings (the only non-deterministic fields) so
    the baseline is byte-stable across runs (append-only discipline)."""
    out: dict = {}
    for key, value in summary.items():
        if key in _TIMING_KEYS:
            continue
        out[key] = (sanitize_summary(value) if isinstance(value, dict)
                    else value)
    return out


def apply_seed_augments(
        per_db_statements: dict[str, list[str]],
        report: "translator.TranslationReport",
        manifest_sha256: str | None = None) -> int:
    """Append the per-database seed augmentations: the world binary
    builds the equipment/random-item
    caches on first boot (~4 minutes of world boot) and persists them
    into ai_playerbot_* tables that later boots load. Shipping a
    captured build in the seed erases the first-boot cost entirely.
    Format is one INSERT statement per line (blank lines and --
    comments ignored), sqlite dialect, applied to the database named
    by the file; files may be plain .sql or gzip .sql.gz (the large
    capture exceeds the 100 MB blob push limit uncompressed). The
    augmentation directory is append-only input pinned by
    the baseline like every other transcript byte, and PROVENANCE.json
    binds the capture to the migrations manifest it was taken against:
    a manifest advance fails loudly until the capture is deliberately
    re-validated (the world's cache-load branches have no version
    check of their own - this guard is the only staleness tripwire).
    Returns the number of statements appended. Shared by seed() and
    the test suite's inline pipeline copies so both stay on one code
    path."""
    augment_dir = AUGMENT_DIR
    if not augment_dir.is_dir():
        return 0
    provenance = augment_dir / "PROVENANCE.json"
    if provenance.is_file() and manifest_sha256 is not None:
        import json
        pinned = json.loads(provenance.read_text(encoding="utf-8"))
        if pinned.get("manifest_sha256") != manifest_sha256:
            raise RuntimeError(
                "seed-augment/PROVENANCE.json: the capture was taken "
                f"against manifest {str(pinned.get('manifest_sha256'))[:16]}... "
                f"but the current manifest is {manifest_sha256[:16]}... - "
                "the item corpus or build semantics may have changed. "
                "Re-capture the caches from a fresh first-boot build "
                "(and update PROVENANCE + --write-baseline) or drop the "
                "augmentation; never re-fold a stale capture silently.")
    appended_total = 0
    for augment in sorted(list(augment_dir.glob("*.sql"))
                          + list(augment_dir.glob("*.sql.gz"))):
        db_name = augment.name[:-len(".sql.gz")] if augment.name.endswith(
            ".sql.gz") else augment.stem
        if db_name not in per_db_statements:
            raise RuntimeError(
                f"seed-augment/{augment.name}: no manifest entries for "
                f"database '{db_name}' - augmentation targets an "
                f"unknown database")
        appended = 0
        text = (gzip.decompress(augment.read_bytes()).decode("utf-8")
                if augment.name.endswith(".sql.gz")
                else augment.read_text(encoding="utf-8"))
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            if not stripped.upper().startswith("INSERT INTO"):
                raise RuntimeError(
                    f"seed-augment/{augment.name}: non-INSERT line "
                    f"rejected (one INSERT per line, sqlite dialect): "
                    f"{stripped[:80]}")
            # The transcript writer joins statements with ";\n" itself -
            # an augmentation line carrying its own terminator would emit
            # ";;" and the pinned-engine replay dies on the empty
            # statement between them (sqlite_exec_file boundaries).
            if stripped.endswith(";"):
                stripped = stripped[:-1].rstrip()
            if ";" in stripped:
                raise RuntimeError(
                    f"seed-augment/{augment.name}: embedded semicolon "
                    f"rejected (one single INSERT statement per line): "
                    f"{stripped[:80]}")
            per_db_statements[db_name].append(stripped)
            appended += 1
        report.statements += appended
        appended_total += appended
    return appended_total


def seed(out_dir: Path, transcripts_dir: Path | None,
         write_baseline: bool, max_errors: int,
         manifest_path: Path = MANIFEST) -> tuple[int, dict]:
    manifest_bytes = manifest_path.read_bytes()
    # Hash-bind the baseline to the exact manifest state it was seeded
    # from: the seed outputs already bind it transitively via per-entry
    # sql_sha256, but an explicit hash makes diagnosing a baseline
    # mismatch O(1) instead of a diff hunt.
    # LF-normalized input: the repo's .gitattributes canonical
    # form is LF, so a fresh LF checkout must hash identically to this
    # CRLF working tree.
    manifest_sha256 = hashlib.sha256(
        manifest_bytes.replace(b"\r\n", b"\n")).hexdigest()
    manifest = json.loads(manifest_bytes.decode("utf-8"))
    report = translator.TranslationReport()
    variables: dict[str, str] = {}
    per_db_statements: dict[str, list[str]] = {}
    # per-database schema tracking: positional ADD COLUMN needs
    # the table's effective column order; namespaces must not mix.
    db_states: dict[str, translator.DBSchemaState] = {}
    started = time.time()

    for entry in manifest["entries"]:
        data = entry_bytes(entry)
        # STRICT decode: errors="replace" would silently freeze U+FFFD
        # mojibake as deterministic, baseline-pinned data.
        text = data.decode("utf-8")
        db_name = entry["database"]
        stmts = translator.translate_file_text(
            text, entry["source_path"], report, variables,
            db_state=db_states.setdefault(db_name,
                                          translator.DBSchemaState()))
        per_db_statements.setdefault(db_name, []).extend(stmts)

    # Precomputed playerbot runtime caches: see
    # apply_seed_augments - the shared helper keeps seed() and the test
    # suite's inline pipeline copies on one code path.
    apply_seed_augments(per_db_statements, report, manifest_sha256)

    translate_seconds = time.time() - started

    # Execute each database, fail-loud.
    out_dir.mkdir(parents=True, exist_ok=True)
    stale_dump = out_dir / "failed-statements.sql"
    if stale_dump.exists():
        stale_dump.unlink()
    results: dict[str, dict] = {}
    errors_total = 0
    for db_name, stmts in per_db_statements.items():
        db_path = out_dir / f"{db_name}.sqlite"
        for suffix in ("-wal", "-shm", "-journal"):
            side = db_path.with_name(db_path.name + suffix)
            if side.exists():
                side.unlink()
        if db_path.exists():
            db_path.unlink()
        conn = sqlite3.connect(str(db_path))
        applied = 0
        errors: list[str] = []
        try:
            conn.execute("PRAGMA foreign_keys=OFF")
            conn.execute("PRAGMA journal_mode=DELETE")
            cur = conn.cursor()
            exec_started = time.time()
            for i, stmt in enumerate(stmts):
                try:
                    cur.execute(stmt)
                    applied += 1
                except sqlite3.Error as exc:
                    preview = stmt if len(stmt) <= 100 else stmt[:97] + "..."
                    errors.append(f"[{db_name}#{i}] {exc}: {preview}")
                    dump = out_dir / "failed-statements.sql"
                    # newline="" (gotcha #14): failed statements can
                    # carry literal CR/LF from escape-rewritten data;
                    # the forensic dump must show the TRUE bytes.
                    with dump.open("a", encoding="utf-8", newline="") as fh:
                        fh.write(f"-- [{db_name}#{i}] {exc}\n{stmt}\n;\n")
                    if len(errors) > max_errors:
                        conn.rollback()
                        raise RuntimeError(
                            f"seed failed: more than {max_errors} errors; "
                            f"first:\n" + "\n".join(errors[:5]))
            conn.commit()
            exec_seconds = time.time() - exec_started
            row_count = 0
            table_count = 0
            for (name,) in cur.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' "
                    "AND name NOT LIKE 'sqlite_%'").fetchall():
                table_count += 1
                row_count += cur.execute(
                    f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
        finally:
            conn.close()
        errors_total += len(errors)
        results[db_name] = {
            "statements": len(stmts),
            "applied": applied,
            "errors": len(errors),
            "tables": table_count,
            "rows": row_count,
            "exec_seconds": round(exec_seconds, 1),
            "error_samples": errors[:10],
        }
        if transcripts_dir:
            transcripts_dir.mkdir(parents=True, exist_ok=True)
            # newline="" - NO newline translation: statements can contain
            # literal CR/LF control chars (escape-rewritten MySQL data),
            # and text-mode writes on Windows would corrupt them
            # lossily. The transcripts must be byte-exact for the
            # pinned-engine replay leg.
            with (transcripts_dir / f"{db_name}.sql").open(
                    "w", encoding="utf-8", newline="") as fh:
                fh.write(";\n".join(stmts) + ";\n")

    summary = {
        "entries": len(manifest["entries"]),
        "manifest_sha256": manifest_sha256,
        "databases": results,
        "translation": report.as_dict(),
        "translate_seconds": round(translate_seconds, 1),
        "variables_unfolded": sorted(k for k, v in variables.items()
                                     if v is None),
    }
    if transcripts_dir:
        # Hash-bind the transcript BYTES (the APK seed assets): the
        # count-level baseline alone cannot detect count-preserving
        # content drift. Sizes pin the both-metric seed
        # footprint alongside the digests.
        summary["transcript_digests"] = {
            db: hashlib.sha256(
                (transcripts_dir / f"{db}.sql").read_bytes()).hexdigest()
            for db in per_db_statements
        }
        summary["transcript_sizes"] = {
            db: (transcripts_dir / f"{db}.sql").stat().st_size
            for db in per_db_statements
        }
    print(json.dumps({k: v for k, v in summary.items()
                      if k != "translation"}, indent=2))
    print("translation report:", json.dumps(summary["translation"], indent=2)[:2000])

    ok = (errors_total == 0
          and summary["translation"]["statements"] > 0
          and not summary["variables_unfolded"])
    if write_baseline:
        if manifest_path != MANIFEST:
            # a synthetic/foreign manifest must never be able to
            # overwrite the append-only baseline
            raise RuntimeError(
                "--write-baseline refused: manifest_path is not the "
                "real migration manifest")
        if not ok:
            raise RuntimeError(
                "--write-baseline refused: seed not clean (a failed run "
                "must never clobber the append-only baseline)")
        # newline="\n": the artifact's bytes must not differ
        # between Windows and Linux regenerations (gotcha #14 class).
        with BASELINE.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(sanitize_summary(summary), indent=2)
                     + "\n")
        print(f"baseline written: {BASELINE}")

    print("SEED " + ("OK" if ok else "FAILED"))
    return (0 if ok else 1), summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", required=True, help="output directory for the .sqlite files")
    ap.add_argument("--transcripts", help="also write per-database .sql transcripts here")
    ap.add_argument("--write-baseline", action="store_true",
                    help="write schemas/sqlite-seed-baseline.json (deterministic)")
    ap.add_argument("--max-errors", type=int, default=0,
                    help="abort after N statement errors (default 0: fail on first)")
    args = ap.parse_args()
    transcripts = Path(args.transcripts) if args.transcripts else None
    rc, _summary = seed(Path(args.out), transcripts, args.write_baseline,
                        args.max_errors)
    return rc


if __name__ == "__main__":
    sys.exit(main())
