#!/usr/bin/env python3
"""Block a small set of irreversible repository commands; allow ordinary build cleanup."""
import json
import re
import sys

try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(0)

command = str(payload.get("tool_input", {}).get("command", ""))
patterns = {
    r"\bgit\s+reset\s+--hard\b": "Use a new commit or restore specific files; do not discard unknown work.",
    r"\bgit\s+clean\s+[^\n;]*-[^\n;]*f": "Review untracked files explicitly before deleting them.",
    r"\bgit\s+push\b[^\n;]*(--force|-f\b)": "Force-push is outside autonomous local implementation.",
    r"\bgit\s+(checkout\s+--|restore\b[^\n;]*)\s+\.?/?(\s|$)": "Do not discard the working tree wholesale; restore named files only after inspection.",
    r"\brm\s+-[^\n;]*r[^\n;]*f[^\n;]*\s+(/|~|\$HOME)(\s|$)": "Refusing recursive deletion of a root/home path.",
    r"\brm\s+-[^\n;]*r[^\n;]*f[^\n;]*\s+(\./?|\*)(\s|$)": "Refusing recursive deletion of the repository working tree.",
    r"\brm\s+-[^\n;]*r[^\n;]*f[^\n;]*\s+\.git(\s|/|$)": "Refusing deletion of repository history.",
    r"\b(drop\s+database|drop\s+table)\b": "Use a disposable test database or an explicit migration instead.",
}

for pattern, reason in patterns.items():
    if re.search(pattern, command, flags=re.IGNORECASE):
        print(f"Blocked destructive command: {reason}", file=sys.stderr)
        sys.exit(2)

sys.exit(0)
