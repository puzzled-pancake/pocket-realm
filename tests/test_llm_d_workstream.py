"""WS-D (plan v2.3 s5) source-contract pins: D1 spawn-stack relief, D4
village ring, D3 chatter-lane staging.

D1/D4 native behavior lives in anchor payloads inside the build driver
(RandomPlayerbotMgr/PlayerbotAIConfig/aiplayerbot.conf.dist.in are
anchor-managed); D3's rung cap and D4's preset fields live on the Kotlin
surface. These pins follow test_llm_player_surface.py's content-contract
pattern: every anchor's UPSTREAM text is asserted byte-present in the
PRISTINE submodule tree (the drift guard), the ANDROID payloads carry the
plan's laws, and the Kotlin surface wires the emission. The behavioral
leg (an actual login wave spreading) is device-gated - the T4 soak
asserts teleportsLast60s on hardware.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
PRISTINE_PB = ROOT / "native" / "playerbots" / "playerbot"
BOT_PROFILES = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "bots" / "BotProfiles.kt"
BOT_LLM_SPEECH = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "bots" / "BotLlmSpeech.kt"
CHATTER_MONITOR = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "server" / "ChatterPowerMonitor.kt"
SERVER_FILES = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "server" / "ServerRuntimeFiles.kt"
LLM_POLICY = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "server" / "LlmRuntimePolicy.kt"


def driver_text() -> str:
    return DRIVER.read_text(encoding="utf-8")


def anchor_text(name: str) -> str:
    """The text of a driver anchor constant (UPSTREAM or ANDROID)."""
    return driver_text().split(name + " = ")[1].split('"""')[1]


def pristine(name: str) -> str:
    with (PRISTINE_PB / name).open(encoding="utf-8", newline="") as handle:
        return handle.read()


def test_every_new_upstream_anchor_is_byte_present_in_the_pristine_tree():
    """The drift guard: each UPSTREAM payload must byte-match the pristine
    submodule file (LF and CRLF variants, mirroring replace_anchor)."""
    cases = {
        "PB_MGR_RTEL_DECL_UPSTREAM": "RandomPlayerbotMgr.h",
        "PB_MGR_LEVEL_GUARD_UPSTREAM": "RandomPlayerbotMgr.cpp",
        "PB_MGR_RANDOMIZE_SETTLER_UPSTREAM": "RandomPlayerbotMgr.cpp",
        "PB_MGR_TELEPORT_EVENT_UPSTREAM": "RandomPlayerbotMgr.cpp",
        "PB_MGR_SPREAD_HELPERS_UPSTREAM": "RandomPlayerbotMgr.cpp",
        "PB_D1_CONFIG_HEADER_UPSTREAM": "PlayerbotAIConfig.h",
        "PB_D1_CONFIG_CPP_UPSTREAM": "PlayerbotAIConfig.cpp",
        "PB_D1_CONF_DIST_UPSTREAM": "aiplayerbot.conf.dist.in",
    }
    for constant, filename in cases.items():
        text = anchor_text(constant)
        raw = pristine(filename)
        assert text in raw or text.replace("\n", "\r\n") in raw, \
            f"{constant} no longer byte-matches pristine {filename} (anchor drift)"


def test_every_new_anchor_pair_is_registered_in_prepare_cmangos_source():
    body = driver_text().split("def prepare_cmangos_source()")[1]
    for name in (
        "PB_MGR_RTEL_DECL_UPSTREAM", "PB_MGR_LEVEL_GUARD_UPSTREAM",
        "PB_MGR_RANDOMIZE_SETTLER_UPSTREAM", "PB_MGR_TELEPORT_EVENT_UPSTREAM",
        "PB_MGR_SPREAD_HELPERS_UPSTREAM", "PB_D1_CONFIG_HEADER_UPSTREAM",
        "PB_D1_CONFIG_CPP_UPSTREAM", "PB_D1_CONF_DIST_UPSTREAM",
    ):
        assert f"replace_anchor(" in body and name in body, \
            f"{name} must be registered inside prepare_cmangos_source()"


def test_d1_force_flag_bypasses_the_level_guard_for_the_forced_path_only():
    upstream = anchor_text("PB_MGR_LEVEL_GUARD_UPSTREAM")
    android = anchor_text("PB_MGR_LEVEL_GUARD_ANDROID")
    # the pristine guard stays intact for every other caller
    assert "if (bot->GetLevel() < 5)" in upstream
    assert "force" not in upstream
    # the forced path - and ONLY the forced path - bypasses it
    assert "if (bot->GetLevel() < 5 && !force)" in android
    decl = anchor_text("PB_MGR_RTEL_DECL_ANDROID")
    assert "bool activeOnly = false, bool force = false);" in decl, \
        "RandomTeleport grows the defaulted force parameter (existing callers unchanged)"


def test_d1_arms_at_the_login_site_with_a_staggered_schedule_teleport():
    login = anchor_text("PB_MGR_LOGIN_ANDROID")
    assert "PocketArmLoginSpread(bot);" in login, "OnBotLoginInternal is the login hook"
    helpers = anchor_text("PB_MGR_SPREAD_HELPERS_ANDROID")
    assert "ScheduleTeleport(botId, POCKET_SPREAD_STAGGER_MIN_SEC + urand(0, POCKET_SPREAD_STAGGER_SPAN_SEC));" in helpers, \
        "the spread staggers through ScheduleTeleport so grid loads do not stack with the login wave"
    # the forced relocation only ever fires through the pending set armed at
    # login - the periodic grind path keeps its own branch
    event = anchor_text("PB_MGR_TELEPORT_EVENT_ANDROID")
    assert "spreadMode" in event and "PocketLoginSpreadTeleport(player);" in event
    assert "RandomTeleportForLevel(player, true);\n                    ScheduleTeleport(bot);" in event, \
        "the non-forced branch keeps today's periodic semantics byte-for-byte"


def test_d1_near_player_filter_resolves_keep_best_before_the_recursion():
    helpers = anchor_text("PB_MGR_SPREAD_HELPERS_ANDROID")
    # keep-best: the narrowing keeps the closest candidates on the anchor's
    # map and NEVER empties the list (a cross-map best survives)
    assert "keep-best" in helpers or "bestAny" in helpers
    assert "WorldLocation const* bestAny = nullptr;" in helpers
    assert "locs.push_back(*bestAny);" in helpers, \
        "when no candidate shares the anchor's map the single best survives"
    # the forced leg enters RandomTeleport AFTER the filter resolved, with
    # activeOnly=false - the empty-candidate recursion inside re-enters
    # WITHOUT the force flag and dead-ends on the level guard for exactly
    # this population, so it must never be reached from the forced path
    forced_call = "RandomTeleport(bot, locs, false, false, true);"
    assert forced_call in helpers
    assert helpers.index("keep-best narrowing") < helpers.index(forced_call), \
        "the filter resolves BEFORE the forced RandomTeleport call"
    # not gated on activeOnly: the forced call's own activeOnly argument is
    # false while the filter ran before it - and the ring placement takes
    # precedence over both
    assert "PocketPickSpreadAnchor(bot);" in helpers


def test_d1_places_a_mob_avoiding_ring_around_the_chosen_player():
    helpers = anchor_text("PB_MGR_SPREAD_HELPERS_ANDROID")
    assert "FleeManager manager(bot, frand(POCKET_SPREAD_RING_MIN_YD, POCKET_SPREAD_RING_MAX_YD), 0.0f, false, WorldPosition(anchor));" in helpers, \
        "the FleeManager pattern rings the anchor player (mob-avoiding placement)"
    assert "POCKET_SPREAD_RING_MIN_YD = 10.0f;" in helpers
    assert "POCKET_SPREAD_RING_MAX_YD = 25.0f;" in helpers, \
        "the ring stays inside say range (ListenRange.Say = 25 yd)"
    # no real player online (or the ring found nothing): plain
    # level-appropriate teleport - never stay-stacked
    assert "never\n    // stay-stacked" in helpers or "stay-stacked" in helpers


def test_d1_kill_switch_and_d4_native_keys_ship_with_the_plan_defaults():
    cpp = anchor_text("PB_D1_CONFIG_CPP_ANDROID")
    assert 'randomBotLoginSpread = config.GetBoolDefault("AiPlayerbot.RandomBotLoginSpread", true);' in cpp, \
        "D1 kill-switch: default on, 0 disables"
    assert 'villageRingCount = config.GetIntDefault("AiPlayerbot.VillageRingCount", 0);' in cpp, \
        "D4 ships DARK: the native default is 0"
    assert 'villageRingMinYd = config.GetIntDefault("AiPlayerbot.VillageRingMinYd", 10);' in cpp
    assert 'villageRingMaxYd = config.GetIntDefault("AiPlayerbot.VillageRingMaxYd", 25);' in cpp, \
        "the plan's 10/25 defaults (inside ListenRange.Say 25.0)"
    header = anchor_text("PB_D1_CONFIG_HEADER_ANDROID")
    for member in ("bool randomBotLoginSpread;", "uint32 villageRingCount;",
                   "uint32 villageRingMinYd;", "uint32 villageRingMaxYd;"):
        assert member in header


def test_d1_conf_dist_documents_the_operator_surface():
    conf = anchor_text("PB_D1_CONF_DIST_ANDROID")
    for line in (
        "# AiPlayerbot.RandomBotLoginSpread = 1",
        "# AiPlayerbot.VillageRingCount = 0",
        "# AiPlayerbot.VillageRingMinYd = 10",
        "# AiPlayerbot.VillageRingMaxYd = 25",
    ):
        assert line in conf
    assert "0 disables" in conf and "0 = off" in conf


def test_d4_settlers_are_exempt_from_relocation_and_the_randomize_event():
    randomize = anchor_text("PB_MGR_RANDOMIZE_SETTLER_ANDROID")
    assert 'GetEventValue(bot, "settler")' in randomize, \
        "the randomize event skips designated villagers"
    assert "ScheduleRandomize(bot);" in randomize, \
        "skipping still re-arms the cadence (no per-pass re-entry)"
    assert "Randomize(player);" in randomize, "the non-settler branch is preserved"
    helpers = anchor_text("PB_MGR_SPREAD_HELPERS_ANDROID")
    assert 'SetEventValue(botId, "settler", 1, -1);' in helpers, \
        "the settler marker persists via the event-value store"
    assert "sPlayerbotAIConfig.villageRingCount > 0" in helpers, \
        "designation only runs while the ring is enabled (DARK default: inert)"
    assert "cellEntry->second.first == bot->getRace()" in helpers, \
        "same-race settlers only"
    ring = "PocketPlaceVillageRing(bot);"
    assert ring in helpers, "villagers get the ring placement, not the D1 relocation"


def test_d4_village_ring_snaps_height_and_feeds_the_teleport_telemetry():
    helpers = anchor_text("PB_MGR_SPREAD_HELPERS_ANDROID")
    assert "map->GetHeight(x, y, bot->GetPositionZ() + 0.5f)" in helpers, \
        "the ring offset snaps z via GetHeight like the placement loop"
    # T4's teleportsLast60s must see every placement the D lane makes
    assert helpers.count("lowCpuTeleportEvents.push_back(time(nullptr));") == 2, \
        "both placement sites (ring-around-player, village ring) feed the telemetry"


def test_d1_companion_emits_the_range_map_login_criteria_on_experience_presets():
    src = BOT_PROFILES.read_text(encoding="utf-8")
    assert "val loginPreferNearPlayer: Boolean = false," in src, \
        "the field defaults off (legacy byte-identity)"
    assert 'AiPlayerbot.DefaultLoginCriteria = maxbots,spareroom,offline,range,map' in src, \
        "the companion enables the existing range/map login criteria"
    assert src.count("loginPreferNearPlayer = true,") == 7, \
        "exactly the seven experience presets opt in"


def test_d4_preset_fields_ship_dark_with_the_plan_law():
    src = BOT_PROFILES.read_text(encoding="utf-8")
    assert "val villageRingCount: Int = 0," in src
    assert "val villageRingMinYd: Int = VILLAGE_RING_DEFAULT_MIN_YD" in src
    assert "val villageRingMaxYd: Int = VILLAGE_RING_DEFAULT_MAX_YD" in src
    assert "internal const val VILLAGE_RING_DEFAULT_MIN_YD = 10" in src
    assert "internal const val VILLAGE_RING_DEFAULT_MAX_YD = 25" in src
    assert "VILLAGE_RING_MIN_COUNT = 3" in src and "VILLAGE_RING_MAX_COUNT = 5" in src, \
        "count law: 0 = off or a village of 3-5"
    assert src.count("villageRingCount = ") - src.count("val villageRingCount = 0,") == 0, \
        "no catalog profile sets a village ring (D4 ships DARK)"
    emission = "AiPlayerbot.VillageRingCount = $villageRingCount" in src and \
        "AiPlayerbot.VillageRingMinYd = $villageRingMinYd" in src and \
        "AiPlayerbot.VillageRingMaxYd = $villageRingMaxYd" in src
    assert emission, "an opted-in preset emits exactly the three staging keys"


def test_d3_chatter_rung_rides_the_staged_power_file_as_a_cap():
    speech = BOT_LLM_SPEECH.read_text(encoding="utf-8")
    assert "val chatterRung: Int = CHATTER_RUNG_FOLLOW," in speech
    assert "const val CHATTER_RUNG_FOLLOW = -1" in speech
    assert "const val CHATTER_RUNG_NORMAL = 4" in speech, \
        "the rung values must move with the native pocketllm::ChatterRung enum"
    monitor = CHATTER_MONITOR.read_text(encoding="utf-8")
    assert "fun applyRungCap(computed: Int, cap: Int): Int =" in monitor
    assert "minOf(computed, cap)" in monitor, \
        "the cap may only LOWER the computed rung - the ambience toggle and the courtesy dim always win"
    assert 'field(current, "rung") == field(next, "rung")' in monitor, \
        "a cap change must restage the file even when enabled+dim are unchanged"
    wiring = SERVER_FILES.read_text(encoding="utf-8")
    assert "rungCap = profile.llmSpeech.chatterRung," in wiring, \
        "ServerRuntimeFiles threads the selected profile's cap into the staging"
    # the conf side still names the staged file (the pre-existing emission
    # D3 rides - regression guard)
    policy = LLM_POLICY.read_text(encoding="utf-8")
    assert 'AiPlayerbot.LLMChatterPowerFile = \\"$chatterPowerFile\\"' in policy


def test_d3_stages_the_chatter_lane_on_the_seven_experience_presets():
    src = BOT_PROFILES.read_text(encoding="utf-8")
    assert src.count("chatterRung = BotLlmSpeech.CHATTER_RUNG_NORMAL") == 7, \
        "a non-OFF rung on exactly the seven experience presets (a real rung exists: RUNG_NORMAL = 4)"
