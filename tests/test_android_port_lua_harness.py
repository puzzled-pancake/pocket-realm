"""Run the Android Port addon's Lua inside a mocked 1.12.1 / 2.4.3 / 3.3.5a client.

The Kotlin asset tests only read the addon's text, so a Lua runtime error
(an undefined local, a function one client lacks) or a protected call the
2.4.3/3.3.5a clients would block passes them untouched. This module loads the
real addon tree, in toc order, into tests/android_port/wowmock.lua under each
client flavor and each control layout, then drives keys, touches, combat,
stance changes, the radial menu, bags, Move UI, chat and layout switching.

Needs lupa (Lua 5.1 runtime); see requirements-test.txt.
"""
from __future__ import annotations

from pathlib import Path

import pytest

try:
    import lupa.lua51 as lua51
except ImportError as exc:  # pragma: no cover - environment guard
    raise ImportError(
        "the Android Port Lua harness needs lupa: python -m pip install -r requirements-test.txt"
    ) from exc

ROOT = Path(__file__).resolve().parent.parent
ADDON = ROOT / "android/app/src/main/assets/addons/android-port/AndroidPort"
MOCK = Path(__file__).resolve().parent / "android_port" / "wowmock.lua"

FLAVORS = ("vanilla", "tbc", "wotlk")
LAYOUTS = ("star", "forever")
CASES = [(flavor, layout) for flavor in FLAVORS for layout in LAYOUTS]
IDS = [f"{flavor}-{layout}" for flavor, layout in CASES]


def toc_files() -> list[str]:
    lines = (ADDON / "AndroidPort.toc").read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.startswith("#")]


def lua_list(table) -> list:
    return [table[i] for i in range(1, len(table) + 1)] if table is not None else []


class Client:
    """One mocked client session with the addon loaded."""

    def __init__(self, flavor: str, layout: str | None = None, host_layout: str | None = None,
                 enter_in_combat: bool = False):
        self.flavor = flavor
        self.rt = lua51.LuaRuntime(unpack_returned_tuples=True)
        self.g = self.rt.globals()
        self.g.FLAVOR = flavor
        self.rt.execute(MOCK.read_text(encoding="utf-8"))
        self.mock = self.g.MOCK
        for name in toc_files():
            source = (ADDON / name).read_text(encoding="utf-8")
            if name == "HostLayout.lua" and host_layout:
                source = source.replace("AndroidPort.HOST_LAYOUT = nil",
                                        f'AndroidPort.HOST_LAYOUT = "{host_layout}"')
            self.rt.execute(source)
        if layout:
            self.rt.execute(f'AndroidPortDB = {{ layout = "{layout}" }}')
        if enter_in_combat:
            self.rt.execute("MOCK.combat = true")
        self.rt.execute('MOCK.fire("ADDON_LOADED", "AndroidPort")')
        self.rt.execute('MOCK.fire("PLAYER_ENTERING_WORLD")')

    def run(self, code: str):
        self.rt.execute(code)

    def eval(self, code: str):
        return self.rt.eval(code)

    def reset(self):
        self.run("MOCK.casts = {}; MOCK.log = {}; MOCK.blocked = {}")

    def press(self, key: str):
        self.reset()
        self.run(f'MOCK.press("{key}")')

    def tap(self, number: int, shift: bool = False) -> list:
        self.reset()
        self.run(f"MOCK.shift = {'true' if shift else 'false'}; "
                 f"MOCK.secureClick(AndroidPortButton{number}, 'LeftButton'); MOCK.shift = false")
        return self.casts

    def enter_combat(self):
        self.run("MOCK.combat = true; MOCK.fire('PLAYER_REGEN_DISABLED')")

    def leave_combat(self):
        self.run("MOCK.combat = false; MOCK.fire('PLAYER_REGEN_ENABLED')")

    def shown_buttons(self) -> list[int]:
        return [n for n in range(1, 33) if self.eval(f"AndroidPortButton{n}:IsShown()")]

    @property
    def casts(self) -> list:
        return lua_list(self.mock.casts)

    @property
    def log(self) -> list:
        return lua_list(self.mock.log)

    @property
    def blocked(self) -> list:
        return lua_list(self.mock.blocked)

    @property
    def errors(self) -> list:
        return lua_list(self.mock.errors)


@pytest.fixture(params=CASES, ids=IDS)
def client(request):
    flavor, layout = request.param
    session = Client(flavor, layout)
    session.layout = layout
    yield session
    assert not session.errors, f"Lua runtime errors: {session.errors}"


def test_every_module_starts_on_every_client(client):
    ap = client.g.AndroidPort
    assert not client.errors
    assert ap.ActionBars.ready and ap.Radial.ready and ap.Bags.ready
    assert client.eval("AndroidPort:GetControlLayout()") == client.layout
    assert bool(ap.SECURE) == (client.flavor != "vanilla")
    assert ap.MAX_PLAYER_LEVEL == {"vanilla": 60, "tbc": 70, "wotlk": 80}[client.flavor]


def test_controller_keys_fire_their_own_slots(client):
    client.press("5")
    assert client.casts == [5]
    for key, slot in [("SHIFT-1", 13), ("SHIFT-3", 15), ("CTRL-6", 30), ("CTRL-SHIFT-8", 44)]:
        client.press(key)
        assert client.casts == [slot], f"{key}: blocked={client.blocked}"
    client.press("1")
    if client.layout == "forever":
        assert client.log == ["Jump"] and client.casts == []
    else:
        assert client.casts == [1]


@pytest.mark.parametrize("flavor", FLAVORS)
def test_forever_base_face_buttons_run_their_fixed_jobs(flavor):
    client = Client(flavor, "forever")
    client.press("4")
    assert client.log == ["ToggleGameMenu"]
    client.press("2")
    assert client.casts == []
    assert any("PR6I:1 INTERACT" in str(line) for line in lua_list(client.mock.chat))
    client.press("3")
    assert client.eval("AndroidPortRadialMenu:IsShown()")
    assert client.tap(1) == []
    assert client.tap(4) == []
    assert not client.errors


def test_layout_decides_which_buttons_are_on_screen(client):
    if client.layout == "forever":
        assert client.shown_buttons() == list(range(1, 33))
        assert client.tap(5) == [5]
        assert client.tap(10) == [14]
        assert client.tap(26) == [38]
    else:
        assert client.shown_buttons() == list(range(1, 9))
        assert client.tap(2) == [2]


@pytest.mark.parametrize("flavor", FLAVORS)
def test_star_layout_shift_shows_and_fires_page_two(flavor):
    client = Client(flavor, "star")
    client.run("MOCK.shift = true; MOCK.tick(0.1)")
    if client.flavor == "vanilla":
        assert client.eval("AndroidPortButton10:IsShown()")
        client.reset()
        client.run("MOCK.secureClick(AndroidPortButton10, 'LeftButton')")
    else:
        assert client.eval("AndroidPortButton2.icon.__texture") == "Interface\\Icons\\Slot14"
        client.reset()
        client.run("MOCK.secureClick(AndroidPortButton2, 'LeftButton')")
    assert client.casts == [14]
    client.run("MOCK.shift = false; MOCK.tick(0.1)")
    assert not client.errors


def test_page_swaps_in_combat_are_never_blocked(client):
    client.reset()
    client.enter_combat()
    client.run("MOCK.shift = true; MOCK.tick(0.1); MOCK.shift = false; MOCK.tick(0.1)")
    client.run("MOCK.ctrl = true; MOCK.tick(0.1); MOCK.ctrl = false; MOCK.tick(0.1)")
    client.run("MOCK.fire('ACTIONBAR_UPDATE_COOLDOWN')")
    assert client.blocked == []


def test_stance_changes_reach_the_page_one_buttons(client):
    client.enter_combat()
    client.reset()
    client.run("MOCK.bonus = 1; MOCK.updateDrivers(); MOCK.fire('UPDATE_BONUS_ACTIONBAR'); MOCK.tick(0.1)")
    assert client.blocked == []
    client.press("5")
    # 2.4.3 has no secure state handler: its stance slot lands once combat ends.
    assert client.casts == ([5] if client.flavor == "tbc" else [77])
    client.leave_combat()
    client.press("5")
    assert client.casts == [77]
    client.run("MOCK.bonus = 0; MOCK.updateDrivers(); MOCK.fire('UPDATE_BONUS_ACTIONBAR')")
    client.press("5")
    assert client.casts == [5]


def test_radial_menu_selects_with_face_keys_and_releases_them(client):
    client.reset()
    client.run("AndroidPort_ToggleRadial()")
    assert client.eval("AndroidPortRadialMenu:IsShown()")
    client.run('MOCK.press("3")')
    assert "ToggleSpellBook" in client.log and client.casts == []
    for key, frame in [(5, "QuestLogFrame"), (6, "WorldMapFrame")]:
        client.run("AndroidPort_ToggleRadial()")
        client.press(str(key))
        assert client.eval(f"{frame}:IsShown()"), client.errors
        client.run(f"{frame}:Hide()")
    # Escape closes the menu through the special-frame sweep, not Radial:Hide.
    client.run("AndroidPort_ToggleRadial(); AndroidPortRadialMenu:Hide()")
    client.press("5")
    assert client.casts == [5]


def test_bags_slot_clicks_and_junk_selling(client):
    client.reset()
    client.run("MOCK.run(AndroidPortBagsSlot1, 'OnClick')")
    client.run("MerchantFrame:Show(); MerchantFrame.selectedTab = 1; AndroidPort.Bags:SellJunk()")
    for _ in range(5):
        client.run("MOCK.tick(0.25)")
    if client.flavor == "vanilla":
        assert "item0:3" in client.casts
    else:
        assert client.casts == [] and client.blocked == []
    client.run("MerchantFrame:Hide()")


def test_move_ui_journals_the_clusters_and_yields_to_combat(client):
    client.reset()
    client.run("AndroidPort:ToggleMoveUI()")
    assert client.eval("AndroidPort.moveUiActive") is True
    key = "frame:AndroidPortRightCluster"
    assert client.eval(f'AndroidPort.addonIconCandidates["{key}"]') is not None
    assert client.eval(f'AndroidPort:CanMoveCandidate(AndroidPort.addonIconCandidates["{key}"])')
    if client.flavor == "vanilla":
        client.run("AndroidPort:ToggleMoveUI()")
    else:
        client.enter_combat()
        assert not client.eval("AndroidPort.moveUiActive")
        client.leave_combat()
    assert client.blocked == []


def test_talk_opens_the_whisper_composer(client):
    client.run("MOCK.fire('CHAT_MSG_WHISPER', 'hi', 'Bot')")
    client.run("AndroidPort.Talk:Open()")
    assert client.eval("MOCK.openedChat") == "/w Bot "


def test_layout_switch_in_combat_lands_after_combat(client):
    other = "star" if client.layout == "forever" else "forever"
    client.reset()
    client.run("MOCK.combat = true")
    client.run(f'SlashCmdList["ANDROIDPORT"]("layout {other}")')
    assert client.blocked == []
    client.leave_combat()
    assert client.shown_buttons() == (list(range(1, 33)) if other == "forever" else list(range(1, 9)))
    client.press("1")
    if other == "forever":
        assert client.log == ["Jump"]
    else:
        assert client.casts == [1]


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_reload_in_combat_waits_for_combat_to_end(flavor):
    session = Client(flavor, enter_in_combat=True)
    assert session.blocked == []
    session.leave_combat()
    assert session.g.AndroidPort.ActionBars.ready
    session.press("1")
    assert session.casts == [1]
    assert not session.errors


def test_app_layout_is_adopted_once_then_the_player_choice_sticks():
    session = Client("wotlk", host_layout="forever")
    assert session.eval("AndroidPort:GetControlLayout()") == "forever"
    session.run('SlashCmdList["ANDROIDPORT"]("layout star")')
    assert session.eval("AndroidPort:GetControlLayout()") == "star"
    session.run("AndroidPort:InitializeDatabase()")
    assert session.eval("AndroidPort:GetControlLayout()") == "star"
    assert not session.errors
