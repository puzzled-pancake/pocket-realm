"""One-shot: delete the Davos test character and its side tables."""
import os
import sqlite3
from pathlib import Path

root = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "database" / "sqlite-datadir"
conn = sqlite3.connect(str(root / "classiccharacters.sqlite3"))
tables = [r[0] for r in conn.execute(
    "SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
row = conn.execute(
    "SELECT guid, name, position_x, position_y, position_z, map "
    "FROM characters WHERE name='Davos'").fetchone()
print("Davos row:", row)
if row:
    guid = row[0]
    conn.execute("DELETE FROM characters WHERE name='Davos'")
    for tbl in ("character_inventory", "character_aura", "character_spell",
                "character_action", "character_homebind",
                "character_queststatus", "character_social"):
        if tbl in tables:
            try:
                conn.execute(f"DELETE FROM {tbl} WHERE guid = ?", (guid,))
                print("cleaned", tbl)
            except Exception as exc:
                print(tbl, "skip:", exc)
    conn.commit()
    print("Davos deleted")
conn.close()
