"""Relay session: the adb link + the console-in/out round trip.

Reuses tools/world_console.py's mechanics (adb root, push of
filesDir/console-in.json with the app uid, poll of
filesDir/console-out.json, pull) and adds:

* the world-endpoint forward (`adb forward tcp:3724 tcp:8085`) that the
  protocol-client slice (suites/realmd_auth.py) attaches to, plus a
  health check through the relay's own `ping` op;
* first-class reconnect/backoff transcript events - an adb hiccup is a
  recorded event with its attempt count and backoff delay, never a silent
  retry, so a flaky link fails the smoke loudly instead of looking like a
  server problem.

The session records every op round trip (`op_send` / `op_result` /
`op_timeout`) into a Transcript (protocol.py).
"""
from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path

from .protocol import Transcript, event

APP_FILES = "/data/data/com.pocketrealm/files"
IN_PATH = f"{APP_FILES}/console-in.json"
OUT_PATH = f"{APP_FILES}/console-out.json"
WORLD_LOG_PATH = f"{APP_FILES}/logs/world.log"
INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"
RELAY_CLASS = "com.pocketrealm.database.WorldConsoleRelay"

# The world listener's endpoint on device is 8085; 3724 is the host-side
# alias the protocol-client slice (realmd_auth) dials.
FORWARD_LOCAL_PORT = 3724
FORWARD_REMOTE_PORT = 8085

RELAY_STARTUP_TIMEOUT_S = 180.0


class RelayError(RuntimeError):
    """A relay round trip failed (timeout, adb failure, bad response)."""


def _default_runner(argv: list[str], timeout: float) -> str:
    completed = subprocess.run(
        argv, capture_output=True, text=True, timeout=timeout)
    if completed.returncode != 0:
        raise RelayError(
            f"{argv[0]} failed ({completed.returncode}): {completed.stderr.strip()}")
    return completed.stdout


class RelaySession:
    """One adb-linked relay session against a device.

    `runner` is injectable so the host unit battery can drive a fake adb.
    """

    def __init__(self, *, adb: str = "adb", device: str | None = None,
                 runner=None, transcript: Transcript | None = None,
                 workdir: str | Path | None = None,
                 poll_interval_s: float = 1.0, attempts: int = 3) -> None:
        self.adb = adb
        self.device = device
        self.runner = runner or _default_runner
        self.transcript = transcript if transcript is not None else Transcript()
        self.workdir = Path(workdir) if workdir else Path("build") / "rp-harness"
        self.workdir.mkdir(parents=True, exist_ok=True)
        self.poll_interval_s = poll_interval_s
        self.attempts = attempts
        self._forwarded = False

    # ---- adb plumbing -------------------------------------------------

    def adb_args(self, *args: str) -> list[str]:
        argv = [self.adb]
        if self.device:
            argv += ["-s", self.device]
        return argv + list(args)

    def run(self, *args: str, timeout: float = 60.0, check: bool = True) -> str:
        """One adb invocation; failures raise RelayError."""
        argv = self.adb_args(*args)
        try:
            return self.runner(argv, timeout)
        except RelayError:
            if not check:
                return ""
            raise

    def adb_root(self) -> None:
        # adb root restarts the daemon; failures are tolerated (some
        # production builds refuse) and surface at the first push.
        self.run("root", timeout=60.0, check=False)
        time.sleep(4)
        self.run("wait-for-device", timeout=120.0)
        self.transcript.record("adb_rooted")

    def ensure_forward(self) -> None:
        """Own `adb forward tcp:3724 tcp:8085` (the protocol-client lane)."""
        self.run("forward", f"tcp:{FORWARD_LOCAL_PORT}",
                 f"tcp:{FORWARD_REMOTE_PORT}", timeout=30.0)
        self._forwarded = True
        self.transcript.record(
            "forward", local=FORWARD_LOCAL_PORT, remote=FORWARD_REMOTE_PORT)

    def start_relay(self) -> None:
        """(Re)start the long-lived WorldConsoleRelay instrumentation."""
        self.adb_root()
        for path in (IN_PATH, OUT_PATH):
            self.run("shell", "rm", "-f", path, timeout=30.0, check=False)
        # non-blocking: the instrumentation stays resident as the console's
        # service host (a -w invocation would block and hold the terminal)
        self.run("shell", "am", "instrument", "-e", "class", RELAY_CLASS,
                 INSTRUMENT, timeout=120.0)
        self.transcript.record("relay_started")

    def connect(self) -> dict:
        """adb root -> relay start -> forward -> health check."""
        self.start_relay()
        self.ensure_forward()
        return self.health()

    def health(self, timeout_s: float = RELAY_STARTUP_TIMEOUT_S) -> dict:
        """Round-trip the relay `ping` op; records a health event."""
        response = self.send("ping", timeout_s=timeout_s)
        ok = bool(response.get("ok")) and bool(response.get("alive"))
        self.transcript.record("health", ok=ok, uptimeMs=response.get("uptimeMs"))
        if not ok:
            raise RelayError(f"relay health check failed: {response}")
        return response

    def pull_world_log(self, dest: str | Path) -> Path:
        """Pull the :world process log for the A8 invariant post-pass."""
        target = Path(dest)
        target.parent.mkdir(parents=True, exist_ok=True)
        self.run("pull", WORLD_LOG_PATH, str(target), timeout=120.0)
        return target

    # ---- the console-in/out round trip ---------------------------------

    def _app_uid(self) -> str:
        return self.run("shell", "stat", "-c", "%u:%g",
                        "/data/data/com.pocketrealm", timeout=60.0).strip()

    def send(self, op: str, arg1: str = "", arg2: str = "", arg3: str = "",
             arg4: str = "", timeout_s: float = 120.0) -> dict:
        """One relay op: push the command json, poll the out file, parse.

        Retries the whole round trip on adb-level failures with a recorded
        backoff (a device hiccup is a reconnect event, never a silent
        retry). A relay timeout (no out file within `timeout_s`) raises
        RelayError after recording `op_timeout`.
        """
        payload = json.dumps({"op": op, "arg1": arg1, "arg2": arg2,
                              "arg3": arg3, "arg4": arg4})
        last_error: Exception | None = None
        for attempt in range(1, self.attempts + 1):
            started = time.monotonic()
            self.transcript.record("op_send", op=op, attempt=attempt,
                                   arg1=arg1, arg2=arg2, arg3=arg3, arg4=arg4)
            try:
                response = self._round_trip(payload, timeout_s)
            except (RelayError, subprocess.TimeoutExpired) as error:
                # round-8 R7: a hung adb (subprocess.TimeoutExpired from
                # the runner) is a RELAY failure like any other -
                # normalize it so the retry/backoff + transcript
                # machinery sees one error class (it used to escape
                # send() uncaught, bypassing the suite's documented
                # exit-2 harness-error path).
                if not isinstance(error, RelayError):
                    error = RelayError(f"adb round trip timed out: {error}")
                last_error = error
                backoff = min(2.0 ** attempt, 8.0)
                self.transcript.record(
                    "reconnect", op=op, attempt=attempt, backoff_s=backoff,
                    error=str(error))
                if attempt < self.attempts:
                    time.sleep(backoff)
                continue
            self.transcript.record(
                "op_result", op=op, attempt=attempt, ok=bool(response.get("ok")),
                elapsed_ms=round((time.monotonic() - started) * 1000.0, 3),
                response=response)
            return response
        self.transcript.record(
            "op_timeout", op=op, attempts=self.attempts, error=str(last_error))
        raise RelayError(f"relay op '{op}' failed after {self.attempts} attempts: "
                         f"{last_error}")

    def _round_trip(self, payload: str, timeout_s: float) -> dict:
        local = self.workdir / "console-in.json"
        local.write_text(payload, encoding="utf-8")
        app_uid = self._app_uid()
        self.run("shell", "rm", "-f", OUT_PATH, timeout=30.0, check=False)
        self.run("push", str(local), IN_PATH, timeout=60.0)
        self.run("shell", "chown", app_uid, IN_PATH, timeout=30.0)
        self.run("shell", "chmod", "644", IN_PATH, timeout=30.0)
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            raw = self.run("shell", "cat", OUT_PATH, timeout=30.0, check=False)
            if raw.strip().startswith("{"):
                return json.loads(raw)
            time.sleep(self.poll_interval_s)
        raise RelayError(f"relay did not answer within {timeout_s:.0f}s")
