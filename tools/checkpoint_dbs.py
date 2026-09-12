"""Checkpoint the SQLite WALs (after a hard world kill) so the next boot
starts from a clean, consistent database state."""
import os
import sqlite3
from pathlib import Path

root = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "database" / "sqlite-datadir"
for name in ("classicrealmd", "classicmangos", "classiccharacters", "classiclogs"):
    path = root / f"{name}.sqlite3"
    conn = sqlite3.connect(str(path))
    mode, log, ckpt = conn.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
    integrity = conn.execute("PRAGMA quick_check").fetchone()[0]
    print(f"{name}: checkpoint mode={mode} wal={log} -> {ckpt} integrity={integrity}")
    conn.close()
print("done")
