"""One-shot: create the Davos test character row directly (level 1 human
warrior at the Northshire spawn), cloning the column layout of an
existing bot row. The in-client create path loses its async write when
it races the random-bot loader's open transaction."""
import os
import sqlite3
from pathlib import Path

root = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "database" / "sqlite-datadir"
auth = sqlite3.connect(str(root / "classicrealmd.sqlite3"))
acct = auth.execute("SELECT id FROM account WHERE username='RPTEST'").fetchone()[0]
auth.close()
conn = sqlite3.connect(str(root / "classiccharacters.sqlite3"))
cols = [r[1] for r in conn.execute("PRAGMA table_info(characters)").fetchall()]
sample = conn.execute(
    "SELECT * FROM characters WHERE level = 1 AND race = 1 ORDER BY guid DESC LIMIT 1").fetchone()
if not sample:
    sample = conn.execute("SELECT * FROM characters ORDER BY guid DESC LIMIT 1").fetchone()
row = dict(zip(cols, sample))
print("cloning layout from:", row["guid"], row["name"], "level", row["level"])

# a fresh guid: one above the current max
new_guid = conn.execute("SELECT MAX(guid)+1 FROM characters").fetchone()[0]
row["guid"] = new_guid
row["name"] = "Davos"
row["account"] = acct
row["level"] = 1
# NorthshireAbbey spawn, map 0 (Eastern Kingdoms)
row["position_x"] = -8913.0
row["position_y"] = -133.0
row["position_z"] = 81.0
row["orientation"] = 0.0
row["map"] = 0
row["zone"] = 12
row["online"] = 0
row["totaltime"] = 0
row["leveltime"] = 0
row["resetTalentsCost"] = 0
row["resetTalentsTime"] = 0
row["taximask"] = b""
row["taxipath"] = 0
conn.execute(
    f"INSERT INTO characters ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
    [row[c] for c in cols],
)
conn.commit()
check = conn.execute("SELECT guid, name, account, level, map FROM characters WHERE name='Davos'").fetchone()
print("created:", check)
conn.close()
