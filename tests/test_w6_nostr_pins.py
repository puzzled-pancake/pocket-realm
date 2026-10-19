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
    end = pub.index('may learn it."', start) + len('may learn it."')
    off_copy = pub[start:end]
    assert "description (MOTD)" in off_copy, "OFF_COPY must name the description/MOTD field"
    assert "timeframe stage" in off_copy, "OFF_COPY must name the timeframe stage"
    assert "status-page link" in off_copy, (
        "OFF_COPY must disclose the optional status-page hostname exception "
        "(R7-r4-1)"
    )
    # the precision predicate: the copy claims no IP (true) and never a
    # blanket 'no hostname' (the status-page link IS one); the N3
    # disclosure names the iroh relay + the direct-connection leg.
    assert "No IP address is ever in a listing" in off_copy
    assert "no IP or hostname" not in off_copy
    assert "iroh home relay see your IP" in off_copy, (
        "OFF_COPY must disclose that the iroh home relay sees the host IP "
        "(the N3 gateway registers there)"
    )
    assert "directly" in off_copy and "may learn it" in off_copy, (
        "OFF_COPY must disclose that a directly-connected player may learn "
        "the host IP (plan ruling 3)"
    )


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
    end = pub.index('may learn it."', start) + len('may learn it."')
    off_copy = pub[start:end]
    assert "description (MOTD)" in off_copy
    assert "timeframe stage" in off_copy
    assert "status-page link" in off_copy
    assert "No IP address is ever in a listing" in off_copy
    assert "no IP or hostname" not in off_copy


def test_relay_only_switch_copy_names_the_transport_internal_direct_path():
    """R7-N3-6: the switch copy must NOT claim relay-only keeps the
    connection on the relay unconditionally — iroh 1.1.0 has no
    path-selection knob (D92(2)/D93(5)), so the copy names the
    transport-internal direct-path possibility."""
    screen = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
              "RealmBrowserScreen.kt").read_text(encoding="utf-8")
    assert "the dial is pinned to the" in screen and "home relay" in screen
    assert "iroh may still move" in screen and "direct path" in screen
    assert "keeps that connection on the relay" not in screen


def test_wiki_relay_only_sentence_names_the_transport_internal_direct_path():
    """R7-N3-6: the wiki sentence must not claim the switch keeps the
    connection on the relay unconditionally."""
    accounts = (ROOT / "docs/wiki/Accounts-Security-and-Networking.md")         .read_text(encoding="utf-8")
    assert "pins the dial to that relay" in accounts
    assert "may still move an established connection onto a direct path"         in accounts
    assert "keeps that connection on the relay" not in accounts


def test_iroh_join_client_resolves_through_the_managed_generation_seam():
    """R6-N3-1: the iroh join launches the client through the SAME
    managed-generation-first, identity-gated resolution the local lane
    uses (backend.resolveClientDir) — never the legacy settings clientDir
    alone, which could spawn a wrong-flavor or unmanaged binary."""
    model = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/"
             "DesktopAppModel.kt").read_text(encoding="utf-8")
    assert "backend.resolveClientDir()" in model, (
        "launchIrohJoinClient must resolve through the managed-generation "
        "seam"
    )
    join_start = model.index("private fun launchIrohJoinClient")
    join_fn = model[join_start:model.index(chr(10) + "    /**", join_start)]
    assert "DesktopSettingsStore" not in join_fn, (
        "the join client launch must not read the legacy settings "
        "clientDir directly"
    )
    assert "clientDirOverride = resolvedDir" in join_fn


def test_gateway_host_role_carries_no_rewrite_symbols():
    """R2-2-4: the charter invariant — the rewrite happens ONLY in the
    joiner gateway. The host role references no joiner-side rewrite
    symbols and carries no realmlist-config write path (source-anchored;
    the joiner half is pinned by RealmListRewriteTest + the captured-
    payload greps)."""
    host = (ROOT / "gateway/src/main/kotlin/com/pocketrealm/gateway/"
            "HostGateway.kt").read_text(encoding="utf-8")
    for banned in ("RealmListRewrite", "AuthStreamFilter",
                   "JOINER_WORLD_TARGET", "realmlist.wtf"):
        assert banned not in host, (
            f"the host gateway must not reference {banned!r} — the "
            "rewrite lives ONLY in the joiner gateway"
        )


def test_join_state_error_lane_is_rendered():
    """R3-2: a post-READY join failure must render the actionable code +
    detail in the browser (the stop button and 'Connected' copy must
    never silently vanish)."""
    screen = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
              "RealmBrowserScreen.kt").read_text(encoding="utf-8")
    assert "JoinState.Error" in screen
    assert "realm-browser-join-error" in screen
    # Round-4 R6-4-3: the rendered string is the manager's extracted
    # joinErrorLine (model-level pinned in IrohGatewayWatcherTest) — the
    # composable must route through it, never a drifted inline copy.
    assert "IrohGatewayManager.joinErrorLine(" in screen
    manager = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/iroh/"
               "IrohGatewayManager.kt").read_text(encoding="utf-8")
    assert '"Internet join failed: $code: $detail"' in manager


def test_n3_canonical_logs_record_the_clean_and_test_executions():
    """R5-2/R3-r3-3: the N3 canonical-suite logs must show BOTH the
    clean legs and the plain test tasks EXECUTED (never UP-TO-DATE), and
    the counts must come from the committed counts JSON artifacts (which
    the regen scripts write from the XML trees).

    D94 (round-3 probe): the :pocketrealm-llm:cleanTest leg is EXEMPT
    from the EXECUTED demand because it is structurally incapable of
    carrying evidence — under AGP the llm module's unit tests compile
    into the VARIANT task (compileDebugUnitTestKotlin →
    testDebugUnitTest), so the plain `test` task executes over an empty
    source set and its build/test-results/test dir stays EMPTY forever;
    a Gradle Delete task over an empty target reports UP-TO-DATE even
    under --rerun-tasks (probed on the round-3 canonical re-run: the
    global force re-executed all 55 other tasks and cleanTest still
    reported UP-TO-DATE). The pin therefore demands the five legs that
    CAN carry evidence EXECUTED, and refuses the one shortcut that
    would make even the empty leg vacuous: a FROM-CACHE cleanTest."""
    import json

    for rel, tasks in (
        ("out/completion/w0/n3-canonical-android.log",
         (":app:cleanTestDebugUnitTest", ":pocketrealm-llm:cleanTestDebugUnitTest",
          ":app:testDebugUnitTest", ":pocketrealm-llm:testDebugUnitTest",
          ":pocketrealm-llm:test")),
        ("out/completion/w0/n3-canonical-desktop.log",
         (":cleanTest", ":test")),
    ):
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for task in tasks:
            executed = re.search(
                rf"^> Task {re.escape(task)}\s*$", text, re.MULTILINE)
            assert executed, (
                f"{rel} must record the EXECUTED (not UP-TO-DATE/FROM-CACHE) "
                f"{task} line — the canonical evidence is otherwise vacuous"
            )
        # D94: the structurally-empty llm clean leg may be UP-TO-DATE but
        # must never ride the build cache.
        from_cache = re.search(
            r"^> Task :pocketrealm-llm:cleanTest FROM-CACHE", text, re.MULTILINE)
        assert not from_cache, (
            f"{rel} records :pocketrealm-llm:cleanTest FROM-CACHE — "
            "the canonical run must not take the build-cache shortcut "
            "(D94; run with --no-build-cache)")
    # The counts derive from the committed artifacts, not from prose.
    for name in ("android-unit-counts.json", "desktop-test-counts.json"):
        artifact = json.loads(
            (ROOT / "out/completion/w0" / name).read_text(encoding="utf-8"))
        assert artifact["failures"] == 0, f"{name} records failures"
        assert artifact["tests"] > 0 and artifact["classes"] > 0


def test_wave_exit_n3_focused_numbers_equal_the_archived_counts():
    """R6-4-1/R4-N3-1/R3-2/R5-N3-2: every focused count the exit record
    cites must EQUAL the cited log's own N3-FOCUSED-COUNTS line (the
    119-vs-64 class: a stale sentence a fresher archive can contradict),
    and the android focused archive must cover the FULL class set its
    --tests filter names — the archived classes count equals the tree's
    @Test census under discovery/iroh + discovery/nostr +
    RealmBrowserModelTest, so a narrowed re-run can never stand in for
    the archived focused measurement."""
    exit_record = ROOT / "out/completion/w6/WAVE-EXIT.md"
    if not exit_record.is_file():
        return
    text = exit_record.read_text(encoding="utf-8")
    cited = re.search(
        r"android focused:.*?= (\d+) tests / 0 failed(?: / (\d+) classes)?"
        r"\s*\((\S+\.log)",
        text,
        re.DOTALL,
    )
    assert cited, "the exit record lost its android focused sentence"
    log_path = ROOT / "out/completion/w6" / cited.group(3)
    assert log_path.is_file(), f"the cited focused log {cited.group(3)} is missing"
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    counts = re.search(
        r"# N3-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)",
        log_text,
    )
    assert counts, f"{cited.group(3)} lost its N3-FOCUSED-COUNTS line"
    assert int(counts.group(1)) == int(cited.group(1)), (
        f"WAVE-EXIT cites {cited.group(1)} android focused tests but "
        f"{cited.group(3)} records tests={counts.group(1)} — correct the "
        "exit record to the measured number (R6-4-1)"
    )
    if cited.group(2) is not None:
        assert int(cited.group(2)) == int(counts.group(2)), (
            "the exit record's cited classes disagree with the archive"
        )
    assert int(counts.group(3)) == 0, "the cited focused run records failures"
    # R5-N3-2: the archive covers the FULL filter set — the archived
    # classes count equals the tree census under the three filters.
    census = 0
    for pattern in (
        "android/app/src/test/java/com/pocketrealm/discovery/iroh/*.kt",
        "android/app/src/test/java/com/pocketrealm/discovery/nostr/*.kt",
        "android/app/src/test/java/com/pocketrealm/ui/RealmBrowserModelTest.kt",
    ):
        for source in sorted(ROOT.glob(pattern)):
            if "@Test" in source.read_text(encoding="utf-8", errors="replace"):
                census += 1
    assert int(counts.group(2)) == census, (
        f"{cited.group(3)} records classes={counts.group(2)} but the tree "
        f"holds {census} @Test classes under the cited filters — the "
        "archived run is narrower than its own filter (R5-N3-2); re-run "
        "the focused set and re-archive"
    )
    # R6-N3-r6-1: the DESKTOP focused sentence is held to the same
    # census standard — the cited classes count must equal the tree
    # census of the desktop-lane discovery/iroh + desktop/iroh +
    # discovery/nostr + desktop/nostr @Test classes, killing the
    # narrowed-re-run-under-a-wide-label class on this lane too.
    desktop_cited = re.search(
        r"desktop code-red focused.*?= (\d+) tests /\s*0 failed / "
        r"(\d+) classes\s*\((\S+\.log)",
        text,
        re.DOTALL,
    )
    assert desktop_cited, "the exit record lost its desktop focused sentence"
    desktop_census = 0
    for pattern in (
        "android/app/src/test/java/com/pocketrealm/discovery/iroh/*.kt",
        "android/app/src/test/java/com/pocketrealm/discovery/nostr/*.kt",
        "desktop/src/test/kotlin/com/pocketrealm/desktop/iroh/*.kt",
        "desktop/src/test/kotlin/com/pocketrealm/desktop/nostr/*.kt",
    ):
        for source in sorted(ROOT.glob(pattern)):
            if "@Test" in source.read_text(encoding="utf-8",
                                           errors="replace"):
                desktop_census += 1
    desktop_path = ROOT / "out/completion/w6" / desktop_cited.group(3)
    assert desktop_path.is_file(), (
        f"the cited desktop focused log {desktop_cited.group(3)} is missing"
    )
    desktop_text = desktop_path.read_text(encoding="utf-8", errors="replace")
    # W6-N4 round 3 (R6-N4-r3-2): the desktop focused lane's TRACKED
    # emitter (desktop/build.gradle.kts Test-task doLast) prints the
    # N4-FOCUSED-COUNTS marker — the era label moved with the mechanism,
    # so this pin matches the marker the lane actually emits (either
    # era-labeled line remains acceptable for N3-era archives).
    desktop_counts = re.search(
        r"# N[34]-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)",
        desktop_text,
    )
    assert desktop_counts, (
        f"{desktop_cited.group(3)} lost its FOCUSED-COUNTS line"
    )
    assert int(desktop_cited.group(1)) == int(desktop_counts.group(1)), (
        f"WAVE-EXIT cites {desktop_cited.group(1)} desktop focused tests "
        f"but {desktop_cited.group(3)} records "
        f"tests={desktop_counts.group(1)} (R6-N3-r6-1)"
    )
    assert int(desktop_counts.group(2)) == desktop_census, (
        f"{desktop_cited.group(3)} records "
        f"classes={desktop_counts.group(2)} but the desktop-lane census "
        f"is {desktop_census} — a narrowed run cannot stand under the "
        "wide label (R6-N3-r6-1)"
    )
    assert int(desktop_counts.group(3)) == 0, "the desktop archive is red"


def test_n3_focused_logs_carry_the_counts_lines():
    """R4-3-3: the archived N3 focused-run logs must carry the
    N3-FOCUSED-COUNTS lines (per-test output or a counts artifact — the
    vacuous-log class never recurs on the N3 evidence)."""
    for rel in (
        "out/completion/w6/n3-android-focused.log",
        "out/completion/w6/n3-desktop-codered.log",
        "out/completion/w6/n3-round2-gateway.log",
    ):
        path = ROOT / rel
        if not path.is_file():
            continue
        assert "N3-FOCUSED-COUNTS" in path.read_text(encoding="utf-8",
                                                    errors="replace"), (
            f"{rel} carries no N3-FOCUSED-COUNTS line — archive the run "
            "with its counts"
        )


def test_realmlist_kdoc_cites_resolve():
    """R5-r6-1: the rewrite KDoc promises citation precision ("each
    documented ... against its citing source line") — every
    ByteBuffer.h:<n> cite in RealmListRewrite.kt must resolve into the
    real file at that line. The :115 raw-append cite was wrong (the
    append lives at :466 in all three trees)."""
    kdoc = (ROOT / "android/app/src/main/java/com/pocketrealm/discovery/"
            "iroh/RealmListRewrite.kt").read_text(encoding="utf-8")
    buffer = (ROOT / "native/cmangos/src/shared/Util/ByteBuffer.h"
              ).read_text(encoding="utf-8", errors="replace").split(chr(10))
    # Each cite carries its OWN verified contract (the raw-append cite
    # and the C-string cite are different lines with different shapes):
    must_contain = {
        "466": "append((uint8*)&value",  # the template append (R5-r6-1)
        "173": "operator<<(const std::string",  # the CStr bytes+NUL shape
    }
    for line_no in re.findall(r"ByteBuffer\.h:(\d+)", kdoc):
        assert line_no in must_contain, (
            f"RealmListRewrite.kt cites ByteBuffer.h:{line_no} which has "
            "no verified contract in this pin — add the cite's expected "
            "content after checking the source (R5-r6-1)"
        )
        cited = buffer[int(line_no) - 1]
        assert must_contain[line_no] in cited, (
            f"RealmListRewrite.kt cites ByteBuffer.h:{line_no} but that "
            f"line reads {cited.strip()!r} - the citation-precision "
            "contract is violated (R5-r6-1)"
        )


def test_wiki_scopes_the_network_change_sentence_and_documents_reconnect():
    """W6-N4 round 6 (R6-N4-r6-2), the N1 R6-2 wiki-pin pattern: the
    LAN restart sentence must be scoped to the LAN lane, and the
    internet lane's verified reconnect behavior must be documented
    (tunnel loss is loud; re-join the SAME listing; the host does not
    restart and no new listing is required)."""
    wiki = ROOT / "docs/wiki/Accounts-Security-and-Networking.md"
    text = wiki.read_text(encoding="utf-8")
    assert "On the LAN lane, if the host's active interface or address changes" in text, (
        "the LAN restart sentence must be scoped to the LAN lane"
    )
    assert "Internet play through the iroh gateway behaves differently" in text, (
        "the internet lane's reconnect behavior must be documented"
    )
    assert "rejoining the SAME Public-realms listing reconnects" in text, (
        "the re-join-the-same-listing remedy must be documented"
    )
    assert "no new listing is needed" in text, (
        "the no-new-listing property must be documented"
    )
    # the old unconditional sentence must not return in unscoped form
    assert not re.search(
        r"(?m)^If the host's active interface or address changes, the session "
        r"is no longer", text), (
        "the unscoped restart sentence came back"
    )


def test_wiki_import_page_names_every_qualified_flavor():
    """W6-N5 round 2 (R6-N5-r2-3), the wiki-truth row pattern: the import
    page documents the flow every qualified lane uses, so it must name the
    three flavors (or scope its vanilla sentence to the vanilla lane) — a
    vanilla-only import page is stale since the W4 qualification flip."""
    wiki = ROOT / "docs/wiki/Game-Files-and-Import.md"
    text = wiki.read_text(encoding="utf-8")
    assert "Vanilla 1.12.1 build 5875, TBC 2.4.3 build 8606, or WotLK 3.3.5a build 12340" in text, (
        "the import page must name all three qualified flavors' clients — "
        "the flow accepts each of them (R6-N5-r2-3)"
    )
    assert "the same flow serves every qualified lane" in text, (
        "the import page must say the documented flow serves every lane"
    )


def test_wiki_controls_binding_reference_is_lane_scoped():
    """W6-N5 round 2 (R6-N5-r2-4): the binding-reference sentence must be
    scoped to the Vanilla lane — the tbc/wotlk catalogs deliberately ship
    no stock-default key data (WowFlavorCatalogs), so 'all 211 stock 1.12.1
    binding IDs' is false on those lanes."""
    wiki = ROOT / "docs/wiki/Controls.md"
    text = wiki.read_text(encoding="utf-8")
    assert "On the Vanilla lane the reference holds all 211 stock WoW 1.12.1 binding IDs" in text, (
        "the binding-reference sentence must be scoped to the Vanilla lane"
    )
    assert "deliberately ship no stock-default key data" in text, (
        "the honest-empty posture of the tbc/wotlk reference must be documented"
    )
    assert not re.search(
        r"a searchable reference containing all 211 stock WoW 1\.12\.1 binding IDs",
        text,
    ), "the unscoped binding-reference sentence came back"
    # R6-N5-r3-1: the round-2 fix's own "starts empty" clause was FALSE —
    # the tbc/wotlk references list 214/251 captured action IDs; only the
    # stock-default KEY data is empty. The wording is scoped, and the
    # sizes are pinned to the catalogs in WowFlavorCatalogsTest.
    assert "the reference starts empty" not in text, (
        "the false emptiness clause came back — scope the emptiness to "
        "the stock-default KEY data (R6-N5-r3-1)"
    )
    assert "(214 and 251)" in text, (
        "the wiki must state the per-lane action-reference sizes the "
        "catalogs pin (214/251)"
    )
