"""W3.5 evidence-pack lint (code-red round 1: R1-1/R2-2/R3-2/R4-1/R6-2).

Every archived login-topology capture must be a possible output of the
committed probe: the md's recorded `-DflavorId` equals its raw
FLAVOR-LOGIN-PROBE line's `flavor=` field, and the line's
acceptedWindowClasses equals `Win32AutoLogin.loginSignatureFor(flavor)`'s
accepted set for that flavor (parsed from the committed Kotlin source, so a
signature change re-derives the expectation automatically). The round-1
review found the archived WotLK capture carried the TBC two-class set with
a hand-written command line — no probe run can print that combination.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_DIR = ROOT / "out/completion/w3/login-topology"
WIN32_AUTO_LOGIN = (
    ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/Win32AutoLogin.kt"
)

ProbeError = tuple[str, str]  # (file, what)

# the realmlist notes the committed FlavorLoginProbe can print. The
# fix made the rewrite UNCONDITIONAL (always writeText + always the
# same note), so exactly one marker is emittable today; the set-form keeps
# the leg readable if a future note variant lands WITH its own test pin.
EMITTABLE_REALMLIST_NOTES = {"rewritten to loopback"}


def _signatures_from_kotlin() -> dict[str, dict[str, object]]:
    """Parse LOGIN_SIGNATURES out of the committed Win32AutoLogin.kt:
    flavor -> {windowClass, title, alternates}."""
    text = WIN32_AUTO_LOGIN.read_text(encoding="utf-8")
    block_at = text.index("LOGIN_SIGNATURES")
    block = text[text.index("mapOf(", block_at):]
    # terminate at the closing of the mapOf (the first "\n    )" after it)
    block = block[: block.index("\n    )")]
    signatures: dict[str, dict[str, object]] = {}
    for flavor_match in re.finditer(
        r"ClientFlavors\.([A-Z]+)\.flavorId to LoginWindowSignature\((.*?)\n        \)",
        block,
        re.DOTALL,
    ):
        lane = flavor_match.group(1).lower()
        body = flavor_match.group(2)
        window_class = re.search(r"windowClass = \"([^\"]+)\"", body)
        title = re.search(r"title = \"([^\"]+)\"", body)
        alternates = re.search(r"alternateWindowClasses = setOf\(([^)]*)\)", body)
        assert window_class and title, f"unparseable signature for {lane}"
        alt_set = (
            re.findall(r'"([^"]+)"', alternates.group(1)) if alternates else []
        )
        signatures[lane] = {
            "windowClass": window_class.group(1),
            "title": title.group(1),
            "alternates": alt_set,
        }
    return signatures


def test_every_login_topology_capture_is_a_possible_probe_output():
    assert EVIDENCE_DIR.is_file.__self__.is_dir(), "the evidence dir is missing"
    md_files = sorted(EVIDENCE_DIR.glob("*.md"))
    assert md_files, "no login-topology evidence archived"
    signatures = _signatures_from_kotlin()
    errors: list[ProbeError] = []
    for md in md_files:
        text = md.read_text(encoding="utf-8")
        # the raw probe line
        raw_match = re.search(r"^FLAVOR-LOGIN-PROBE (.+)$", text, re.M)
        if raw_match is None:
            errors.append((md.name, "no FLAVOR-LOGIN-PROBE raw line"))
            continue
        raw = raw_match.group(1)
        flavor_field = re.search(r'flavor="([^"]+)"', raw)
        classes_field = re.search(r'acceptedWindowClasses="([^"]+)"', raw)
        title_field = re.search(r'pinnedTitle="([^"]+)"', raw)
        if flavor_field is None or classes_field is None or title_field is None:
            errors.append((md.name, "raw line is missing a required field"))
            continue
        flavor = flavor_field.group(1)
        # (a) the recorded command's -DflavorId must equal the line's flavor
        command = re.search(r"-DflavorId=([a-z]+)", text)
        if command is None:
            errors.append((md.name, "no -DflavorId in the recorded command"))
        elif command.group(1) != flavor:
            errors.append((
                md.name,
                f"command drives -DflavorId={command.group(1)} but the raw "
                f'line says flavor="{flavor}"',
            ))
        # (b) the acceptedWindowClasses must equal the committed signature's
        # set for that flavor (primary + alternates, in order)
        signature = signatures.get(flavor)
        if signature is None:
            errors.append((md.name, f"unknown flavor {flavor!r}"))
            continue
        expected = "|".join(
            [str(signature["windowClass"])] + list(signature["alternates"]))
        if classes_field.group(1) != expected:
            errors.append((
                md.name,
                f'acceptedWindowClasses="{classes_field.group(1)}" is not the '
                f'committed {flavor} signature ("{expected}") — no probe run '
                "can print that combination",
            ))
        if title_field.group(1) != str(signature["title"]):
            errors.append((md.name, "pinnedTitle drifted from the signature"))
        # (c2) R3-1: the realmlist note must be a marker the COMMITTED probe
        # can emit — FlavorLoginProbe's unconditional rewrite prints exactly
        # '(rewritten to loopback)'; a note outside the emittable set
        # proves the capture predates the build the md claims (the
        # finding: '(already loopback)' was the pre- substring-check
        # build's marker, not this one's).
        realmlist_field = re.search(r'realmlist="[^"]*\(([^)]+)\)"', raw)
        if realmlist_field is None:
            errors.append((md.name, "raw line is missing the realmlist note"))
        elif realmlist_field.group(1) not in EMITTABLE_REALMLIST_NOTES:
            errors.append((
                md.name,
                f'realmlist note "({realmlist_field.group(1)})" is not a '
                "marker the committed FlavorLoginProbe can emit "
                f"{sorted(EMITTABLE_REALMLIST_NOTES)} — the capture predates "
                "the build the md claims",
            ))
        # (c) the verdict prose names the same flavor (case-insensitive —
        # the lane's display spelling, e.g. "WotLK", is fine)
        prose_flavor = re.search(
            r"the pinned `?Win32AutoLogin`? (\w+) login signature", text)
        if prose_flavor is None or prose_flavor.group(1).lower() != flavor:
            errors.append((
                md.name,
                f"the verdict prose does not name the {flavor} signature",
            ))
        # (d) R4-R2-1: the archived exeSha256 must match the flavor's S5.1 pin
        sha_field = re.search(r'exeSha256="([^"]+)"', raw)
        if sha_field is None:
            errors.append((md.name, "raw line is missing exeSha256"))
        else:
            import json as _json
            flavor_manifest = _json.loads(
                (ROOT / "schemas/flavor.json").read_text(encoding="utf-8"))
            pinned = next(
                (f.get("wine", {}).get("known_exe_sha256")
                 for f in flavor_manifest["flavors"] if f["id"] == flavor),
                None,
            )
            if pinned and sha_field.group(1) != pinned:
                errors.append((
                    md.name,
                    f'exeSha256="{sha_field.group(1)[:12]}…" does not match the '
                    f'{flavor} S5.1 pin ({pinned[:12]}…) — a wrong-client capture',
                ))
    assert not errors, (
        "login-topology evidence captures that no probe run could produce: "
        + "; ".join(f"{f}: {w}" for f, w in errors)
    )
