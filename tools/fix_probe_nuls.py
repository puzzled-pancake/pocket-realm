"""One-shot: escape raw NUL bytes in a python source file, properly."""
import sys
from pathlib import Path

p = Path(sys.argv[1])
data = p.read_bytes()
out = bytearray()
i = 0
while i < len(data):
    if data[i] == 0:
        out += b"\\x00"          # backslash, x, 0, 0 - four ASCII chars
        i += 1
    else:
        out.append(data[i])
        i += 1
p.write_bytes(bytes(out))
print("nulls before:", data.count(0), "after:", p.read_bytes().count(0))
