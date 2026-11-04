"""W6-N3 code-red round-1 merged pins: the gateway manifest (R1-1/R2-6),
the join lane's async + UI wiring (R1-2/R1-4/R4-2/R6-5), the event-id join
key (R1-3), the death watcher (R3-2), the executed N3 logs (R3-3/R6-8),
the admission bound (R4-5), the contract doc (R6-9), and the disclosures
(R7-N3-1). Line/source-anchored where behavioral lanes live elsewhere.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATEWAY_BUILD = ROOT / "gateway" / "build.gradle.kts"
MANIFEST = ROOT / "desktop" / "shared-sources.json"


def _gateway_include_list() -> set[str]:
    """The shared-core include list from gateway/build.gradle.kts (the
    sharedCoreSources.include(...) block)."""
    text = GATEWAY_BUILD.read_text(encoding="utf-8")
    block = re.search(r"sharedCoreSources\.include\((.*?)\)", text, re.DOTALL)
    assert block, "gateway/build.gradle.kts lost its include block"
    return set(re.findall(r'"([^"]+\.kt)"', block.group(1)))


def test_gateway_shared_core_list_matches_the_manifests():
    """R1-1/R2-6: the gateway's hand-copied include list must equal
    {client/ClientFlavor.kt} ∪ shared-sources.json's discovery/iroh/*.kt ∪
    {discovery/nostr/NostrJson.kt} — the drift alarm the gateway build
    comment names (a shared-core import that misses the gateway compile
    fails HERE, at the gate, not late in a fatJar build)."""
    expected = {"client/ClientFlavor.kt", "discovery/nostr/NostrJson.kt"}
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    expected |= {
        entry for entry in manifest["shared"]
        if entry.startswith("discovery/iroh/")
    }
    actual = _gateway_include_list()
    assert actual == expected, (
        f"the gateway shared-core include list drifted: "
        f"missing={sorted(expected - actual)} extra={sorted(actual - expected)}"
    )




def test_gateway_build_scripts_references_resolve():
    """R3-r6-2: every scripts/... reference in gateway/build.gradle.kts
    must resolve to an existing tracked file — the old comment cited
    scripts/iroh_n3_* which never existed (a reader following it finds
    nothing; the same doc-truth shape the comment pin above holds)."""
    build = (ROOT / "gateway/build.gradle.kts").read_text(encoding="utf-8")
    # Strip comment lines first: a cite MENTIONED as corrected-and-gone
    # (the R3-r6-2 fix note itself) is not a live reference.
    live = chr(10).join(
        line for line in build.split(chr(10)) if not line.strip().startswith("//")
    )
    referenced = re.findall(r"scripts/[A-Za-z0-9_./-]+", live)
    for rel in referenced:
        path = ROOT / rel
        assert path.is_file(), (
            f"gateway/build.gradle.kts cites {rel} which does not exist — "
            "a doc cite must resolve (R3-r6-2)"
        )


def test_gateway_build_pins_the_relay_only_transport_posture():
    """R3-r6-1: the transport-level IP protection is presetN0()'s
    PkarrPublisher DEFAULT AddrFilter::relay_only — NOT a discovery-free
    preset — and the ffi 1.1.0 exposes no knob for it, so the posture
    lives as a named, version-stamped comment in endpointOptions that a
    dependency bump must fail this pin against until re-verified."""
    main = (ROOT / "gateway/src/main/kotlin/com/pocketrealm/gateway/"
            "GatewayMain.kt").read_text(encoding="utf-8")
    assert "AddrFilter::relay_only" in main, (
        "endpointOptions must name the relay-only address-filter default "
        "the transport privacy rests on (R3-r6-1)"
    )
    assert "iroh 1.0.2" in main and "pkarr.rs" in main, (
        "the posture comment must stamp the iroh version it was "
        "verified on (R3-r6-1)"
    )
    assert "preset = presetN0()" in main
    # D93(5) describes the REAL mechanism (the round-6 correction): the
    # old LIVE description 'iroh's no-discovery defaults' must stay gone;
    # the only surviving mention is the correction note quoting it.
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(
        encoding="utf-8")
    assert "iroh's no-discovery defaults" not in decisions, (
        "D93(5)'s 'iroh's no-discovery defaults' mis-description must "
        "stay corrected to the PkarrPublisher relay-only mechanism "
        "(R3-r6-1)"
    )
    assert "AddrFilter::relay_only" in decisions


def test_desktop_focus_fence_covers_the_binary_results_tree():
    """R6-N3-r6-2: the -Ppocket.focus fence must redirect the Test
    task's BINARY results directory too — the JUnit-XML-only redirect
    still let focused runs delete the canonical build/test-results/test
    binary tree (the observed 'Unable to delete directory ... binary')."""
    build = (ROOT / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    fence = build[build.index("pocket.focus"):]
    assert "binaryResultsDirectory.set(" in fence, (
        "the focused-run fence must redirect binaryResultsDirectory "
        "alongside the JUnit XML output (R6-N3-r6-2)"
    )


def test_gateway_build_comment_names_a_real_test():
    """R1-1: the comment promising a drift pin must name THIS test (a
    named-but-absent test was the original finding)."""
    text = GATEWAY_BUILD.read_text(encoding="utf-8")
    assert "test_gateway_shared_sources_manifest.py" in text, (
        "the gateway build's manifest-drift comment must name the real "
        "gate (tests/test_gateway_shared_sources_manifest.py)"
    )


def test_join_ready_wait_suspends_and_never_sleeps_on_the_caller_thread():
    """R1-2/R4-1/R6-1: the READY wait is a suspend (delay) inside
    withContext(Dispatchers.IO) — a Thread.sleep poll there blocked the
    Compose UI thread for up to 45 s."""
    model = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/"
             "DesktopAppModel.kt").read_text(encoding="utf-8")
    fn = re.search(
        r"private suspend fun awaitJoinReadyAndLaunch\(.*?\n    \}",
        model, re.DOTALL,
    )
    assert fn, "awaitJoinReadyAndLaunch vanished"
    body = fn.group(0)
    assert "withContext(Dispatchers.IO)" in body, (
        "the join READY wait must hop to Dispatchers.IO"
    )
    assert "delay(JOIN_READY_POLL_MS)" in body, (
        "the join READY wait must SUSPEND (delay) between polls"
    )
    assert "Thread.sleep" not in body, (
        "the join READY wait must never Thread.sleep on the caller's thread"
    )


def test_join_action_keys_by_event_id_never_by_name():
    """R1-3/R4-3/R6-2: the join lane keys its dial targets by the listing
    EVENT ID — a name-keyed map dialed the wrong realm when two same-name
    listings were live. (Re-pointed with the Phase 5 screen split: the
    bridge maps GameTarget.id = eventId and the Find tab's join lookup
    keys by that id, never by name.)"""
    bridge = (ROOT / "android/app/src/main/java/com/pocketrealm/"
              "onlineplay/GameBrowserBridge.kt").read_text(encoding="utf-8")
    assert "id = eventId" in bridge, (
        "the join target's identity must be the listing event id"
    )
    screen = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
              "online/FindGameTab.kt").read_text(encoding="utf-8")
    assert "it.summary.id == row.id" in screen, (
        "the join lookup must key by the event id"
    )
    assert "summary.name ==" not in screen, (
        "the join lookup must never key by realm name"
    )


def test_relay_only_switch_and_stop_join_are_rendered_and_wired():
    """R1-4/R4-2/R6-4/R6-5/R7-N3-3: the relay-only switch is rendered and
    wired to setIrohRelayOnly, and a live join renders a stop control
    wired to stopIrohJoin — the guards' 'stop it first' copy and the OFF
    friend-join affordance are otherwise dead ends. (Re-pointed with the
    Phase 5 screen split: the controls moved into the Find tab's
    join-lane block.)"""
    screen = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
              "online/JoinLaneAdvanced.kt").read_text(encoding="utf-8")
    assert "model.setIrohRelayOnly(it)" in screen, (
        "the relay-only switch must be wired to the model verb"
    )
    assert "mp-find-relay-only" in screen
    assert "model.stopIrohJoin()" in screen, (
        "a live join must render a stop control wired to stopIrohJoin"
    )
    assert "mp-find-stop-join" in screen
    find = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
            "online/FindGameTab.kt").read_text(encoding="utf-8")
    assert "JoinBridge.joinState(model).collectAsState()" in find, (
        "the join lane state must render"
    )


def test_join_death_watcher_guards_stale_ready():
    """R3-2: a post-READY process death must not leave a stale Ready —
    the manager arms a death watcher (mirroring hostDescriptor's
    per-read liveness leg)."""
    manager = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/iroh/"
               "IrohGatewayManager.kt").read_text(encoding="utf-8")
    assert "joinDeathWatcher" in manager
    assert "the join gateway process exited while the" in manager


def test_host_gateway_admission_bound_pins():
    """R4-5: the internet-reachable endpoint has a concurrent-flow budget
    and an idle-connection reap — not unbounded accept."""
    host = (ROOT / "gateway/src/main/kotlin/com/pocketrealm/gateway/"
            "HostGateway.kt").read_text(encoding="utf-8")
    assert "Semaphore(MAX_CONCURRENT_FLOWS" in host
    assert "flowBudget.tryAcquire()" in host
    assert "flowBudget.release()" in host
    assert "IDLE_REAP_POLLS" in host and "IDLE_REAP_POLL_MS" in host
    assert "reaping an idle connection" in host


def test_wave_exit_carries_the_n3_section_and_the_full_gateway_inventory():
    """R6-N3-3/R2-2-5: the W6 WAVE-EXIT record must contain the N3 section
    and the archived gateway log it cites must name EVERY tracked gateway
    test class (the 5-test subset was the R2-2-5 finding)."""
    exit_record = ROOT / "out/completion/w6/WAVE-EXIT.md"
    if not exit_record.is_file():
        return
    text = exit_record.read_text(encoding="utf-8")
    assert "W6-N3 wave exit" in text, "the W6 exit record lost its N3 section"
    log = ROOT / "out/completion/w6/n3-round2-gateway.log"
    assert log.is_file(), "the archived gateway log is missing"
    log_text = log.read_text(encoding="utf-8", errors="replace")
    for cls in (
        "BreakerR2Round2NegativeTest",
        "GatewayBreakerR2NegativeE2E",
        "GatewayContractPortPins",
        "GatewaySubprocessE2E",
    ):
        assert cls in log_text, (
            f"the archived gateway log must name {cls} (the full suite, "
            "not a subset)"
        )


def test_wave_exit_gateway_inventory_equals_the_tracked_tree():
    """R6-4-2/R2-1/R4-N3-2/R3-1: the exit record's gateway inventory is
    the TRACKED TREE's @Test census, not a stale sentence — every
    @Test-bearing class under gateway/src/test is enumerated here, the
    cited executed log must carry one PASSED line per tracked test (a
    merged-but-unexecuted round probe can never satisfy the pin), and
    the log's counts line must equal both."""
    exit_record = ROOT / "out/completion/w6/WAVE-EXIT.md"
    if not exit_record.is_file():
        return
    text = exit_record.read_text(encoding="utf-8")
    # The tracked census: every @Test-bearing class NAME under
    # gateway/src/test, plus the tree's total @Test count. A file may
    # carry SEVERAL test classes (GatewaySubprocessE2E.kt holds the E2E
    # and the PortPins class) — names are tracked for coverage, the
    # totals for per-class completeness.
    sources = sorted((ROOT / "gateway/src/test").rglob("*.kt"))
    census = set()
    for source in sources:
        body = source.read_text(encoding="utf-8", errors="replace")
        if "@Test" not in body:
            continue
        for declared in re.finditer(r"(?m)^class\s+(\w+)", body):
            census.add(declared.group(1))
    assert census, "no gateway test classes discovered"
    tree_total = sum(
        len(re.findall(r"(?m)^\s*@Test\b",
                       s.read_text(encoding="utf-8", errors="replace")))
        for s in sources
    )
    inventory = re.search(
        r"gateway/: (\d+) tests", text)
    assert inventory, "the exit record lost its gateway inventory sentence"
    assert int(inventory.group(1)) == tree_total, (
        f"WAVE-EXIT says gateway/: {inventory.group(1)} tests but the "
        f"tracked tree holds {tree_total} @Test methods across "
        f"{sorted(census)} — re-run the full gateway suite and correct "
        "the inventory (the round-4 13-vs-22 class)"
    )
    cited = re.search(
        r"Executed:\s*(\S+\.log)\s*\((\d+) PASSED / 0 FAILED[;)]", text)
    assert cited, "the exit record cites no executed gateway log"
    log_path = ROOT / "out/completion/w6" / cited.group(1)
    assert log_path.is_file(), f"the cited gateway log {cited.group(1)} is missing"
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    passed_lines = re.findall(r"^(\w+) > .*PASSED$", log_text, re.MULTILINE)
    assert len(passed_lines) == tree_total, (
        f"{cited.group(1)} records {len(passed_lines)} PASSED lines, the "
        f"tree holds {tree_total} @Test methods — the citation is stale"
    )
    for cls in census:
        assert any(line == cls for line in passed_lines), (
            f"{cls} is tracked in gateway/src/test but {cited.group(1)} "
            "records no PASSED line for it — a merged-but-unexecuted "
            "probe (round-4 R6-4-2's BreakerR3Round3NegativeTest class)"
        )
    counts = re.search(
        r"# N3-GATEWAY-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)",
        log_text,
    )
    assert counts, "the cited gateway log lost its N3-GATEWAY-COUNTS line"
    assert (
        int(counts.group(1)) == tree_total
        and int(counts.group(2)) == len(census)
        and int(counts.group(3)) == 0
    ), "the gateway counts line disagrees with the tracked tree"


def test_gateway_dependency_coordinates_are_pinned():
    """R3-4: the gateway build adds the wave's dependency surface (iroh +
    transitives resolved into a fat jar that terminates the realm
    identity secret) — its exact coordinates are pinned here the same way
    the N1 secp256k1 lane is (a bump or a transitive introduction is
    loud). Repo-wide Gradle verification metadata is the owner-scale
    follow-up recorded in D95."""
    build = (ROOT / "gateway/build.gradle.kts").read_text(encoding="utf-8")
    for coordinate in (
        'implementation("computer.iroh:iroh:1.1.0")',
        'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")',
        'implementation("org.json:json:20240303")',
        'testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")',
        'testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")',
    ):
        assert coordinate in build, (
            f"the gateway build lost the pinned coordinate {coordinate} — "
            "a dependency change here is a transport-surface change and "
            "must be made loudly (R3-4/D95)"
        )


def test_n3_evidence_logs_record_executed_test_tasks():
    """R3-3/R6-8: the N3 evidence logs the exit record cites must show an
    EXECUTED (not UP-TO-DATE/FROM-CACHE) test task — a vacuous log is the
    N1 R6-2 defect class. The gateway suite additionally runs under the
    E:-resident JDK 21 recorded in gateway/gradle.properties."""
    expected = {
        "out/completion/w6/n3-canonical-android.log": "> Task :app:testDebugUnitTest",
        "out/completion/w0/n3-canonical-android.log": "> Task :app:testDebugUnitTest",
        "out/completion/w0/n3-canonical-desktop.log": "> Task :test",
        "out/completion/w6/n3-gateway-final.log": "> Task :test",
        "out/completion/w6/n3-gateway-postfix.log": "> Task :test",
    }
    for rel, task in expected.items():
        path = ROOT / rel
        if not path.is_file():
            continue  # a log the record does not cite is not held
        text = path.read_text(encoding="utf-8", errors="replace")
        executed = re.search(
            rf"^{re.escape(task)}\s*$", text, re.MULTILINE)
        assert executed, (
            f"{rel} never shows an executed '{task}' line — the evidence "
            "is vacuous (the N1 R6-2 class); re-run the suite for real"
        )


def test_gateway_gradle_properties_pin_the_e_jdk():
    """R6-8: the gateway toolchain is discoverable without an undocumented
    JAVA_HOME — org.gradle.java.installations.paths names the E:-resident
    JDK 21 the iroh artifact's JVM-21 bytecode needs."""
    props = (ROOT / "gateway" / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(
        r"org\.gradle\.java\.installations\.paths=(.+)", props)
    assert match, (
        "gateway/gradle.properties must pin org.gradle.java.installations."
        "paths to the E:-resident JDK 21"
    )
    assert "pocket_realm_toolchain" in match.group(1)


def test_contract_doc_never_list_carries_the_hostname_exception():
    """R6-9: the frozen-contract NEVER-list must not indict the shipped
    join descriptor — the hostname ban carries the relay/status-page
    exception in the same breath."""
    event = (ROOT / "android/app/src/main/java/com/pocketrealm/discovery/"
             "nostr/RealmListingEvent.kt").read_text(encoding="utf-8")
    assert "the only\n *    hostnames a listing may carry" in event, (
        "the contract block's NEVER-list must name the hostname exception "
        "(the join descriptor's home-relay URL and the status-page link)"
    )


def test_disclosures_name_the_direct_connection_and_the_iroh_relay():
    """R7-N3-1: the plan-mandated disclosure exists in the product copy —
    the iroh relay sees the host IP and a directly-connected player may
    learn it — on BOTH the host card and the browser lanes. (Re-pointed
    with the Phase 5 screen split: the host card keeps the technical
    wording; the browser lane's copy moved into the shared model in
    plain words — the main screens never name the transport technology,
    which is also what the index gate demands.)"""
    publisher = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/"
                 "nostr/NostrListingPublisher.kt").read_text(encoding="utf-8")
    assert "iroh home relay see your IP" in publisher
    assert "directly" in publisher and "may learn it" in publisher
    model = (ROOT / "android/app/src/main/java/com/pocketrealm/ui/"
             "online/OnlinePlayModel.kt").read_text(encoding="utf-8")
    assert "carries the game list" in model, (
        "the list's standing disclosure must exist in the shared copy"
    )
    assert "directly" in model and "may learn" in model
    # and the Find tab actually renders it (both platforms twin it)
    for screen in (
        ROOT / "android/app/src/main/java/com/pocketrealm/ui/online/FindGameTab.kt",
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/online/FindGameTab.kt",
    ):
        text = screen.read_text(encoding="utf-8")
        assert "LIST_CONNECTION_DISCLOSURE" in text, (
            f"{screen.name} must render the standing disclosure"
        )


def test_wiki_pages_describe_the_n3_listing_and_join_lane():
    """R6-6/R7-N3-2: the wiki truth is held in-wave — the privacy and
    home-screen pages name the join descriptor's relay hostname, and the
    networking page names the first-party internet-join lane."""
    privacy = (ROOT / "docs/wiki/Data-Storage-and-Privacy.md").read_text(
        encoding="utf-8")
    assert "iroh home-relay hostname" in privacy
    assert "joins your realm directly may learn it" in privacy
    home = (ROOT / "docs/wiki/Home-Screen.md").read_text(encoding="utf-8")
    assert "join descriptor" in home and "iroh node id" in home
    accounts = (ROOT / "docs/wiki/Accounts-Security-and-Networking.md") \
        .read_text(encoding="utf-8")
    assert "Internet play is first-party since the iroh gateway" in accounts
    assert "relay-only switch" in accounts
    # the wiki states the dev-checkout posture — the gateway
    # transport ships with the developer checkout, and a build without it
    # refuses the Join action saying exactly that (never silent, never a
    # dial into the void).
    assert "ships with the developer checkout only" in accounts
    assert "refuses the Join action with a message that says exactly that"         in accounts


def test_e_only_gate_catches_gitbash_drive_spellings():
    """R3-r2-1: the E-only gate's literal regex must catch the git-bash
    drive-letter spelling class (POSIX form: slash, drive letter, slash)
    that a staged script carried behind the old backslash-only regex —
    and the PII markers file must carry the forward-slash owner-path
    twin."""
    import re

    gate = (ROOT / "tools/check_e_only.py").read_text(encoding="utf-8")
    assert "/[Cc]/[A-Za-z0-9._-]+/" in gate, (
        "check_e_only's C_LITERAL_RE must match the git-bash drive spelling"
    )
    markers = (ROOT / "tools/pii_markers.local.json")
    if markers.is_file():
        text = markers.read_text(encoding="utf-8")
        # The spellings are constructed at runtime so this pin file never
        # embeds the owner-path literals it checks for.
        backslash_twin = "Users" + chr(92) + "David"
        has_backslash = backslash_twin in text
        has_forward = ("Users" + chr(47) + "David") in text
        assert has_forward or not has_backslash, (
            "the PII markers file carries the backslash owner path without "
            "its forward-slash twin — the spelling evades check_repo"
        )


def test_n2_script_resolves_rustup_without_c_resident_paths():
    """R3-r2-1 root fix: scripts/iroh_n2_android_build.sh resolves the
    rustup launcher from the E:-resident CARGO_HOME and carries no
    C:-resident path literal in any spelling."""
    script = (ROOT / "scripts/iroh_n2_android_build.sh").read_text(encoding="utf-8")
    assert "RUSTUP_LAUNCHER" not in script
    assert not re.search(r"(?<![A-Za-z0-9])([Cc]:[\/]|/[Cc]/[A-Za-z0-9._-]+/)", script), (
        "the N2 build script still carries a C:-resident path literal"
    )
    assert "CARGO_HOME/bin/rustup" in script


def test_gateway_contract_ready_line_doc_matches_behavior():
    """R7-N3-7: the contract doc must describe the HOST role's `addrs`
    field honestly (the direct lane's local dial input, never logged,
    never in a listing) instead of the false 'NEVER an IP address'."""

    contract = (ROOT / "android/app/src/main/java/com/pocketrealm/discovery/"
                "iroh/GatewayContract.kt").read_text(encoding="utf-8")
    assert "Nothing on this channel is ever published" in contract
    assert "never logged, and never" in contract
    assert "NEVER an IP address" not in contract


def test_host_gateway_carries_no_rewrite_symbols():
    """R2-2-4: the charter invariant — the rewrite happens ONLY in the
    joiner gateway; the host role holds no rewriter and no realmlist
    config write path (source-anchored; the joiner half is pinned by
    RealmListRewriteTest + the captured-payload greps)."""
    host = (ROOT / "gateway/src/main/kotlin/com/pocketrealm/gateway/"
            "HostGateway.kt").read_text(encoding="utf-8")
    # The joiner-only symbols must not appear in the host role at all.
    for banned in ("RealmListRewrite", "AuthStreamFilter", "JOINER_WORLD_TARGET"):
        assert banned not in host, (
            f"the host gateway must not reference {banned!r} — the rewrite "
            "lives ONLY in the joiner gateway"
        )
    # No realmlist-config write path in the host role.
    assert "realmlist.wtf" not in host and "ClientRealmEndpointProjection" not in host


def test_regen_scripts_interpolate_counts_and_artifacts_are_consistent():
    """R6-N3-2: the regen scripts interpolate the live fields (no
    hard-coded 'counts N/M' text), the N3 annotation segments are unique,
    and the newest segment carries the artifact's own tests/classes."""
    import json

    for script, artifact_name in (
        ("out/completion/w6/n3-regen-counts.py", "android-unit-counts.json"),
        ("out/completion/w6/n3-regen-desktop-counts.py",
         "desktop-test-counts.json"),
    ):
        text = (ROOT / script).read_text(encoding="utf-8")
        assert not re.search(r"counts \d+/\d+", text), (
            f"{script} hard-codes a count literal — interpolate the fields"
        )
        artifact = json.loads(
            (ROOT / "out/completion/w0" / artifact_name).read_text(
                encoding="utf-8"),
        )
        generated = artifact.get("generated", "")
        segments = generated.count("W6-N3 canonical re-measure")
        assert segments <= 1, (
            f"{artifact_name} carries {segments} duplicated N3 segments"
        )
        tail = generated.split("|")[-1]
        assert f"{artifact['tests']}/{artifact['classes']}" in tail, (
            f"{artifact_name}'s newest segment must carry its own "
            f"{artifact['tests']}/{artifact['classes']} fields"
        )
