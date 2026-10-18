#!/usr/bin/env python3
"""E-drive enforcement gate (COMPLETION-PLAN section 7, W0).

The project's hard rule 1: nothing installs to, caches on, or writes to
`C:`. This gate keeps that true mechanically, in three legs:

1. **Env redirection table** (Windows only): every §7 toolchain variable —
   `GRADLE_USER_HOME`, `PIP_CACHE_DIR`, `RUSTUP_HOME`, `CARGO_HOME`,
   `JAVA_HOME`, `ANDROID_SDK_ROOT`/`ANDROID_HOME` — must be configured
   (user registry and/or live process env) AND resolve under `E:\\`.
   A variable that is unset is a finding too: the Windows defaults for
   these homes all live under `C:\\Users\\...`, so "unset" means "writes
   to C:". The live process env is also compared against the registry:
   a mismatch is a loud warning (daemons started before a redirection
   inherit stale env), never a pass.
2. **C: path-literal sweep**: every committed-or-committable text file
   (`git ls-files --cached --others --exclude-standard`; R1-1/R3-1) except the
   allowlisted classes below) is scanned for `C:\\` / `C:/` path
   literals, so no tool, test, or config silently re-points a lane at
   the dead drive. Allowlisted classes: `docs/` (historical prose),
   vendored upstreams (`native/xserver-winlator/`, `runtime/`), and the
   per-file entries in ALLOWLIST below, each with a reason and retire
   stage. The allowlist is self-checking: a scoped entry whose target
   file no longer contains any C: literal is a stale-entry finding.
3. **Configured cache roots + C:-artifact inventory**: any configured
   toolchain home that
   resolves ONTO `C:` (a redirection pointing the wrong way) is listed
   as a finding.

Fail-closed with actionable codes: `E-ENV-*` (redirection table),
`E-LITERAL` (C: path literal outside the allowlist), `E-STALE` (stale
allowlist entry), `E-CACHE-ON-C` (cache root on C:). Exit 0 = clean,
1 = findings.

Usage:
    python tools/check_e_only.py [--root PATH] [--json]

Non-Windows hosts: leg 1/3 print a loud SKIPPED note and do not pass or
fail (the registry/`E:` contract is machine-local to the build host);
leg 2 (the literal sweep) runs everywhere. This mirrors check_repo's
personal-marker skip precedent — skipped legs are named, never silent.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent

# The redirection contract (COMPLETION-PLAN section 7 table). Each entry:
# variable -> (must-live-under prefix, what-unset-means). ANDROID_HOME and
# ANDROID_SDK_ROOT are one row each but both must agree.
TOOLCHAIN_VARS: tuple[tuple[str, str, str], ...] = (
    ("GRADLE_USER_HOME", "E:\\pocket_realm_toolchain\\gradle-home",
     "unset means Gradle caches to C:\\Users\\<u>\\.gradle"),
    ("PIP_CACHE_DIR", "E:\\pocket_realm_toolchain\\venv\\pip-cache",
     "unset means pip caches to C:\\Users\\<u>\\AppData\\Local\\pip\\Cache"),
    ("RUSTUP_HOME", "E:\\pocket_realm_toolchain\\rust\\rustup",
     "unset means rustup installs toolchains under C:\\Users\\<u>\\.rustup"),
    ("CARGO_HOME", "E:\\pocket_realm_toolchain\\rust\\cargo",
     "unset means cargo caches registries under C:\\Users\\<u>\\.cargo"),
    ("JAVA_HOME", "E:\\pocket_realm_toolchain\\jdk",
     "unset means Gradle picks whatever JVM is on PATH (C:\\Program Files\\...)"),
    ("ANDROID_SDK_ROOT", "E:\\pocket_realm_toolchain\\android-sdk",
     "unset means the Android build resolves the SDK from ANDROID_HOME"),
    ("ANDROID_HOME", "E:\\pocket_realm_toolchain\\android-sdk",
     "unset (with ANDROID_SDK_ROOT set) still resolves via SDK_ROOT; both on C: is a finding"),
)

# Vendored upstream trees (their own repos gate their content) and
# machine-local files never swept for literals.
EXCLUDED_PREFIXES: tuple[str, ...] = (
    "docs/",          # historical prose allowlist (section 7: "docs allowlisted")
    "native/xserver-winlator/",
    "runtime/",
    "out/",           # archived evidence, not buildable config
)

# A C: path literal: drive letter, then a separator (real or escaped-in-source
# backslash — the file bytes carry `C:\\` as two literal backslashes, which the
# backslash. The lookbehind keeps prose like "tbc:" inside a message
# string - a letter can never precede a drive letter - from matching.)
# The git-bash spelling (`/c/users/...`) is the same C: path in POSIX
# form — R3-r2-1 caught a staged script carrying it behind the old
# regex. The lookbehind keeps prose like "tbc:" from matching.
# The git-bash spelling (`/c/users/...`) is the same C: path in POSIX
# form - R3-r2-1 caught a staged script carrying it behind the old
# regex. The two-segment requirement (`/c/<dir>/`) keeps base64
# payloads (cacert.pem) and prose (`//c/...`) from false-matching; the
# lookbehind keeps prose like "tbc:" from matching.
C_LITERAL_RE = re.compile(r"(?<![A-Za-z0-9])([Cc]:[\\/]|/[Cc]/[A-Za-z0-9._-]+/)")

# literals == None exempts the whole file; the gate itself never uses scoped
# literal subsets because a C: path is either proven-prose/fixture or a
# finding. Each entry names why the file may mention C: paths and when the
# entry retires.
ALLOWLIST: tuple[dict[str, str], ...] = (
    {
        "path": "tools/check_e_only.py",
        "reason": "this gate owns the redirection table and the C: literal patterns",
        "retires": "never (owning table)",
    },
    {
        "path": "tests/test_build_toolchain_residency.py",
        "reason": (
            "W5 R3-r6-2: the win warm-dir residency pin freezes the D76-DISCLOSED "
            "C:-resident MSVC/WinGet toolchain identities as a drift baseline — the "
            "literals ARE the recorded disclosure (any NEW identity fails the pin); "
            "the win toolchain relocation itself is owner-owned (D76, W1)"
        ),
        "retires": "when the owner relocates the win toolchain to E: and the baseline "
                   "is re-derived E:-resident (the pin then scans for E: records)",
    },
    {
        "path": "tests/test_check_e_only.py",
        "reason": "the gate's own tests construct C: literals by design to prove detection",
        "retires": "never (owning-table test)",
    },
    {
        "path": "desktop/build.gradle.kts",
        "reason": (
            "SystemRoot env-lookup fallback string C:\\Windows: reads the OS's own "
            "system root location; not a project write target"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
    {
        "path": "scripts/build_win_deps.py",
        "reason": (
            "PROGRAMFILES(X86) env-lookup fallback: locates the host MSVC/Windows SDK "
            "where Windows itself installed it; not a project write target"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
    {
        "path": "scripts/smoke_win_msvc.py",
        "reason": (
            "PROGRAMFILES(X86) env-lookup fallback: locates the host MSVC/Windows SDK "
            "where Windows itself installed it; not a project write target"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
    {
        "path": "tools/build_win_realm_runtime.py",
        "reason": (
            "PROGRAMFILES(X86) env-lookup fallback: locates the host MSVC/Windows SDK "
            "where Windows itself installed it; not a project write target"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
    {
        "path": "tools/common.py",
        "reason": (
            "MSYS2 host-toolchain DISCOVERY candidates (read-only existence probes; "
            "MSYS2_ROOT/MSYS_HOME env overrides honored before any literal)"
        ),
        "retires": "never (host toolchain lookup, never a write target)",
    },
    {
        "path": "scripts/build_native.py",
        "reason": "docstring prose describing MSYS2 discovery defaults and a path-mapping example",
        "retires": "never (prose is not a code literal)",
    },
    {
        "path": "schemas/sources.json",
        "reason": "provenance prose note describing the upstream MSYS2 default install location",
        "retires": "never (prose is not a code literal)",
    },
    {
        "path": "tests/test_common.py",
        "reason": (
            "pure string-transform fixture: proves docker_path() maps a Windows path "
            "spelling to its docker form; no filesystem access"
        ),
        "retires": "never (transform fixture)",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/diagnostics/SecretRedactorTest.kt",
        "reason": "redaction fixture: proves C:\\Users paths are scrubbed from diagnostics",
        "retires": "never (adversarial fixture must stay C:-shaped)",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/diagnostics/SupportBundleTest.kt",
        "reason": "redaction fixture: proves C:\\Users paths are scrubbed from support bundles",
        "retires": "never (adversarial fixture must stay C:-shaped)",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/AddonArchiveValidatorTest.kt",
        "reason": "adversarial path fixture: proves C:/ archive-entry paths are rejected",
        "retires": "never (adversarial fixture must stay C:-shaped)",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/ImportPathPolicyTest.kt",
        "reason": "adversarial path fixture: proves C:/Windows-style paths fail validation",
        "retires": "never (adversarial fixture must stay C:-shaped)",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/ui/RealmDataArchiveTest.kt",
        "reason": "adversarial zipslip fixture: proves C: entries inside snapshot archives are rejected",
        "retires": "never (adversarial fixture must stay C:-shaped)",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientRuntimeProviderTest.kt",
        "reason": (
            "synthetic client-path fixture for armClientArguments string mapping "
            "(Wine-drive spelling inside the sandbox; no host filesystem access)"
        ),
        "retires": "never (mapping fixture)",
    },
    {
        "path": "tests/test_install_client_windows_ps1.py",
        "reason": (
            "ProgramFiles/WINDIR env-lookup fallbacks: locate 7-Zip and "
            "powershell where Windows itself installed them; OS-provided "
            "locations, env override honored first"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
    {
        "path": "tools/build_win_llama_server.py",
        "reason": (
            "PROGRAMFILES(x86) env-lookup fallback: locates the host MSVC "
            "toolchain where Windows installed it; not a project write target"
        ),
        "retires": "never (OS-provided location, env override honored first)",
    },
)


def _is_on_e(value: str) -> bool:
    """True when a Windows path value lives under the E: drive."""
    normalized = value.replace("/", "\\").strip().lower()
    return normalized.startswith("e:\\")


def _registry_user_env(name: str) -> str | None:
    """The persisted per-user value of an environment variable (winreg),
    or None when absent / not on Windows."""
    if sys.platform != "win32":
        return None
    try:
        import winreg
    except ImportError:  # pragma: no cover - winreg is stdlib on win32
        return None
    for hive, key_path in (
        (winreg.HKEY_CURRENT_USER, "Environment"),
        (
            winreg.HKEY_LOCAL_MACHINE,
            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment",
        ),
    ):
        try:
            with winreg.OpenKey(hive, key_path) as key:
                value, _kind = winreg.QueryValueEx(key, name)
                if isinstance(value, str) and value.strip():
                    return value
        except OSError:
            continue
    return None


def env_redirection_findings() -> tuple[list[dict[str, object]], list[str]]:
    """Leg 1: the section 7 redirection table. Returns (findings, notes)."""
    findings: list[dict[str, object]] = []
    notes: list[str] = []
    if sys.platform != "win32":
        notes.append(
            "env-redirection leg SKIPPED: not a Windows host (the E:\\ toolchain "
            "contract is machine-local; the C: literal sweep below still runs)"
        )
        return findings, notes
    for name, expected_prefix, unset_meaning in TOOLCHAIN_VARS:
        live = os.environ.get(name)
        persisted = _registry_user_env(name)
        # The registry is the persisted machine truth (every new shell reads
        # it); the live process env may be a stale pre-redirection daemon.
        # The redirection is satisfied when EITHER source points under E:,
        # but a live value that contradicts a correct registry value is a
        # loud note (launching tools from THIS process would still resolve
        # the C: location), and a redirection that exists nowhere is a
        # finding, as is one configured onto C: in every source.
        on_e_sources = [
            value for value in (live, persisted) if value and _is_on_e(value)
        ]
        if not on_e_sources:
            configured = live or persisted
            if not configured:
                findings.append(
                    {
                        "code": "E-ENV-UNSET",
                        "var": name,
                        "detail": (
                            f"{name} is not configured (live env or user/machine "
                            f"registry); {unset_meaning} "
                            f"(section 7 expects {expected_prefix})"
                        ),
                    }
                )
            else:
                findings.append(
                    {
                        "code": "E-ENV-ON-C",
                        "var": name,
                        "detail": (
                            f"{name}={configured!r} resolves outside E:\\ "
                            f"(section 7 expects {expected_prefix})"
                        ),
                    }
                )
            continue
        # Round-3 R3-1a: the direction of a disagreement matters. A C: value
        # in the LIVE env with the registry on E: is a stale daemon (a note:
        # this process must not launch toolchains). But a C: value in the
        # REGISTRY with the live env on E: means every NEW shell writes its
        # caches to C: — the persisted truth is the opposite of the
        # redirection, and a note would be a fail-open lie. That direction
        # is a finding naming the source.
        # Round-4 R3-4/R2-3: a redirection that exists ONLY in the live
        # process env (persisted registry UNSET) means every new shell -
        # and CI - gets NO redirection: Gradle/pip/cargo default their
        # caches to C:\\Users\\<u>. The charter deliverable is the machine/
        # user env vars per section 7; a live-only export cannot verify
        # it. Loud finding, same doctrine as registry-side C:.
        if live and _is_on_e(live) and persisted is None:
            findings.append(
                {
                    "code": "E-ENV-NOT-PERSISTED",
                    "var": name,
                    "detail": (
                        f"{name} is set in THIS process's env ({live!r}) but "
                        "has NO persisted registry value - every new shell "
                        "and CI reads nothing and the tool defaults to "
                        "C:\\Users\\<u>; persist the redirection (setx) so "
                        "section 7 holds beyond this process (expects "
                        f"{expected_prefix})"
                    ),
                }
            )
        elif live and persisted and live.strip() != persisted.strip():
            if _is_on_e(live) and not _is_on_e(persisted):
                findings.append(
                    {
                        "code": "E-ENV-ON-C",
                        "var": name,
                        "detail": (
                            f"{name}'s PERSISTED registry value {persisted!r} "
                            "resolves outside E:\ while the live process env "
                            f"says {live!r} — every NEW shell reads the "
                            "registry and would write its caches to C: "
                            f"(section 7 expects {expected_prefix}); fix the "
                            "registry (setx) and restart shells"
                        ),
                    }
                )
            elif not _is_on_e(live) and _is_on_e(persisted):
                notes.append(
                    f"note: live {name}={live!r} differs from registry {persisted!r} "
                    "(a process started before the redirection; new shells see the "
                    "registry value — restart long-lived daemons or export the var "
                    "before launching toolchains from that process)"
                )
            else:
                notes.append(
                    f"note: live {name}={live!r} differs from registry {persisted!r} "
                    "(both moved — verify which is intended)"
                )
    return findings, notes


# R3-3: C:-resident project artifacts that were disclosed and retired carry
# an inventory here. A pip.ini under %APPDATA% existing WITHOUT an entry is
# a finding — the W7 section 0.8 "C: carries no project artifacts" gate
# re-checks this list, so the delete-or-waive decision cannot be forgotten.
DISCLOSED_C_ARTIFACTS: tuple[str, ...] = (
    # (empty as of 2026-10-08: the W0 pip.ini was retired — PIP_CACHE_DIR at
    # user-env scope covers the redirection; add a path here ONLY with an
    # owner-signed Decision Log waiver and a retire stage)
)


def configured_cache_root_findings() -> tuple[list[dict[str, object]], list[str]]:
    """Leg 3: any configured toolchain CACHE root that resolves ONTO C:.

    Cache roots are the actual section 1 write hazard (GRADLE_USER_HOME,
    PIP_CACHE_DIR, CARGO_HOME, RUSTUP_HOME); tool locations like JAVA_HOME
    are read-mostly and belong to leg 1. Also carries the R3-3 disclosed
    C:-artifact inventory and the R3-4 OS %TEMP% residue note."""
    findings: list[dict[str, object]] = []
    notes: list[str] = []
    if sys.platform != "win32":
        notes.append("cache-root leg SKIPPED: not a Windows host")
        return findings, notes
    cache_vars = {
        "GRADLE_USER_HOME", "PIP_CACHE_DIR", "CARGO_HOME", "RUSTUP_HOME"
    }
    candidates: dict[str, str] = {}
    for name in sorted(cache_vars):
        # The EFFECTIVE cache root a tool launched from THIS process would
        # use: live env wins (that is what the tool reads), registry is the
        # fallback. A stale live C: value is a real write hazard here, so
        # unlike leg 1 it does NOT fall through to the registry.
        value = os.environ.get(name) or _registry_user_env(name) or ""
        if value:
            candidates[name] = value
        # round-3 R3-1a: the registry is what NEW shells read — check it
        # even when the live env already redirects (both must be clean)
        registry_value = _registry_user_env(name) or ""
        if registry_value:
            candidates[f"{name} (registry)"] = registry_value
    appdata = os.environ.get("APPDATA", "")
    pip_ini = Path(appdata) / "pip" / "pip.ini" if appdata else None
    if pip_ini and pip_ini.is_file():
        if str(pip_ini) not in DISCLOSED_C_ARTIFACTS:
            findings.append(
                {
                    "code": "E-C-ARTIFACT",
                    "var": "pip.ini",
                    "detail": (
                        f"{pip_ini} exists on C: without a disclosed-artifact "
                        "waiver — retire it (PIP_CACHE_DIR covers the "
                        "redirection) or record an owner-signed waiver in "
                        "DISCLOSED_C_ARTIFACTS with a retire stage"
                    ),
                }
            )
        try:
            for line in pip_ini.read_text(encoding="utf-8", errors="replace").splitlines():
                stripped = line.strip()
                if stripped.lower().startswith("cache-dir"):
                    value = stripped.split("=", 1)[1].strip() if "=" in stripped else ""
                    if value:
                        candidates["pip.ini cache-dir"] = value
        except OSError:
            notes.append(f"note: could not read {pip_ini}")
    for name, value in sorted(candidates.items()):
        normalized = value.replace("/", "\\").strip().lower()
        if normalized.startswith("c:\\"):
            findings.append(
                {
                    "code": "E-CACHE-ON-C",
                    "var": name,
                    "detail": f"{name}={value!r} is configured ONTO C:\\ — relocate under E:\\",
                }
            )
    # Round-4 R2-1: the PRODUCT's durable-state root (%LOCALAPPDATA%
    # PocketRealm) resolved the way pip.ini is - an existing C:-resident
    # tree without an owner-signed disclosed-artifact entry is a finding
    # (plan section 1.1: any C: write is Serious; section 0.8: C: carries
    # no project artifacts).
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    product_root = Path(local_app_data) / "PocketRealm" if local_app_data else None
    if product_root is not None and product_root.is_dir():
        if str(product_root) not in DISCLOSED_C_ARTIFACTS:
            findings.append(
                {
                    "code": "E-C-ARTIFACT",
                    "var": "product-data-root",
                    "detail": (
                        f"{product_root} exists on C: without a disclosed-"
                        "artifact waiver (the desktop product's durable-state "
                        "tree; D50 owns the relocate-or-waive decision)"
                    ),
                }
            )
            notes.append(
                "note: the desktop product root is on C: - D50 (round-4 R2-1) "
                "escalated the relocate-or-waive decision; the gate stays red "
                "until the owner rules"
            )
    # R3-4: OS %TEMP% residue — disclosed, loud, and NOT asserted until the
    # owner rules on machine-wide TEMP/TMP redirection (WAVE-EXIT deferral).
    on_c = [
        name for name in ("TEMP", "TMP")
        if (os.environ.get(name, "").replace("/", "\\").lower().startswith("c:\\"))
    ]
    if on_c:
        notes.append(
            "note E-TEMP: " + ", ".join(on_c) + " resolve on C:\\ — "
            "the PROJECT's own writers are redirected: the gradle daemon "
            "jvmargs, the forked Test-worker systemProperty, pytest's "
            "PYTEST_DEBUG_TEMPROOT (conftest.py), and every tracked Python "
            "tool's tempfile scratch via tools/common.toolchain_tmpdir() "
            "(all pin E:/pocket_realm_toolchain/tmp; a new default-tempdir "
            "producer fails the repo-hygiene pin until it routes through "
            "the helper). The remaining %TEMP% users are OS-level tools "
            "OUTSIDE the project — the D50(c) disclosed exception class "
            "(decision D56) — until the owner rules a machine-wide "
            "TEMP/TMP move"
        )
    return findings, notes


def _tracked_text_files(root: Path) -> list[Path] | None:
    """Every COMMITTED-or-COMMITTABLE text file (index + untracked but not
    gitignored), or None when the git enumeration itself cannot run.

    R1-1/R3-1: scanning only the index left the gate structurally blind to
    exactly the files a finalize commit sweeps in, so the enumeration is
    `--cached --others --exclude-standard` — tracked, plus untracked files
    git would actually add. R2-1/R3-2: a failed/absent git is a LOUD
    E-SCAN-FAILED finding (returned as None), never a silent 0-file pass.
    R3-1 (review round 1): a file whose 8 KB head probe raises OSError
    STAYS in the returned list (fail-closed) — the caller's read of it
    produces the <unreadable> E-LITERAL finding, mirroring leak_sweep's
    handling; dropping it here made locked files invisible to the sweep."""
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files",
             "--cached", "--others", "--exclude-standard", "-z"],
            capture_output=True, timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    paths: list[Path] = []
    for raw in result.stdout.split(bytes([0])):
        if not raw:
            continue
        rel = raw.decode("utf-8", "replace")
        if any(rel.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
            continue
        path = root / rel
        if not path.is_file():
            continue
        try:
            head = path.open("rb")
            with head:
                head_bytes = head.read(8192)
        except OSError:
            # R3-1 (review round 1): an unreadable file must NOT vanish from
            # the sweep — the old `continue` silently dropped it, so a
            # locked file carrying a C: literal passed with zero findings.
            # Keep the path; literal_findings' read of it raises and lands
            # the <unreadable> E-LITERAL finding (the leak_sweep shape).
            paths.append(path)
            continue
        # Round-3 R3-2: UTF-16/32 text (a real class — PowerShell 5.1's
        # Out-File default) carries NUL bytes but is NOT binary: decode via
        # BOM instead of skipping it silently.
        if bytes([0]) in head_bytes:
            decoded = _decode_bom_text(path)
            if decoded is None:
                continue  # genuinely binary: no text to sweep
            paths.append(path)
            continue
        paths.append(path)
    return sorted(paths)


def _decode_bom_text(path: Path) -> str | None:
    """Decode a UTF-16/32 file via its BOM; None when no BOM (binary)."""
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    for bom, encoding in (
        (bytes([0xFF, 0xFE, 0x00, 0x00]), "utf-32"),
        (bytes([0x00, 0x00, 0xFE, 0xFF]), "utf-32"),
        (bytes([0xFF, 0xFE]), "utf-16"),
        (bytes([0xFE, 0xFF]), "utf-16"),
    ):
        if raw.startswith(bom):
            try:
                return raw.decode(encoding)
            except (UnicodeDecodeError, ValueError):
                return None
    return None


def _allowlisted(rel_path: str) -> dict[str, str] | None:
    for entry in ALLOWLIST:
        if entry["path"] == rel_path:
            return entry
    return None


def _enumeration_has_any_file(root: Path) -> bool:
    """True when the git enumeration itself returned nothing (the E-SCAN-EMPTY
    case). A repo whose files were all excluded (docs-only synthetic trees)
    legitimately scans zero and is NOT a coverage failure."""
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files",
             "--cached", "--others", "--exclude-standard"],
            capture_output=True, timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return True  # cannot tell: keep the loud failure
    return result.returncode == 0 and not result.stdout.strip()


def literal_findings(root: Path) -> tuple[list[dict[str, object]], int]:
    """Leg 2: C: path literals in committed-or-committable text files
    outside the allowlist. Returns (findings, files scanned).

    R2-1/R3-2 fail-closed: a failed git enumeration is E-SCAN-FAILED, and a
    successful enumeration that yields ZERO scannable files is E-SCAN-EMPTY
    — a security gate must never report success on zero coverage."""
    findings: list[dict[str, object]] = []
    paths = _tracked_text_files(root)
    if paths is None:
        findings.append(
            {
                "code": "E-SCAN-FAILED",
                "path": str(root),
                "line": 0,
                "detail": (
                    "the git file enumeration failed (git missing, not a "
                    "repository, or timed out) — the C: literal sweep cannot "
                    "run, and a gate that cannot run must fail, not pass"
                ),
            }
        )
        return findings, 0
    if not paths and _enumeration_has_any_file(root):
        findings.append(
            {
                "code": "E-SCAN-EMPTY",
                "path": str(root),
                "line": 0,
                "detail": (
                    "the git enumeration returned NOTHING AT ALL — an empty "
                    "scan is a coverage failure, never a pass"
                ),
            }
        )
        return findings, 0
    for path in paths:
        rel = path.relative_to(root).as_posix()
        entry = _allowlisted(rel)
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
            head_probe = path.open("rb").read(4)
        except OSError as exc:
            findings.append(
                {
                    "code": "E-LITERAL",
                    "path": rel,
                    "line": 0,
                    "detail": f"<unreadable> {exc}",
                }
            )
            continue
        if head_probe.startswith((bytes([0xFF, 0xFE]), bytes([0xFE, 0xFF]),
                                  bytes([0xFF, 0xFE, 0x00, 0x00]),
                                  bytes([0x00, 0x00, 0xFE, 0xFF]))):
            decoded = _decode_bom_text(path)
            if decoded is not None:
                text = decoded
        indirection = entry is None and _env_indirection_hit(rel)
        for lineno, line in enumerate(text.splitlines(), 1):
            if C_LITERAL_RE.search(line):
                if entry is not None:
                    continue  # allowlisted file (reason recorded in ALLOWLIST)
                findings.append(
                    {
                        "code": "E-LITERAL",
                        "path": rel,
                        "line": lineno,
                        "detail": line.strip()[:160],
                    }
                )
            elif indirection and _ENV_INDIRECTION_RE.search(_code_half(line)):
                # Round-4 R2-1: LOCALAPPDATA/APPDATA/user.home indirection
                # builds C: roots with NO C: literal - the class that put the
                # desktop product's durable state on %LOCALAPPDATA%.
                findings.append(
                    {
                        "code": "E-ENV-INDIRECTION",
                        "path": rel,
                        "line": lineno,
                        "detail": line.strip()[:160],
                    }
                )
    return findings, len(paths)


# The env-indirection producers (round-4 R2-1): OS user-profile locations
# a project root can be built from instead of E:.
_ENV_INDIRECTION_RE = re.compile(
    r"LOCALAPPDATA|APPDATA|user\.home|getUserProfile"
)


def _env_indirection_hit(rel_path: str) -> bool:
    """True for a tracked production file that may route a project root
    through the OS user profile. Test fixtures are exempt (JUnit tmp
    dirs); the owning-surface entries in ENV_INDIRECTION_ALLOWLIST are the
    designed producers, named with the pending owner decision (D50)."""
    if rel_path.startswith("tests/") or "/test/" in rel_path or "Test" in rel_path:
        return False
    return _allowlisted(rel_path) is None and rel_path not in ENV_INDIRECTION_ALLOWLIST


def _code_half(line: str) -> str:
    """The code half of a line (KDoc/doc-comment mentions of the
    %LOCALAPPDATA% root are prose, not producers).

    R3-4/R5-2: a ``*`` truncates only in COMMENT POSITION — the ``/*``
    opener, or a ``*`` at line start preceded by whitespace alone (the
    block-comment continuation convention). A pointer DECLARATOR's ``*``
    is code: truncating ``const char* base = std::getenv("LOCALAPPDATA");``
    at the ``*`` hid the exact producer this gate exists for. Fail-closed
    consequence: a ``LOCALAPPDATA`` mention inside a mid-line block
    comment AFTER code on the same line now flags (a reviewable false
    positive beats a blind gate)."""
    cut = len(line)
    for marker in ("//", "#", "/*"):
        idx = line.find(marker)
        if idx != -1 and idx < cut:
            cut = idx
    for idx, ch in enumerate(line):
        if ch == "*" and not line[:idx].strip():
            if idx < cut:
                cut = idx
            break
    return line[:cut]


# Round-4 R2-1: the DESIGNED producers of the desktop product's
# %LOCALAPPDATA% root - the owning surfaces of the indirection class,
# each pending the owner's relocation decision (D50). Any NEW producer
# fails the gate until it joins this table or moves under E:.
# Round-5 R5-1: exactly TWO owning resolvers remain - the Kotlin side
# (DesktopStorageRoots.defaultRoot: POCKET_REALM_DATA_ROOT -> the shipped
# LOCALAPPDATA fallback) and the Python side (tools/common.product_data_root,
# the same contract). Every other surface resolves through them, so the
# D50 override relocates the WHOLE product; any NEW LOCALAPPDATA producer
# fails the gate until it routes through a resolver.
# R3-2/R5-1 (native-server round 1): a THIRD owning resolver joins them -
# the native crash tracer (win_exception_shim.cpp pocket_crash_file), the
# native twin of the same contract (override root first, LOCALAPPDATA
# fallback), pinned byte-shared with both by tests/test_crash_trace_root.py.
ENV_INDIRECTION_ALLOWLIST: frozenset[str] = frozenset({
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopStorageRoots.kt",
    "tools/common.py",
    "native/realm-runtime/src/win_exception_shim.cpp",
    # defaultLocalDumpsDir READS Windows Error Reporting's own dump
    # location (an OS-provided location Windows itself writes); the
    # product-root override does not govern it.
    "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopCrashEvidence.kt",
})


def stale_allowlist_findings(root: Path) -> list[dict[str, object]]:
    """An allowlist entry whose target file no longer contains any C:
    literal is dead weight that hides future edits from review."""
    findings: list[dict[str, object]] = []
    for entry in ALLOWLIST:
        path = root / entry["path"]
        if not path.is_file():
            continue  # absent targets (synthetic trees) are skipped
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if not any(C_LITERAL_RE.search(line) for line in text.splitlines()):
            findings.append(
                {
                    "code": "E-STALE",
                    "path": entry["path"],
                    "line": 0,
                    "detail": (
                        f"stale allowlist entry: {entry['path']} contains no C: literal "
                        "anymore — retire the entry"
                    ),
                }
            )
    return findings


def collect_findings(root: Path) -> dict[str, object]:
    env_findings, env_notes = env_redirection_findings()
    cache_findings, cache_notes = configured_cache_root_findings()
    lit_findings, scanned = literal_findings(root)
    stale = stale_allowlist_findings(root)
    findings = env_findings + cache_findings + lit_findings + stale
    return {
        "ok": not findings,
        "files_scanned": scanned,
        "excluded_prefixes": list(EXCLUDED_PREFIXES),
        "allowlisted_files": len(ALLOWLIST),
        "findings": findings,
        "notes": env_notes + cache_notes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = collect_findings(args.root.resolve())
    if args.json:
        print(json.dumps(report, indent=2))
        return 1 if not report["ok"] else 0

    for note in report["notes"]:
        print(note)
    for finding in report["findings"]:
        code = finding.get("code", "E-?")
        where = finding.get("path", finding.get("var", "?"))
        line = finding.get("line")
        loc = f":{line}" if isinstance(line, int) and line else ""
        print(f"ERROR {code} {where}{loc} {finding['detail']}")
    if not report["ok"]:
        print(
            f"check_e_only: FAILED ({len(report['findings'])} findings; "
            f"{report['files_scanned']} files scanned, "
            f"{report['allowlisted_files']} allowlisted files)"
        )
        return 1
    print(
        f"check_e_only: OK ({report['files_scanned']} files scanned, "
        f"{report['allowlisted_files']} allowlisted files, "
        f"env redirection table verified)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
