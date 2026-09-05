"""Event-record helpers for the RP harness transcript.

Every event carries BOTH clocks: `mono_ms` (a ms-resolution monotonic
reading, `time.monotonic()*1000`) for latency math that survives wall-clock
adjustments, and `ts` (an ISO-8601 UTC wall-clock stamp) for correlating a
transcript line against device-side logs (world.log) after reconnects or
clock skew. The transcript is JSONL, one event per line.
"""
from __future__ import annotations

import json
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


def mono_ms() -> float:
    """Ms-resolution monotonic clock (never goes backwards)."""
    return time.monotonic() * 1000.0


def wall_ts() -> str:
    """ISO-8601 UTC wall-clock stamp with ms precision."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def event(kind: str, **fields) -> dict:
    """Build one transcript event record."""
    record = {"kind": kind, "mono_ms": round(mono_ms(), 3), "ts": wall_ts()}
    record.update(fields)
    return record


def make_sentinel(prefix: str = "rptest") -> str:
    """A per-run distinctive token.

    The smoke's whisper text carries one; `assertions.no_echo_leak` then
    proves no bot line parrots it back (the injection-hygiene regression).
    Alphanumeric only - the world-chat op rejects non-printable text.
    """
    return f"{prefix}{uuid.uuid4().hex[:8]}"


class Transcript:
    """Append-only event list with JSONL persistence."""

    def __init__(self) -> None:
        self.events: list[dict] = []

    def append(self, record: dict) -> dict:
        self.events.append(record)
        return record

    def record(self, kind: str, **fields) -> dict:
        """event() + append in one step."""
        return self.append(event(kind, **fields))

    def by_kind(self, kind: str) -> list[dict]:
        return [e for e in self.events if e.get("kind") == kind]

    def since(self, mono_ms_value: float, kind: str | None = None) -> list[dict]:
        """Events at or after a monotonic stamp (optionally one kind)."""
        return [e for e in self.events
                if e.get("mono_ms", 0.0) >= mono_ms_value
                and (kind is None or e.get("kind") == kind)]

    def write_jsonl(self, path: str | Path) -> Path:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("w", encoding="utf-8") as handle:
            for record in self.events:
                handle.write(json.dumps(record, sort_keys=True) + "\n")
        return target

    @classmethod
    def load_jsonl(cls, path: str | Path) -> "Transcript":
        transcript = cls()
        with Path(path).open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    transcript.events.append(json.loads(line))
        return transcript
