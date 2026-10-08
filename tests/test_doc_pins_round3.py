"""Doc-drift pins from code-red round 3.

R6-2: every artifact-path claim docs/FLAVOR-ARTIFACT-NAMING.md makes about
the resolver must equal tools/flavor_layout.resolve()'s actual output, and
every `tests/...::test` name the doc cites must exist.
R6-3: the LLM settings guide and runtime submenu must state the SAME
generation-timeout numbers the shipped Kotlin constants carry
(MEASURED_COLD_START_TIMEOUT_SEC / MIN/MAX_GENERATION_TIMEOUT_SEC).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import flavor_layout  # noqa: E402

NAMING_DOC = ROOT / "docs/FLAVOR-ARTIFACT-NAMING.md"


def test_every_lockfile_scheme_resolves_to_the_resolver_output():
    """R6-2: the doc's vanilla claim is scheme-specific — pin the resolver
    against the documented families for ALL three flavors, so prose and
    code cannot drift again."""
    for flavor in ("vanilla", "tbc", "wotlk"):
        layout = flavor_layout.resolve(flavor)
        # the doc's §2 rows these families cover
        assert layout.sqlite_win_lockfile() == {
            "vanilla": "schemas/realm-runtime-lockfile-sqlite-win.json",
            "tbc": "schemas/realm-runtime-lockfile-sqlite-win-tbc.json",
            "wotlk": "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json",
        }[flavor]
        assert layout.o11_lockfile("x86_64") == {
            "vanilla": "schemas/o11-extractor-lockfile.json",
            "tbc": "schemas/o11-extractor-lockfile-tbc.json",
            "wotlk": "schemas/o11-extractor-lockfile-wotlk.json",
        }[flavor]
        assert layout.realm_runtime_lockfile("arm64-v8a") == (
            "schemas/realm-runtime-lockfile-arm64-v8a.json" if flavor == "vanilla"
            else f"schemas/realm-runtime-lockfile-{flavor}-arm64-v8a.json"
        ), (flavor, layout.realm_runtime_lockfile("arm64-v8a"))
        # migration_ledger returns (canonical, alias-or-None): vanilla keeps
        # the unsuffixed name as the byte-identical alias (D35); tbc/wotlk
        # have canonical-only names.
        canonical, alias = layout.migration_ledger()
        assert canonical == {
            "vanilla": "schemas/database-migrations-vanilla.json",
            "tbc": "schemas/database-migrations-tbc.json",
            "wotlk": "schemas/database-migrations-wotlk.json",
        }[flavor]
        assert alias == ("schemas/database-migrations.json" if flavor == "vanilla" else None)


def test_naming_doc_cited_tests_exist():
    text = NAMING_DOC.read_text(encoding="utf-8")
    for test_file, test_name in re.findall(
            r"`(tests/[A-Za-z0-9_./]+\.py)::([A-Za-z0-9_]+)`", text):
        source = (ROOT / test_file).read_text(encoding="utf-8")
        assert f"def {test_name}" in source, (
            f"{NAMING_DOC.name} cites {test_file}::{test_name} which does not exist"
        )


def test_llm_timeout_docs_state_the_shipped_constants():
    """R6-3: the guides must state the SAME numbers the shipped constants
    carry — a user reading 300 would believe a 350 s override clamps down
    when it actually passes through (<=360)."""
    policy = (
        ROOT / "android/app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt"
    ).read_text(encoding="utf-8")
    registry = (
        ROOT / "android/app/src/main/java/com/pocketrealm/llm/LlmModelRegistry.kt"
    ).read_text(encoding="utf-8")
    embedded_default = re.search(
        r"MEASURED_COLD_START_TIMEOUT_SEC\s*=\s*(\d+)", registry)
    max_clamp = re.search(r"MAX_GENERATION_TIMEOUT_SEC\s*=\s*(\d+)", policy)
    min_clamp = re.search(r"MIN_GENERATION_TIMEOUT_SEC\s*=\s*(\d+)", policy)
    assert embedded_default and max_clamp and min_clamp
    embedded = embedded_default.group(1)
    low, high = min_clamp.group(1), max_clamp.group(1)

    for doc in ("docs/llm-settings-guide.md", "docs/llm-runtime-submenu.md"):
        text = (ROOT / doc).read_text(encoding="utf-8")
        assert f"clamped {low}-{high} s" in text or doc.endswith("settings-guide.md"), (
            f"{doc}: the clamp band drifted from the shipped constants"
        )
        # the embedded-lane wall is 360, never the stale 300
        assert "wall 300 s" not in text, (
            f"{doc}: still states the stale 300 s embedded cold-start wall "
            f"(shipped: {embedded} s)"
        )
    submenu = (ROOT / "docs/llm-runtime-submenu.md").read_text(encoding="utf-8")
    assert f"LLMGenerationTimeout = {embedded}" in submenu, (
        "the sample conf's generation timeout drifted from the registry constant"
    )
    assert f"clamped {low}-{high} s" in submenu
