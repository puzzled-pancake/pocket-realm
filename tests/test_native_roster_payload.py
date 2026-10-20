"""Roster payload contract pins (R3-1/R5-4 + R5-5, native-server round 1).

Rule 7 (plan §1): peer addresses are NEVER displayed. The account_list
payload therefore carries NO address member — the producer dropped the
``l.ip`` subquery, the shared parser dropped the field, and the desktop
Friends card renders only the logon TIMESTAMP (the D52
FriendRequestFlowSourceIpPinTest pattern, producer+consumers in one pin).

R5-5: bot accounts (the bot admission's ``PRB13`` prefix) are labeled in
the payload and rendered with an explicit bot marker — never padding the
friends roster unlabeled. The C++ label literal is byte-pinned to the
OWNING constant in BotProfiles.kt so a prefix change cannot desync it.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORLD_CPP = ROOT / "native/realm-runtime/src/world_runtime.cpp"
ROSTER_JSON_KT = ROOT / "android/app/src/main/java/com/pocketrealm/supervisor/FriendRosterJson.kt"
PLAYERS_KT = ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/PlayersScreen.kt"
BOT_PROFILES_KT = ROOT / "android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt"


def _account_list_body() -> str:
    """The account_list() function body (its JSON emission section)."""
    source = WORLD_CPP.read_text(encoding="utf-8")
    start = source.index("std::string account_list()")
    body = source[start:]
    end = body.index("\n    std::string ", 1)
    return body[:end]


def test_account_list_payload_has_no_address_member():
    source = WORLD_CPP.read_text(encoding="utf-8")
    assert "lastLogonIp" not in source, (
        "world_runtime.cpp still emits a lastLogonIp roster member "
        "(plan §1 rule 7: peer addresses are never displayed)"
    )
    body = _account_list_body()
    assert not re.search(r"l\.ip\b", body), (
        "account_list still selects l.ip from account_logons"
    )


def test_account_list_labels_bot_rows():
    body = _account_list_body()
    # the emission is C++ source: the JSON member appears in its escaped
    # form \\"bot\\"
    assert '\\"bot\\":' in body, "account_list payload lost the bot label member"
    table = _cpp_bot_prefix_table(body)
    profiles = BOT_PROFILES_KT.read_text(encoding="utf-8")
    owning = sorted({match.group(1) for match in _owning_prefix_rows(profiles)})
    assert owning, "BotProfiles.kt lost the accountPrefix owning constants"
    assert table == owning, (
        f"the C++ roster bot-prefix set desynced from the owning "
        f"BotProfiles.kt table: uncovered prefixes "
        f"{sorted(set(owning) - set(table))}, stale table rows "
        f"{sorted(set(table) - set(owning))}"
    )


def _cpp_bot_prefix_table(body: str) -> list[str]:
    """The BOT_ACCOUNT_PREFIXES table in account_list (sorted, deduped)."""
    start = body.index("BOT_ACCOUNT_PREFIXES[]")
    block = body[start:body.index("};", start)]
    table = sorted({
        match.group(1)
        for match in re.finditer(r'"([A-Z0-9]+)"', block)
    })
    assert table, "the C++ bot-prefix label table is empty"
    return table


def _owning_prefix_rows(profiles: str):
    """Every line-form `accountPrefix = "..."` in BotProfiles.kt (the
    constructor entries — never the toString() template literal)."""
    return re.finditer(r'(?m)^\s*accountPrefix\s*=\s*"([^"]+)"', profiles)


def test_every_user_selectable_bot_prefix_is_labeled():
    """R2-1 (round 2): the single-literal labeler covered only the
    non-selectable soak profile — every USER-SELECTABLE profile's prefix
    (the presets and the launch-day profile) rendered bot:false and padded
    the roster unlabeled. The predicate table must cover every
    userSelectable profile's accountPrefix, and the C++ must not have
    regressed to a single-literal compare."""
    profiles = BOT_PROFILES_KT.read_text(encoding="utf-8")
    # profile blocks: userSelectable (explicit, or the data-class default
    # true at BotProfiles.kt:11) PRECEDES accountPrefix in each constructor
    # call — the block for row i spans from the previous prefix row's end
    # to this row's end.
    selectable_prefixes = set()
    rows = list(_owning_prefix_rows(profiles))
    assert len(rows) >= 20, f"unexpectedly few profile rows: {len(rows)}"
    for index, row in enumerate(rows):
        block_start = rows[index - 1].end() if index else 0
        block = profiles[block_start:row.start()]
        flag = re.search(r'userSelectable\s*=\s*(true|false)', block)
        if flag is None or flag.group(1) == "true":
            selectable_prefixes.add(row.group(1))
    assert selectable_prefixes, "no user-selectable bot profiles found"
    body = _account_list_body()
    table = set(_cpp_bot_prefix_table(body))
    uncovered = sorted(selectable_prefixes - table)
    assert not uncovered, (
        f"user-reachable bot account prefixes the native labeler does not "
        f"cover (they render bot:false and pad the roster): {uncovered}")
    assert "BOT_ACCOUNT_PREFIX[] = " not in body, (
        "the labeler regressed to a single-literal compare")


def test_create_account_never_puts_the_credential_in_a_command_string():
    """R2-1 (round 2, serious): `account create <user> <pass>` as a console
    verb is ECHOED to world.log by the core's LogCommand whenever
    LogFileLevel >= LOG_LVL_DETAIL (the debug-logs mode stages 3), and
    world.log rides the shareable support bundle. The op must route the
    credential write through AccountMgr::CreateAccount — the same
    non-echoed write the console handler itself uses — and must never
    build a command string carrying the password. Negative control: a
    revert re-introducing the concatenation literal fails this pin."""
    source = WORLD_CPP.read_text(encoding="utf-8")
    start = source.index("int create_account(")
    body = source[start:source.index("\n    int set_account_gmlevel(", start)]
    assert '"account create " + username + " " + password' not in body, (
        "create_account re-introduces the plaintext password into the "
        "echoed console command string (world.log leak)")
    assert "issue_command" not in body, (
        "create_account must not ride the echoed console-verb lane")
    assert "sAccountMgr.CreateAccount(username, password)" in body, (
        "create_account lost the non-echoed AccountMgr write")
    assert "AOR_NAME_ALREADY_EXIST" in body, (
        "the ACCOUNT_EXISTS verdict mapping must come from the core's own "
        "AccountOpResult")


def test_ban_duration_validator_matches_the_core_time_parser_domain():
    """R5-r2-1 (round 2): ban_account's validator accepted s/m/h/d/w/M/y,
    but the core's TimeStringToSecs (Util.cpp) understands only s/m/h/d and
    returns 0 for anything else — and duration 0 is a PERMANENT ban, so a
    '1w' call permanently banned while the op answered ok. The validator's
    unit-char class must equal the core parser's own switch table exactly
    (digits ride the sentinel/number grammar; w/M/y refuse as
    invalid-duration)."""
    source = WORLD_CPP.read_text(encoding="utf-8")
    start = source.index("std::string ban_account(")
    body = source[start:source.index("std::string unban_account(", start)]
    guard = body[body.index("if (duration.empty()"):]
    guard = guard[:guard.index("invalid-duration")]
    accepted_units = set(re.findall(r"c == '([a-zA-Z])'", guard))
    assert accepted_units, "ban_account's duration char class not found"
    # the shared ban_account compiles into ALL THREE
    # flavor lanes, so the parser contract holds against EVERY pinned
    # core's TimeStringToSecs — an expansion core that drops a unit the
    # shared validator still accepts would re-open the permanent-ban bug
    # (duration 0) while a vanilla-only pin stayed green.
    sys.path.insert(0, str(ROOT / "tools"))
    from tools import flavor_layout  # noqa: E402

    for flavor_id in flavor_layout.FLAVOR_IDS:
        layout = flavor_layout.resolve(flavor_id)
        util_path = ROOT / layout.core_submodule / "src/shared/Util/Util.cpp"
        if not util_path.is_file():
            continue  # unmaterialized pin: the loud-NOTE posture
        util = util_path.read_text(encoding="utf-8")
        tsts = util[util.index("TimeStringToSecs"):]
        tsts = tsts[:tsts.index("\n}", 1) if "\n}" in tsts else len(tsts)]
        core_units = set(re.findall(r"case '([a-zA-Z])':", tsts))
        assert core_units == {"d", "h", "m", "s"}, (
            f"{flavor_id}'s TimeStringToSecs table shifted: "
            f"{sorted(core_units)} — re-derive this pin with it ({util_path})")
        assert accepted_units == core_units, (
            f"ban_account accepts {sorted(accepted_units)} but the "
            f"{flavor_id} core's time parser handles only "
            f"{sorted(core_units)} — an unmapped unit returns 0, which "
            "that core treats as a PERMANENT ban while the op answers ok "
            "(TimeStringToSecs default leg)")
    for banned in ("w", "M", "y"):
        assert f"c == '{banned}'" not in body, (
            f"the duration validator re-accepts '{banned}', which "
            "TimeStringToSecs maps to 0 = permanent ban")


def test_roster_parser_projects_the_bot_flag_and_no_address():
    source = ROSTER_JSON_KT.read_text(encoding="utf-8")
    assert "lastLogonIp" not in source, "AccountRow still carries the address field"
    assert 'row.optBoolean("bot")' in source, (
        "parseAccounts lost the bot label projection"
    )


def test_desktop_friends_card_labels_bots_and_never_renders_addresses():
    source = PLAYERS_KT.read_text(encoding="utf-8")
    assert "lastLogonIp" not in source, (
        "PlayersScreen still references the address field (plan §1 rule 7)"
    )
    assert 'if (account.bot)' in source, (
        "the Friends card lost the explicit bot label (R5-5)"
    )


def test_account_set_ops_pass_the_resolved_id_not_the_raw_username():
    """R3-1 (round 3): the core's `account set` handlers resolve their
    argument through ChatHandler::ExtractAccountId, whose NUMERIC branch
    treats a digit string as an ACCOUNT ID (Chat.cpp:3420-3425) — a
    digits-named guest (LanScreen accepts [A-Za-z0-9]{1,16}) would be
    rejected forever on an expansion provisioning lane, or receive
    another account's expansion/gmlevel write on an id collision. Both
    ops must resolve the id native-side (account_info) and put the
    NUMERIC id — never the raw username — into the CLI string."""
    source = WORLD_CPP.read_text(encoding="utf-8")
    for op, next_op in (
        ("int set_account_gmlevel(", "int set_account_addon("),
        ("int set_account_addon(", "bool verify_account_password("),
    ):
        start = source.index(op)
        body = source[start:source.index(next_op, start)]
        assert "account_info(username)" in body, (
            f"{op.strip('(')} lost the native-side id resolution")
        assert '"+ username + "' not in body, (
            f"{op.strip('(')} still interpolates the raw username into the "
            "CLI string (ExtractAccountId reinterprets digit strings as "
            "account ids)")
        assert "std::to_string(account.first)" in body, (
            f"{op.strip('(')} must pass the resolved numeric id")
        assert "if (account.first == 0) return POCKET_SERVER_ACCOUNT_REJECTED" in body, (
            f"{op.strip('(')} lost the unknown-account verdict")
