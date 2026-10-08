"""Rank-lane cardinality pins for tools/install_client_windows.ps1.

The verdict list is built as ``$verdicts = foreach ($archive in $archives)``
and then extended with ``$verdicts += [pscustomobject]`` per already-extracted
client. A bare foreach statement assigns $null (zero iterations) or a SCALAR
(one iteration) on Windows PowerShell 5.1, so the first += produced a scalar
PSObject and the SECOND client crashed with "does not contain a method named
'op_Addition'" (rc=1) — every staging root holding extracted clients and no
archives took the rank and install lanes down (LVF-flavor-import-005). The
fix wraps the foreach in @(...) so += always appends to an array.

A second recorded defect is pinned here too: the genuine 1.12.1.5875 client
stamps WoW.exe FileVersion in comma form ('1, 12, 1, 5875'), which the
'1.12.1.5875*' -like pattern can never match, so the real client WARNed on
its own rank row. The fix normalizes the FileVersion string (trim, commas ->
dots) BEFORE the match; the pristine byte-size acceptance path stays
untouched.

A third set of pins covers the -Source install lane: the raw FileInfo the
lane used to hand to verdict-row code (FileInfo has neither Archive nor
Format, so Get-ArchiveFormat got $null and died with 'Empty path name is
not legal.'), the documented folder variant that Test-Path -PathType Leaf
used to reject with 'Source not found', and the P1 where an archive
basename with no [A-Za-z0-9_.-] at all slugged to '' so Join-Path returned
the InstallRoot itself and the no-silent-merges Remove-Item wiped every
previously installed client before extracting into InstallRoot directly.

These tests run the real script against synthetic staging roots, on this
host's Windows PowerShell, so the cardinality shapes stay pinned end to end:
zero archives + two extracted clients (the recorded crash), and one archive +
one extracted client (the same root cause's scalar arm).
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "install_client_windows.ps1"


def _powershell_available() -> bool:
    return sys.platform == "win32" and shutil.which("powershell") is not None


def _seven_zip_exe() -> Path:
    return Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "7-Zip" / "7z.exe"


def _seven_zip_available() -> bool:
    # The script fails loud at discovery when 7-Zip is missing; the pins
    # below are about the verdict-table path, not the discovery error.
    return _seven_zip_exe().is_file()


def _run_rank(staging_root: Path, flavor: str | None = None) -> subprocess.CompletedProcess:
    args = [
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", str(SCRIPT),
        "-StagingRoot", str(staging_root), "-RankOnly",
    ]
    if flavor is not None:
        args += ["-Flavor", flavor]
    return subprocess.run(args, capture_output=True, text=True, timeout=120)


def _extracted_client(root: Path, name: str) -> Path:
    # WoW.exe + Data/ is all the folder-import detector needs (identity
    # findings may then WARN; a WARN row still reaches the table).
    client = root / name
    (client / "Data").mkdir(parents=True)
    (client / "WoW.exe").write_bytes(b"MZ")
    return client


def _run_install(
    staging_root: Path, install_root: Path, source: Path | None = None
) -> subprocess.CompletedProcess:
    args = [
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", str(SCRIPT),
        "-StagingRoot", str(staging_root), "-InstallRoot", str(install_root),
    ]
    if source is not None:
        args += ["-Source", str(source)]
    return subprocess.run(args, capture_output=True, text=True, timeout=120)


def _client_zip(dest: Path) -> None:
    """A real zip whose listing satisfies Get-ClientRoot (WoW.exe + Data\\*).

    Built with the same 7-Zip binary the script itself discovers, so the
    ranking, extraction and client-root rebase paths all see production
    shapes (7-Zip lists entry paths with backslashes).
    """
    wrapper = dest.parent / "ziproot"
    (wrapper / "Data").mkdir(parents=True)
    (wrapper / "WoW.exe").write_bytes(b"MZ")
    (wrapper / "Data" / "base.MPQ").write_bytes(b"")
    subprocess.run(
        [str(_seven_zip_exe()), "a", "-tzip", str(dest), "WoW.exe", "Data"],
        cwd=wrapper, capture_output=True, text=True, timeout=120, check=True,
    )


def test_rank_lane_survives_extracted_only_staging_root() -> None:
    """Zero archives + two extracted clients: the exact recorded crash shape.

    Before the @(...) fix the first += collapsed $verdicts to a scalar
    PSObject and the second client died on op_Addition with rc=1 before any
    table was printed.
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _extracted_client(root, "clientA")
        _extracted_client(root, "clientB")

        result = _run_rank(root)

    assert result.returncode == 0, (
        f"rank lane crashed on an extracted-only root:\n{result.stderr}"
    )
    assert "op_Addition" not in result.stderr
    # Both clients must be ranked - no silent partial table.
    assert "clientA" in result.stdout
    assert "clientB" in result.stdout
    assert "extracted" in result.stdout


def test_rank_lane_survives_single_archive_plus_extracted_client() -> None:
    """One archive + one extracted client: the same root cause, scalar arm.

    A foreach statement over a single-element collection also assigns a
    scalar (not an array) on PowerShell 5.1, so += crashed here too before
    the fix. The archive is a stub zip (PK header only): the script rejects
    it as 'no WoW.exe + Data client inside' but must still append its row.
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "stub.zip").write_bytes(b"PK\x05\x06" + b"\x00" * 18)
        _extracted_client(root, "clientA")

        result = _run_rank(root)

    assert result.returncode == 0, (
        f"rank lane crashed with one archive + one extracted client:\n{result.stderr}"
    )
    assert "op_Addition" not in result.stderr
    assert "clientA" in result.stdout
    assert "stub.zip" in result.stdout


_CSC = (
    Path(os.environ.get("WINDIR", r"C:\Windows"))
    / "Microsoft.NET" / "Framework64" / "v4.0.30319" / "csc.exe"
)
PRISTINE_EXE_BYTES = 4775986  # the 1.12.1.5875 acceptance size the script pins
BASE_MPQS = (
    "base", "dbc", "fonts", "interface", "misc", "model",
    "sound", "speech", "terrain", "texture", "wmo",
)


def _versioned_exe(dest: Path, file_version: str, size: int) -> None:
    """Build an exe whose Win32 FileVersion STRING is exactly ``file_version``.

    csc auto-generates the VERSIONINFO resource from the assembly attributes
    and only WARNS (CS1607, rc=0) on the comma form - which is exactly the
    shape of the genuine client's on-disk FileVersion. The binary is padded
    to ``size`` so the pristine byte-size check passes and ONLY the version
    check can flip the identity verdict.
    """
    src = dest.with_suffix(".cs")
    src.write_text(
        "using System.Reflection;\n"
        f'[assembly: AssemblyFileVersion("{file_version}")]\n'
        "class X { static void Main() {} }\n",
        encoding="ascii",
    )
    compile_result = subprocess.run(
        [str(_CSC), "-nologo", f"-out:{dest}", str(src)],
        capture_output=True, text=True, timeout=120,
    )
    if compile_result.returncode != 0:
        raise AssertionError(
            f"csc failed for FileVersion {file_version!r}:\n"
            f"{compile_result.stdout}\n{compile_result.stderr}"
        )
    blob = dest.read_bytes()
    assert len(blob) <= size, f"csc output {len(blob)} bytes, cannot pad to {size}"
    dest.write_bytes(blob + b"\x00" * (size - len(blob)))
    # Fixture self-check: the padding must not hide the version resource, and
    # the stored string must be verbatim (this is what the script reads).
    probe = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         f"[System.Diagnostics.FileVersionInfo]::GetVersionInfo('{dest}').FileVersion"],
        capture_output=True, text=True, timeout=120,
    )
    assert probe.returncode == 0, probe.stderr
    assert probe.stdout.strip() == file_version, (
        f"fixture FileVersion is {probe.stdout.strip()!r}, wanted {file_version!r}"
    )


def _full_client(root: Path, name: str, file_version: str) -> Path:
    """An extracted client that satisfies every identity check BUT the version."""
    client = root / name
    (client / "Data").mkdir(parents=True)
    for mpq in BASE_MPQS:
        (client / "Data" / f"{mpq}.MPQ").write_bytes(b"")
    _versioned_exe(client / "WoW.exe", file_version, PRISTINE_EXE_BYTES)
    return client


def test_rank_lane_accepts_genuine_comma_file_version() -> None:
    """The genuine 1.12.1.5875 client must rank clean, not WARN.

    WoW.exe FileVersion on the genuine client is '1, 12, 1, 5875' (comma
    form); '1.12.1.5875*' can never match it, so the client WARNed on its own
    rank row. Three clients pin the three edges of the normalization fix:
    comma-genuine must be OK (the defect), dot-form must stay OK (acceptance
    path intact), and a wrong comma version must still WARN (the fix is not
    a blanket pass).
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")
    if not _CSC.is_file():
        import pytest

        pytest.skip(".NET Framework csc.exe required for versioned fixtures")

    import tempfile

    # Format-Table -AutoSize -Wrap (redirected width ~120) can break a long
    # verdict row mid-phrase, so multi-word assertions stay whitespace-proof.
    with tempfile.TemporaryDirectory() as tmp:
        genuine = _full_client(Path(tmp), "commaGenuine", "1, 12, 1, 5875")
        result = _run_rank(genuine.parent)

        assert result.returncode == 0, f"rank lane crashed:\n{result.stderr}"
        assert "commaGenuine" in result.stdout
        assert re.search(r"already\s+extracted", result.stdout), result.stdout
        assert "WARN" not in result.stdout, (
            "genuine comma-form FileVersion WARNed on the rank table:\n"
            f"{result.stdout}"
        )
        assert "WoW.exe version is" not in result.stdout

    with tempfile.TemporaryDirectory() as tmp:
        dotted = _full_client(Path(tmp), "dotForm", "1.12.1.5875")
        result = _run_rank(dotted.parent)

        assert result.returncode == 0, f"rank lane crashed:\n{result.stderr}"
        assert "WARN" not in result.stdout, (
            "dot-form FileVersion regressed:\n" f"{result.stdout}"
        )

    with tempfile.TemporaryDirectory() as tmp:
        wrong = _full_client(Path(tmp), "commaWrong", "2, 4, 3, 8606")
        result = _run_rank(wrong.parent)

        assert result.returncode == 0, f"rank lane crashed:\n{result.stderr}"
        assert "WARN" in result.stdout, (
            "a wrong comma version slipped through the normalized match:\n"
            f"{result.stdout}"
        )
        assert re.search(
            r"WoW\.exe\s+version\s+is\s+'2,\s*4,\s*3,\s*8606'", result.stdout
        ), result.stdout


def test_source_lane_installs_named_archive() -> None:
    """-Source <archive> must reach the extraction lane, not die on $null.

    The lane used to drop the raw FileInfo from Get-Item into code that
    reads verdict-row properties; FileInfo has neither Archive nor Format,
    so Get-ArchiveFormat received $null and the run died with 'Empty path
    name is not legal.' (rc=1) before 7-Zip ever ran. The fix normalizes
    -Source into the same @{Archive; Format} shape the auto-pick produces.
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        staging = root / "staging"
        staging.mkdir()
        (staging / "stub.zip").write_bytes(b"PK\x05\x06" + b"\x00" * 18)
        install = root / "Installed"

        result = _run_install(staging, install, staging / "stub.zip")

        # Empty zip: 7-Zip extracts nothing (exit 0), identity then reports
        # the missing WoW.exe instead of crashing - and the slug dir exists.
        assert (install / "stub").is_dir(), (
            f"no slug dir created under InstallRoot:\n{result.stdout}"
        )

    assert result.returncode == 0, (
        f"-Source archive lane crashed:\n{result.stderr}"
    )
    assert "Empty path name" not in result.stderr
    assert re.search(r"Extracting\s+.*stub\.zip", result.stdout), result.stdout
    assert re.search(r"EXTRACTED\s+with\s+findings", result.stdout), result.stdout


def test_source_lane_accepts_extracted_client_folder() -> None:
    """-Source <folder> is a documented lane ('install one specific archive/folder').

    The leaf-only Test-Path used to reject an existing folder with 'Source
    not found' (rc=1). The fix routes folder sources through the
    extracted-client lane: identity verdict in place, nothing extracted, no
    InstallRoot created. The client sits OUTSIDE the staging root so any
    'already extracted' text can only come from the -Source lane, never
    from the rank table.
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        staging = root / "staging"
        staging.mkdir()
        client = _extracted_client(root, "clientA")
        install = root / "Installed"

        result = _run_install(staging, install, client)

        assert not install.exists(), (
            "the folder -Source lane must not create or touch an InstallRoot"
        )

    assert result.returncode == 0, (
        f"-Source folder lane crashed:\n{result.stderr}"
    )
    assert "Source not found" not in result.stderr + result.stdout
    assert re.search(
        r"clientA\s+already\s+extracted,\s+folder-import\s+path", result.stdout
    ), result.stdout


def test_unsluggable_archive_name_never_wipes_install_root() -> None:
    """P1 pin: Get-Slug('###') is '' and Join-Path then returns InstallRoot itself.

    An archive basename with no [A-Za-z0-9_.-] at all (e.g. a CJK-named zip
    with a client inside, which ranks OK) slugged to '', so $destination
    collapsed onto $InstallRoot and the no-silent-merges Remove-Item
    -Recurse -Force wiped every previously installed client before
    extracting the new archive straight into InstallRoot. The fix falls
    back to the fixed slug 'client'; the auto-pick lane (no -Source) is the
    reachable arm because verdict rows already carry Format.
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _client_zip(root / "###.zip")
        install = root / "Installed"
        previous = install / "previous-client"
        previous.mkdir(parents=True)
        (previous / "marker.txt").write_text("keepme")

        # No -Source: the auto-pick must settle on ###.zip (the only OK row).
        result = _run_install(root, install)

        assert (previous / "marker.txt").read_text() == "keepme", (
            "the empty slug collapsed $destination onto $InstallRoot and "
            "Remove-Item wiped the previously installed clients:\n"
            f"{result.stdout}"
        )
        assert (install / "client" / "WoW.exe").is_file(), (
            "the fallback slug 'client' was not used for the install dir:\n"
            f"{result.stdout}"
        )

    assert result.returncode == 0, f"install lane crashed:\n{result.stderr}"
    assert re.search(r"installing\s+as\s+'client'", result.stdout), result.stdout


def test_rank_lane_pins_in_app_import_verdict_shape() -> None:
    """A plain client archive ranks 'OK - imports in-app (<format>)'.

    Pins the final else the rar4/rar5 elseif collapsed into: the removed
    elseif emitted a byte-identical string, so zip, 7z and RAR alike funnel
    into one interpolated verdict ('imports in-app ($format)').
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _client_zip(root / "client.zip")

        result = _run_rank(root)

    assert result.returncode == 0, f"rank lane crashed:\n{result.stderr}"
    assert "client.zip" in result.stdout
    assert re.search(r"imports\s+in-app\s+\(zip\)", result.stdout), (
        f"the client zip did not rank as an in-app import:\n{result.stdout}"
    )
    assert "REJECTED" not in result.stdout


# --- F12: -Flavor parameterization (S5 item 8 host fixture) -----------------------

def _flavor_block(flavor_id: str) -> dict:
    """The S5.1-registered identity the ps1 must resolve per flavor — read
    from the manifest itself, so these pins travel with the data (no flavor
    literals in this file)."""
    import json

    manifest = json.loads(
        (ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8")
    )
    return next(f for f in manifest["flavors"] if f["id"] == flavor_id)


def _flavor_client(root: Path, name: str, flavor_id: str) -> Path:
    """An extracted client shaped exactly for the flavor: WoW.exe at the
    registered size carrying the registered FileVersion (comma form — the
    genuine-client stamp), plus the registered MPQ exact set with its
    Data\\<locale>\\ segments."""
    block = _flavor_block(flavor_id)
    version = block["version_resource"]
    comma_version = ", ".join(version.split("."))
    client = root / name
    for relative in block["mpq_set"]["required"]:
        target = client / "Data" / Path(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"")
    _versioned_exe(client / "WoW.exe", comma_version, block["client_binary"]["known_exe_size_bytes"])
    return client


def test_flavor_argument_ranks_registered_tbc_client_clean() -> None:
    """-Flavor tbc verifies against the tbc S5.1 identity, not vanilla's.

    The same locale-segmented client that WARNs under the vanilla default
    (wrong exe size, wrong version resource, missing flat MPQs) must rank
    clean under -Flavor tbc — the parameterization is data-driven from
    schemas/flavor.json, so a newly registered flavor is a -Flavor argument,
    never a code edit (F12).
    """
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")
    if not _CSC.is_file():
        import pytest

        pytest.skip(".NET Framework csc.exe required for versioned fixtures")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _flavor_client(root, "tbcClient", "tbc")

        as_tbc = _run_rank(root, flavor="tbc")
        assert as_tbc.returncode == 0, f"rank lane crashed:\n{as_tbc.stderr}"
        assert "tbcClient" in as_tbc.stdout
        assert "WARN" not in as_tbc.stdout, (
            "the registered tbc client WARNed under -Flavor tbc:\n"
            f"{as_tbc.stdout}"
        )

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _flavor_client(root, "tbcClient", "tbc")

        as_vanilla = _run_rank(root)

        assert as_vanilla.returncode == 0, f"rank lane crashed:\n{as_vanilla.stderr}"
        assert "WARN" in as_vanilla.stdout, (
            "a tbc-shaped client silently ranked clean under the vanilla "
            "default:\n"
            f"{as_vanilla.stdout}"
        )


def test_flavor_argument_ranks_registered_wotlk_client_clean() -> None:
    """Same pin on the wotlk identity (16-entry set incl. the lichking
    locale pair) — the second registered lane proves the loop, not a
    tbc-specific accident."""
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")
    if not _CSC.is_file():
        import pytest

        pytest.skip(".NET Framework csc.exe required for versioned fixtures")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _flavor_client(root, "wotlkClient", "wotlk")

        result = _run_rank(root, flavor="wotlk")
        assert result.returncode == 0, f"rank lane crashed:\n{result.stderr}"
        assert "wotlkClient" in result.stdout
        assert "WARN" not in result.stdout, (
            "the registered wotlk client WARNed under -Flavor wotlk:\n"
            f"{result.stdout}"
        )


def test_unknown_flavor_is_refused_naming_the_valid_set() -> None:
    """Fail-closed: -Flavor <unknown> refuses with the valid set named (the
    tools/flavor_layout.resolve contract), never a silent vanilla default."""
    if not _powershell_available() or not _seven_zip_available():
        import pytest

        pytest.skip("windows powershell + 7-Zip required")

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        result = _run_rank(Path(tmp), flavor="cataclysm")

    assert result.returncode != 0
    combined = result.stderr + result.stdout
    assert "unknown flavor 'cataclysm'" in combined
    # PowerShell's error formatter ellipsizes long messages, so pin the
    # surviving tokens: the fail-closed refusal names the valid set.
    assert "valid flavors" in combined
    assert "vanilla" in combined
