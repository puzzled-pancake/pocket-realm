"""W6-N1 code-red round 1 pins (the reviewer-proposed tests).

Holds the wave's decision-record, launch-config, dependency-resolution and
wiki claims against the live tree:

- R1-1/R3-2/R4-2/R5-7/R6-1/R7-3: the secp256k1-kmp version recorded in
  Decision D91 equals the version the version catalog actually pins (the
  recorded 0.17.3 could never resolve on the pinned JDK-17 toolchain).
- R3-1/R7-1: BOTH desktop production launch lanes (`run` via
  applicationDefaultJvmArgs and the jpackage image via --java-options)
  carry the secp256k1 temp-dir pin, so the JNI native never extracts to
  %TEMP% on C:.
- R4-1: the Android debug runtime classpath resolves the secp256k1
  JNI-android AAR (without it the device verify path throws and every
  listing is refused — a permanent honest-looking empty browser).
- R6-2: the wiki names the Public realms surface, the opt-in publish
  switch, and the relay-sees-IP fact on the three touched pages.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def test_d91_records_the_pinned_secp256k1_version():
    toml = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    match = re.search(r'secp256k1Kmp\s*=\s*"([^"]+)"', toml)
    assert match, "libs.versions.toml lost the secp256k1Kmp version ref"
    pinned = match.group(1)
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    d91 = re.search(r"^\| D91 \|(.*?)\n", decisions, re.MULTILINE)
    assert d91, "the D91 row vanished from the Decision Log"
    assert f"secp256k1-kmp {pinned} is the wave's one real dependency" in d91.group(1), (
        "D91 records a secp256k1-kmp version the tree does not pin "
        f"(catalog pins {pinned}) — a future wave reading D91 would "
        "'restore' an unresolvable version; the record must state the "
        "shipped pin (R1-1/R3-2/R4-2/R5-7/R6-1/R7-3)"
    )
    # the recorded rationale for the rejection must stay in the row
    assert "JVM 21" in d91.group(1) and "JDK-17" in d91.group(1)


def test_desktop_launch_lanes_pin_the_secp256k1_tmpdir():
    build = (ROOT / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    # the run lane (applicationDefaultJvmArgs): the pin rides the args list
    # (slice past the block's opening — a naive .*?\) match dies on the
    # comment's own parenthesis)
    args_start = build.find("applicationDefaultJvmArgs")
    assert args_start >= 0, "applicationDefaultJvmArgs vanished"
    args_block = build[args_start:args_start + 900]
    assert "-Dfr.acinq.secp256k1.tmpdir=" in args_block, (
        "the desktop `run` lane lost the secp256k1 temp-dir pin — the first "
        "host Schnorr sign would extract the JNI native to %TEMP% on C: "
        "(hard rule 1; R3-1/R7-1)"
    )
    # the jpackage lane (--java-options)
    assert re.search(
        r'"--java-options",\s*"-Dfr\.acinq\.secp256k1\.tmpdir=[^"]+"',
        build,
    ), "the jpackage image lost the secp256k1 temp-dir pin (R3-1/R7-1)"
    # and the pin roots at E: (hard rule 1's actual invariant)
    for pin in re.findall(r"-Dfr\.acinq\.secp256k1\.tmpdir=(\S+)", build):
        assert pin.startswith("E:/"), f"the secp256k1 tmpdir pin must live on E: (got {pin})"


def test_android_runtime_classpath_resolves_the_secp256k1_jni_aar():
    import os
    import subprocess

    env = dict(os.environ)
    gradle = ROOT / "android" / "gradlew.bat"
    result = subprocess.run(
        [str(gradle), "-q", "-PpocketAbi=arm64-v8a",
         ":app:dependencies", "--configuration", "debugRuntimeClasspath"],
        capture_output=True, text=True, cwd=ROOT / "android", env=env, timeout=600,
    )
    tree = result.stdout + result.stderr
    assert result.returncode == 0, (
        f"the android dependency walk failed: {tree[-400:]}"
    )
    assert "secp256k1-kmp-jni-android" in tree, (
        "the Android debug runtime classpath resolves NO secp256k1 JNI "
        "artifact — on a device getSecpk256k1() throws and every listing "
        "verify silently refuses (R4-1 serious); the jni-android AAR must "
        "be an implementation dependency"
    )


def test_every_secp256k1_coordinate_pins_the_catalog_version():
    """R1-r2-1: the jni-android AAR must ride the owning catalog ref — a
    bare literal desyncs silently on a catalog bump (mixed-version JNI ==
    the R4-1 on-device failure class)."""
    toml = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    pinned = re.search(r'secp256k1Kmp\s*=\s*"([^"]+)"', toml).group(1)
    for rel in (
        "android/app/build.gradle.kts",
        "android/gradle/libs.versions.toml",
        "desktop/build.gradle.kts",
    ):
        text = (ROOT / rel).read_text(encoding="utf-8")
        for coordinate in re.findall(r'"fr\.acinq\.secp256k1:([\w-]+):([\w.]+)"', text):
            name, version = coordinate
            assert version == pinned, (
                f"{rel}: fr.acinq.secp256k1:{name} pins {version} but the "
                f"catalog pins {pinned} — a mixed-version secp256k1 set is "
                "the R4-1 silent-on-device failure class"
            )
    # the android AAR rides the catalog (no bare literal anywhere)
    app_build = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    assert "libs.secp256k1.kmp.jni.android" in app_build


def test_publish_copy_enumerates_fields_and_discloses_the_link_hostname():
    """R7-r4-1/R6-4-2: the consent copy must enumerate what the listing
    actually carries (name, description/MOTD, flavor+build, timeframe,
    rates, players) AND disclose the optional status-page hostname — the
    frozen schema's sanctioned statusPageUrl IS a hostname, so a blanket
    'no hostname ever' claim overstates privacy."""
    pub = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/nostr/"
           "NostrListingPublisher.kt").read_text(encoding="utf-8")
    start = pub.index("private const val OFF_COPY")
    end = pub.index('your IP."', start)
    off_copy = pub[start:end]
    assert "description (MOTD)" in off_copy, "OFF_COPY must name the description/MOTD field"
    assert "timeframe stage" in off_copy, "OFF_COPY must name the timeframe stage"
    assert "status-page link" in off_copy, (
        "OFF_COPY must disclose the optional status-page hostname exception "
        "(R7-r4-1)"
    )
    # the precision predicate: the copy claims no IP (true) and never a
    # blanket 'no hostname' (the status-page link IS one)
    assert "No IP address is ever in a listing" in off_copy
    assert "no IP or hostname" not in off_copy


def test_wiki_discloses_the_status_page_hostname_exception():
    """R7-r4-1: the wiki pages must either disclose the optional
    status-page hostname or stop claiming 'no hostname'."""
    home = (ROOT / "docs/wiki/Home-Screen.md").read_text(encoding="utf-8")
    assert "no IP address" in home
    assert "status-page link is a public trycloudflare hostname" in home, (
        "Home-Screen.md's listing paragraph must disclose the optional "
        "status-page hostname exception (R7-r4-1)"
    )
    storage = (ROOT / "docs/wiki/Data-Storage-and-Privacy.md").read_text(encoding="utf-8")
    assert "never carry your IP address" in storage
    assert "status-page link" in storage, (
        "Data-Storage-and-Privacy.md must disclose the status-page hostname "
        "exception (R7-r4-1)"
    )


def test_wiki_names_the_public_realms_surface_and_disclosure():
    home = (ROOT / "docs/wiki/Home-Screen.md").read_text(encoding="utf-8")
    assert "Public realms" in home, "Home-Screen.md no longer names the Public realms surface (R6-2)"
    assert "opt-in" in home.lower() or "off by default" in home.lower()
    assert "Nostr relays see" in home or "see your IP" in home
    accounts = (ROOT / "docs/wiki/Accounts-Security-and-Networking.md").read_text(encoding="utf-8")
    assert "Nostr relays" in accounts and "see your IP address" in accounts, (
        "Accounts-Security-and-Networking.md must disclose the relay browser "
        "and the relay-sees-IP fact (R6-2)"
    )
    storage = (ROOT / "docs/wiki/Data-Storage-and-Privacy.md").read_text(encoding="utf-8")
    assert "Nostr relays" in storage and "see your IP address" in storage, (
        "Data-Storage-and-Privacy.md's Network-use section must carry the "
        "outbound relay traffic + relay-sees-IP disclosure (R6-2)"
    )
    # R6-2-2: Overview's "not a public Internet realm service" claim must
    # stay qualified against the opt-in Public realms lane the same wave
    # shipped (Home-Screen carries the disclosure-bearing paragraph).
    overview = (ROOT / "docs/wiki/Overview.md").read_text(encoding="utf-8")
    assert "not a public Internet realm service by default" in overview, (
        "Overview.md's not-a-public-service claim lost its opt-in "
        "qualification — it contradicts Home-Screen's Public realms "
        "publish-switch paragraph (R6-2-2)"
    )
    assert "Public realms" in overview and "off by default" in overview


ROOT = Path(__file__).resolve().parent.parent


def test_focused_nostr_logs_show_executed_test_tasks():
    """R6-2/R4r6-1: a focused evidence log whose test task reports
    UP-TO-DATE/FROM-CACHE evidences nothing — every archived focused
    nostr log under out/completion/w6/ must show the test task EXECUTED."""
    import glob

    logs = sorted(glob.glob(str(ROOT / "out/completion/w6/*focused*.log")))
    assert logs, "no focused nostr logs archived under out/completion/w6/"
    bad = []
    executed_re = re.compile(
        r"^> Task :(test|app:testDebugUnitTest)$", re.MULTILINE
    )
    up_to_date_re = re.compile(
        r"^> Task :(test|app:testDebugUnitTest) UP-TO-DATE", re.MULTILINE
    )
    from_cache_re = re.compile(
        r"^> Task :(test|app:testDebugUnitTest) FROM-CACHE", re.MULTILINE
    )
    for path in logs:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
        # R6-2: a log whose header carries the NO-OP marker is a retained
        # record of a mislabeled attempt, not evidence — exempted.
        if "this archived run was a NO-OP" in text:
            continue
        executed = executed_re.search(text)
        skipped = up_to_date_re.search(text) or from_cache_re.search(text)
        if skipped or not executed:
            bad.append(
                f"{Path(path).name} (executed-line={bool(executed)}, "
                f"skipped={bool(skipped)})"
            )
    assert not bad, (
        "focused nostr logs that evidence no executed test task:\n  "
        + "\n  ".join(bad)
        + "\nre-run with cleanTest/--rerun-tasks so the archived log shows "
        "the test task EXECUTED (R6-2/R4r6-1)"
    )


def test_off_copy_discloses_the_status_page_hostname_exception():
    """R7-r4-1/R6-4-2: the consent copy enumerates the real published
    fields (incl. description/MOTD and timeframe) and discloses the
    optional status-page hostname — never a blanket 'no hostname ever'."""
    pub = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/nostr/"
           "NostrListingPublisher.kt").read_text(encoding="utf-8")
    start = pub.index("private const val OFF_COPY")
    end = pub.index('your IP."', start)
    off_copy = pub[start:end]
    assert "description (MOTD)" in off_copy
    assert "timeframe stage" in off_copy
    assert "status-page link" in off_copy
    assert "No IP address is ever in a listing" in off_copy
    assert "no IP or hostname" not in off_copy
