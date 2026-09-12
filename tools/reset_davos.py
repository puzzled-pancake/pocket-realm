"""Reset the Davos test character to a fresh Northshire spawn."""
import os
import sqlite3
from pathlib import Path

root = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "database" / "sqlite-datadir"

# remove any prior row plus its side tables
conn = sqlite3.connect(str(root / "classiccharacters.sqlite3"))
tables = [r[0] for r in conn.execute(
    "SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
row = conn.execute(
    "SELECT guid FROM characters WHERE name='Davos'").fetchone()
if row:
    guid = row[0]
    conn.execute("DELETE FROM characters WHERE name='Davos'")
    for tbl in ("character_inventory", "character_aura", "character_spell",
                "character_action", "character_homebind",
                "character_queststatus", "character_social"):
        if tbl in tables:
            try:
                conn.execute(f"DELETE FROM {tbl} WHERE guid = ?", (guid,))
            except Exception:
                pass
    conn.commit()
    print("old Davos removed")

# recreate at the Northshire Abbey spawn, cloning a valid level-1 layout
auth = sqlite3.connect(str(root / "classicrealmd.sqlite3"))
acct = auth.execute("SELECT id FROM account WHERE username='RPTEST'").fetchone()[0]
auth.close()
cols = [r[1] for r in conn.execute("PRAGMA table_info(characters)").fetchall()]
sample = conn.execute(
    "SELECT * FROM characters WHERE level = 1 AND race = 1 ORDER BY guid DESC LIMIT 1").fetchone()
if not sample:
    sample = conn.execute("SELECT * FROM characters ORDER BY guid DESC LIMIT 1").fetchone()
new_row = dict(zip(cols, sample))
new_guid = conn.execute("SELECT MAX(guid)+1 FROM characters").fetchone()[0]
new_row["guid"] = new_guid
new_row["name"] = "Davos"
new_row["account"] = acct
new_row["level"] = 1
new_row["position_x"] = -8913.0
new_row["position_y"] = -133.0
new_row["position_z"] = 81.0
new_row["orientation"] = 0.0
new_row["map"] = 0
new_row["zone"] = 12
new_row["online"] = 0
new_row["totaltime"] = 0
new_row["leveltime"] = 0
conn.execute(
    f"INSERT INTO characters ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
    [new_row[c] for c in cols],
)
conn.commit()
check = conn.execute(
    "SELECT guid, name, account, level, map, position_z FROM characters WHERE name='Davos'").fetchone()
print("Davos reset:", check)
conn.close()
