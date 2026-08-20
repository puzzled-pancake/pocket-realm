#!/usr/bin/env python3
"""Generate the Kotlin community driver list from the reviewed JSON manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "schemas" / "community-vulkan-drivers.json"
OUTPUT = (
    ROOT / "android" / "app" / "src" / "main" / "java" / "com" /
    "pocketrealm" / "client" / "GeneratedCommunityVulkanDrivers.kt"
)
POLICY = "pinned-digest-download-only"
FORMATS = {"adrenotools-zip", "bare-so"}
MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
# Unanchored on purpose: every use is .fullmatch(), mirroring Kotlin's
# Regex.matches() (full match) so a manifest edit can never pass here and
# then throw in the data-class init at app startup.
ID_RE = re.compile(r"community-[a-z0-9][a-z0-9.-]{2,63}")
REPO_RE = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
HEX64_RE = re.compile(r"[0-9a-f]{64}")
# The pinned URL must be the exact GitHub release asset of source.repo and
# source.release — no query strings, fragments, or whitespace; redirects at
# download time resolve only through the self-updater's allowlisted hosts.
URL_RE = re.compile(
    r"https://github\.com/(?P<repo>[^/\s]+/[^/\s]+)/releases/download/"
    r"(?P<release>[^/?#\s]+)/(?P<asset>[^/?#\s]+)"
)


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def reject(message: str) -> None:
    raise RuntimeError(message)


def load_manifest() -> dict[str, object]:
    raw = MANIFEST.read_bytes()
    manifest = json.loads(raw)
    if manifest.get("schema") != 1:
        reject("unsupported community Vulkan driver manifest schema")
    if manifest.get("policy") != POLICY:
        reject("community Vulkan drivers must be pinned-digest download-only")
    drivers = manifest.get("drivers")
    if not isinstance(drivers, list) or not drivers:
        reject("community Vulkan driver manifest has no driver list")

    seen_ids: set[str] = set()
    for driver in drivers:
        driver_id = driver.get("id")
        if not isinstance(driver_id, str) or not ID_RE.fullmatch(driver_id):
            reject(f"invalid community Vulkan driver id: {driver_id}")
        if driver_id in seen_ids:
            reject(f"duplicate community Vulkan driver id: {driver_id}")
        seen_ids.add(driver_id)
        if not isinstance(driver.get("version"), str) or not driver["version"].strip():
            reject(f"community Vulkan driver version is absent: {driver_id}")

        display = driver.get("display")
        if not isinstance(display, dict) or not all(
            isinstance(display.get(field), str) and display[field].strip()
            for field in ("label", "summary", "note")
        ):
            reject(f"community Vulkan driver display metadata is incomplete: {driver_id}")
        # Kotlin's String.length counts UTF-16 units, not code points — the
        # label cap must be measured the way the Kotlin init will measure it.
        if len(display["label"].encode("utf-16-le")) // 2 > 64:
            reject(f"community Vulkan driver label exceeds 64 chars: {driver_id}")

        source = driver.get("source")
        if not isinstance(source, dict):
            reject(f"community Vulkan driver source is absent: {driver_id}")
        repo = source.get("repo")
        if not isinstance(repo, str) or not REPO_RE.fullmatch(repo):
            reject(f"community Vulkan driver source repo is invalid: {driver_id}")
        if any(segment in {".", ".."} for segment in repo.split("/")):
            reject(f"community Vulkan driver source repo has dot segments: {driver_id}")
        if not isinstance(source.get("release"), str) or not source["release"].strip():
            reject(f"community Vulkan driver release is absent: {driver_id}")
        url = source.get("url")
        match = URL_RE.fullmatch(url) if isinstance(url, str) else None
        if match is None:
            reject(f"community Vulkan driver URL is not a GitHub release asset: {driver_id}")
        elif match.group("repo") != repo or match.group("release") != source["release"]:
            reject(f"community Vulkan driver URL does not match its source repo/release: {driver_id}")
        if not isinstance(source.get("size"), int) or not 0 < source["size"] <= MAX_DOWNLOAD_BYTES:
            reject(f"community Vulkan driver size is invalid: {driver_id}")
        sha256 = source.get("sha256")
        if not isinstance(sha256, str) or not HEX64_RE.fullmatch(sha256):
            reject(f"community Vulkan driver sha256 is invalid: {driver_id}")
        library_sha256 = source.get("library_sha256")
        if library_sha256 is not None and (
            not isinstance(library_sha256, str) or not HEX64_RE.fullmatch(library_sha256)
        ):
            reject(f"community Vulkan driver library_sha256 is invalid: {driver_id}")
        if source.get("format") not in FORMATS:
            reject(f"community Vulkan driver format is unsupported: {driver_id}")
        if source.get("license") != "MIT":
            reject(f"community Vulkan driver license is not MIT: {driver_id}")
        if not isinstance(source.get("upstream"), str) or not source["upstream"].strip():
            reject(f"community Vulkan driver upstream is absent: {driver_id}")
    return manifest


def render(manifest: dict[str, object]) -> bytes:
    source_digest = hashlib.sha256(MANIFEST.read_bytes()).hexdigest()
    lines = [
        "// Generated by tools/generate_community_vulkan_drivers.py.",
        "// Edit schemas/community-vulkan-drivers.json, then regenerate; do not edit this file.",
        "package com.pocketrealm.client",
        "",
        "internal object GeneratedCommunityVulkanDrivers {",
        f"    const val SCHEMA: Int = {manifest['schema']}",
        f"    const val POLICY: String = {kotlin_string(manifest['policy'])}",
        f"    const val SOURCE_MANIFEST_SHA256: String = {kotlin_string(source_digest)}",
        "",
        "    val drivers: List<CommunityVulkanDriver> = listOf(",
    ]
    for driver in manifest["drivers"]:
        source = driver["source"]
        library_sha256 = source.get("library_sha256")
        lines.extend([
            "        CommunityVulkanDriver(",
            f"            id = {kotlin_string(driver['id'])},",
            f"            label = {kotlin_string(driver['display']['label'])},",
            f"            version = {kotlin_string(driver['version'])},",
            f"            repo = {kotlin_string(source['repo'])},",
            f"            release = {kotlin_string(source['release'])},",
            f"            url = {kotlin_string(source['url'])},",
            f"            size = {source['size']}L,",
            f"            sha256 = {kotlin_string(source['sha256'])},",
            "            librarySha256 = "
            + (kotlin_string(library_sha256) if library_sha256 else "null") + ",",
            f"            format = {kotlin_string(source['format'])},",
            f"            license = {kotlin_string(source['license'])},",
            f"            upstream = {kotlin_string(source['upstream'])},",
            f"            summary = {kotlin_string(driver['display']['summary'])},",
            f"            note = {kotlin_string(driver['display']['note'])},",
            "        ),",
        ])
    lines.extend(["    )", "}", ""])
    return "\n".join(lines).encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the checked-in Kotlin source is not exactly regenerated",
    )
    args = parser.parse_args()
    rendered = render(load_manifest())
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != rendered:
            raise RuntimeError(
                "GeneratedCommunityVulkanDrivers.kt is stale; run "
                "python tools/generate_community_vulkan_drivers.py"
            )
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
