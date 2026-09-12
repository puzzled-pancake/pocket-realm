"""Desktop RP host driver: spawn `gradlew rpHost`, speak JSON-line ops.

The desktop twin of RelaySession's op surface (world-chat,
llm-memory-state, reset-state, world-account) - no adb, the ops are JNI
calls inside the rpHost JVM and the protocol is one JSON object per
stdin line -> one JSON object per stdout line (events carry "event",
responses carry "op"). EOF/stop drains the stack with a save.

Usage:
    host = DesktopHost(REPO_ROOT)
    host.wait_ready(timeout_s=900)
    host.account("rptest", "rptestpass", gm=3)
    answer = host.chat("Seth", "whisper", "Varleigh", "Hail, friend")
    ...
    host.close()
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path


class DesktopHostError(RuntimeError):
    """The host died or an op round trip failed."""


class DesktopHost:
    """One rpHost JVM; strictly serial request/response over stdio.

    Protocol lines are `@@RP@@{...}`; every other stdout row (gradle
    banners, native prints) is noise and is dropped.
    """

    PREFIX = "@@RP@@"

    def __init__(self, repo_root: str | Path, *, client: bool = False,
                 client_dir: str | None = None, account: str | None = None,
                 password: str | None = None, bots: int = 0,
                 stderr_path: str | Path | None = None,
                 workdir: str | Path | None = None) -> None:
        self.repo = Path(repo_root)
        self.desktop = self.repo / "desktop"
        self.workdir = Path(workdir) if workdir else self.repo / "build" / "rp-desktop"
        self.workdir.mkdir(parents=True, exist_ok=True)
        if stderr_path is None:
            stderr_path = self.workdir / "rphost-stderr.log"
        self.stderr_path = Path(stderr_path)
        argv = [str(self.desktop / "gradlew.bat"), "rpHost"]
        # clientDir rides the staged settings (space-bearing Windows paths
        # do not survive gradlew.bat/cmd quoting); only the boolean/number
        # flags go on the command line.
        if client:
            argv.append("-PrpClient=1")
        if account:
            argv.append(f"-PrpAccount={account}")
        if password:
            argv.append(f"-PrpPassword={password}")
        if bots > 0:
            argv.append(f"-PrpBots={bots}")
        env = dict(os.environ)
        # The no-manifest 1.12 client trips Windows' installer-detection
        # heuristic and comes up ELEVATED; UIPI then eats every synthetic
        # input from a non-elevated driver. RUNASINVOKER disables the
        # heuristic for the whole process tree (rpHost JVM -> WoW.exe).
        env["__COMPAT_LAYER"] = "RUNASINVOKER"
        self.stderr_file = self.stderr_path.open("w", encoding="utf-8")
        self.proc = subprocess.Popen(
            argv, cwd=str(self.desktop), stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=self.stderr_file,
            text=True, encoding="utf-8", errors="replace", bufsize=1, env=env)
        self.events: list[dict] = []
        self._lines: queue.Queue[str | None] = queue.Queue()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()
        self._serial = threading.Lock()

    # ---- internals -------------------------------------------------------

    def _read_loop(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            if line.startswith(self.PREFIX):
                self._lines.put(line[len(self.PREFIX):])
        self._lines.put(None)

    def _next_line(self, deadline: float) -> str | None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            return self._lines.get(timeout=remaining)
        except queue.Empty:
            return None

    # ---- lifecycle -------------------------------------------------------

    def wait_ready(self, timeout_s: float = 900.0) -> dict:
        """Block until the ready event (world READY); client event follows."""
        deadline = time.monotonic() + timeout_s
        while True:
            line = self._next_line(deadline)
            if line is None:
                raise DesktopHostError(
                    f"rpHost never reported ready within {timeout_s:.0f}s "
                    f"(see {self.stderr_path})")
            value = json.loads(line)
            self.events.append(value)
            if value.get("event") == "ready":
                return value
            if value.get("event") == "client" and value.get("state") != "READY":
                raise DesktopHostError(f"client start failed: {value}")

    def alive(self) -> bool:
        return self.proc.poll() is None

    def close(self, timeout_s: float = 120.0) -> None:
        """Best-effort drain: stop op, then EOF, then terminate."""
        try:
            if self.alive():
                self.send_raw({"op": "stop"}, timeout_s=timeout_s)
        except (DesktopHostError, OSError, ValueError):
            pass
        try:
            if self.alive():
                assert self.proc.stdin is not None
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.stderr_file.close()

    # ---- ops ---------------------------------------------------------------

    def send_raw(self, request: dict, timeout_s: float = 180.0) -> dict:
        with self._serial:
            if not self.alive():
                raise DesktopHostError(
                    f"rpHost exited (code {self.proc.poll()}); see {self.stderr_path}")
            assert self.proc.stdin is not None
            self.proc.stdin.write(json.dumps(request) + "\n")
            self.proc.stdin.flush()
            deadline = time.monotonic() + timeout_s
            while True:
                line = self._next_line(deadline)
                if line is None:
                    raise DesktopHostError(
                        f"op {request.get('op')} timed out after {timeout_s:.0f}s")
                value = json.loads(line)
                if "event" in value:
                    self.events.append(value)
                    continue
                return value

    # typed helpers over the same op verbs the Android relay serves
    def hello(self) -> dict:
        return self.send_raw({"op": "hello"})

    def online(self) -> int:
        return int(self.send_raw({"op": "online"}).get("online") or 0)

    def botstatus(self) -> dict:
        return self.send_raw({"op": "botstatus"})

    def bots(self, target: int) -> dict:
        return self.send_raw({"op": "bots", "target": target})

    def account(self, user: str, password: str, gm: int = -1) -> dict:
        request: dict = {"op": "account", "user": user, "pass": password}
        if gm >= 0:
            request["gm"] = gm
        return self.send_raw(request)

    def chat(self, char: str, channel: str, target: str = "", text: str = "",
             timeout_s: float = 180.0) -> dict:
        return self.send_raw(
            {"op": "chat", "char": char, "channel": channel,
             "target": target, "text": text}, timeout_s=timeout_s)

    def memory(self, player: str = "") -> dict:
        return self.send_raw({"op": "memory", "player": player})

    def reset(self, player: str = "") -> dict:
        return self.send_raw({"op": "reset", "player": player})

    def save(self) -> dict:
        return self.send_raw({"op": "save"})

    def autologin(self, user: str, password: str) -> dict:
        return self.send_raw({"op": "autologin", "user": user, "pass": password})
