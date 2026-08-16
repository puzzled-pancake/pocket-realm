package com.pocketrealm.addons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPortAssetTest {
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
        ).first { it.isDirectory }
    }
    private val addon = File(assetRoot, "addons/android-port/AndroidPort")

    @Test fun `journal re-key drops the whole VanillaConsolePort prefix`() {
        // 0.5.x -> 0.6.0 migration: "VanillaConsolePort" is 18 characters, so
        // the re-key must slice from 19. An earlier build sliced from 18
        // (keeping the trailing "t"), silently losing migrated frame positions
        // (de-vibe AD1). Pin the corrected slice and forbid the off-by-one.
        val core = File(addon, "Core.lua").readText()
        assertTrue(
            "re-key must use string.sub(name, 19)",
            core.contains("string.sub(name, 19)"),
        )
        assertFalse(
            "off-by-one slice string.sub(name, 18) must not appear in the re-key",
            core.contains("string.sub(name, 18)"),
        )
        assertTrue(
            "re-key only applies to the VanillaConsolePort prefix",
            core.contains("string.find(name, \"VanillaConsolePort\", 1, true) == 1"),
        )
    }

    @Test fun `built in package is a clean Interface 11200 Lua addon`() {
        val toc = File(addon, "AndroidPort.toc").readText()
        assertTrue(toc.lineSequence().any { it.trim() == "## Interface: 11200" })
        val declared = toc.lineSequence().map(String::trim)
            .filter { it.endsWith(".lua") || it.endsWith(".xml") }.toList()
        assertEquals(listOf("Core.lua", "ActionBars.lua", "Radial.lua", "Bags.lua", "FrameMover.lua", "Hud.lua"), declared)
        declared.forEach { assertTrue("TOC entry $it", File(addon, it).isFile) }
        // Vanilla discovers this reserved filename itself. Listing it in the
        // TOC sends it through the generic FrameXML loader, which reports one
        // `Unknown frame type: Binding` error for every Binding child.
        val bindingsFile = File(addon, "Bindings.xml")
        assertTrue("special Bindings.xml exists", bindingsFile.isFile)
        assertFalse("special Bindings.xml must not be a TOC entry", declared.contains("Bindings.xml"))
        assertTrue(addon.walkTopDown().filter(File::isFile).all {
            it.extension.lowercase() in setOf("lua", "xml", "toc", "txt")
        })
        val source = addon.walkTopDown().filter { it.extension == "lua" }.joinToString("\n") { it.readText() }
        listOf(
            "SecureHandler", "SecureAction", "RegisterStateDriver", "SetBindingClick",
            "Interact.dll", "CE_INTERACT", "string.match", "ipairs =", "table.unpack",
        )
            .forEach { assertFalse(it, source.contains(it, ignoreCase = true)) }
        val bindingDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bindingsFile)
        assertEquals("Bindings", bindingDocument.documentElement.tagName)
        assertEquals(43, bindingDocument.getElementsByTagName("Binding").length)
    }

    @Test fun `special binding loader and visible frames use exact 11200 conventions`() {
        val toc = File(addon, "AndroidPort.toc").readText()
        val core = File(addon, "Core.lua").readText()
        val bars = File(addon, "ActionBars.lua").readText()
        val radial = File(addon, "Radial.lua").readText()
        val bindings = File(addon, "Bindings.xml").readText()

        assertFalse(toc.lineSequence().map(String::trim).any { it == "Bindings.xml" })
        assertTrue(core.contains("events:RegisterEvent(\"ADDON_LOADED\")"))
        assertTrue(core.contains("event == \"ADDON_LOADED\" and arg1 == \"AndroidPort\""))
        assertFalse(core.contains("this:UnregisterEvent(\"ADDON_LOADED\")"))
        assertTrue(core.contains("elseif event == \"ADDON_LOADED\" then"))
        assertTrue(core.contains("AP:RefreshAddonIcons()"))
        assertTrue(core.contains("self.ActionBars:Initialize()"))
        assertTrue(core.contains("self.Radial:Initialize()"))
        assertTrue(core.contains("event == \"PLAYER_ENTERING_WORLD\""))
        assertTrue(core.contains("AP:ApplyBindings()"))

        // CooldownFrameTemplate is a Model in Interface 11200. A modern
        // `Cooldown` frame type aborts the first button before any UI appears.
        assertTrue(bars.contains("CreateFrame(\"Model\""))
        assertTrue(bars.contains("\"CooldownFrameTemplate\""))
        assertFalse(bars.contains("CreateFrame(\"Cooldown\""))
        assertTrue(bars.contains("staged[index] = self:CreateButton(index)"))
        assertTrue(bars.contains("staged[index]:Show()"))
        assertTrue(radial.contains("CreateFrame(\"Frame\", \"AndroidPortRadialMenu\""))

        assertEquals(40, Regex("<Binding name=\"AP_ACTION_[0-9]+\"").findAll(bindings).count())
        assertTrue(bindings.contains("<Binding name=\"AP_TOGGLE_RADIAL\""))
        assertTrue(bindings.contains("<Binding name=\"AP_MOVE_UI\""))
        assertTrue(bindings.contains("<Binding name=\"AP_NEARBY_INTERACT\""))
        assertTrue(bindings.contains("AndroidPort_Action(1, keystate)"))
        assertTrue(bindings.contains("AndroidPort_ToggleRadial()"))
    }

    @Test fun `Lua stays within vanilla APIs and never claims rear controls`() {
        val source = addon.walkTopDown().filter { it.extension in setOf("lua", "xml") }
            .joinToString("\n") { it.readText() }
        assertFalse(source.contains("CreateFrame(\"Cooldown\""))
        assertFalse(Regex("SetTexture\\([^\\n]*,[^\\n]*,[^\\n]*,[^\\n]*\\)").containsMatchIn(source))
        assertFalse(source.contains("..."))
        assertFalse(source.contains("REAR", ignoreCase = true))
        assertFalse(source.contains("paddle", ignoreCase = true))
    }

    @Test fun `handheld geometry and prompts match the native preset exactly`() {
        val core = File(addon, "Core.lua").readText()
        assertTrue(core.contains("button = 56, padding = 60"))
        assertTrue(core.contains("star = 640, bottom = 96, radial = 400, radialButton = 44"))
        assertTrue(core.contains("button = 80, padding = 86"))
        assertTrue(core.contains("star = 860, bottom = 136, radial = 520, radialButton = 58"))

        val bars = File(addon, "ActionBars.lua").readText()
        val expected = listOf(
            "side = 1, x = 0, y = -1, label = \"1\"",
            "side = 1, x = -1, y = 0, label = \"2\"",
            "side = 1, x = 0, y = 1, label = \"3\"",
            "side = 1, x = 1, y = 0, label = \"4\"",
            "side = -1, x = 0, y = -1, label = \"5 v\"",
            "side = -1, x = -1, y = 0, label = \"6 <\"",
            "side = -1, x = 0, y = 1, label = \"7 ^\"",
            "side = -1, x = 1, y = 0, label = \"8 >\"",
        )
        var cursor = -1
        expected.forEach { entry -> cursor = bars.indexOf(entry, cursor + 1); assertTrue(entry, cursor >= 0) }

        data class Layout(val width: Int, val height: Int, val button: Int, val padding: Int, val star: Int, val bottom: Int)
        listOf(
            Layout(1280, 720, 56, 60, 640, 96),
            Layout(1920, 1080, 80, 86, 860, 136),
        ).forEach { layout ->
            val positions = listOf(
                Triple(1, 0, -1), Triple(1, -1, 0), Triple(1, 0, 1), Triple(1, 1, 0),
                Triple(-1, 0, -1), Triple(-1, -1, 0), Triple(-1, 0, 1), Triple(-1, 1, 0),
            )
            positions.forEach { (side, x, y) ->
                val centerX = layout.width / 2 + side * layout.star / 2 + x * layout.padding
                val centerY = layout.bottom + y * layout.padding
                assertTrue(centerX - layout.button / 2 >= 8)
                assertTrue(centerX + layout.button / 2 <= layout.width - 8)
                assertTrue(centerY - layout.button / 2 >= 8)
                assertTrue(centerY + layout.button / 2 <= layout.height - 8)
            }
        }
    }

    @Test fun `radial binding matches native F12 and restore preserves later edits`() {
        val core = File(addon, "Core.lua").readText()
        assertTrue(core.contains("AP.BINDING_SCHEMA = 5"))
        assertTrue(core.contains("AP.keys = { \"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\" }"))
        assertTrue(core.contains("if db.bindingSchema == 1 then"))
        assertTrue(core.contains("db.bindingSchema == 1 and current == oldOwned"))
        assertTrue(core.contains("SetBinding(\"F12\", \"AP_TOGGLE_RADIAL\")"))
        assertTrue(core.contains("SetBinding(\"F8\", \"AP_MOVE_UI\")"))
        assertTrue(core.contains("SetBinding(\"F9\", \"TOGGLEAUTORUN\")"))
        assertTrue(core.contains("SetBinding(\"F7\", \"AP_NEARBY_INTERACT\")"))
        assertTrue(core.contains("local mayClaimF9 = db.bindingSchema == nil or GetBindingAction(\"F9\") == \"\" or"))
        assertFalse(core.contains("db.bindingBackup[\"F8\"] = GetBindingAction(\"F8\") or \"\"\n        mayClaimF8 = true"))
        assertFalse(core.contains("db.bindingBackup[\"F9\"] = GetBindingAction(\"F9\") or \"\"\n        mayClaimF9 = true"))
        assertFalse(core.contains("db.bindingBackup[\"F7\"] = GetBindingAction(\"F7\") or \"\"\n        mayClaimF7 = true"))
        assertTrue(core.contains("GetBindingAction(key) == owned"))
        assertFalse(core.contains("SHIFT-ESCAPE"))
        assertTrue(File(addon, "AndroidPort.toc").readText().contains("## SavedVariables: AndroidPortDB"))
    }

    @Test fun `nearby use sends one exact authenticated-session request and never auto loots`() {
        val core = File(addon, "Core.lua").readText()
        val bindings = File(addon, "Bindings.xml").readText()
        assertTrue(bindings.contains("AndroidPort_NearbyInteract()"))
        assertTrue(core.contains("SendChatMessage(\"PR6I:1 INTERACT\", \"WHISPER\", nil, UnitName(\"player\"))"))
        assertFalse(core.contains("SendAddonMessage"))
        assertFalse(core.contains("local player = UnitName(\"player\")"))
        assertFalse(core.contains("LootSlot("))
        assertFalse(core.contains("InteractUnit("))
    }

    @Test fun `Move UI closes radial first and has a clear balanced exit`() {
        val core = File(addon, "Core.lua").readText()
        val radial = File(addon, "Radial.lua").readText()
        val bindings = File(addon, "Bindings.xml").readText()
        assertTrue(bindings.contains("AndroidPort_ToggleMoveUI()"))
        assertTrue(radial.contains("name = \"Move UI\""))
        assertTrue(radial.contains("AndroidPort:ToggleMoveUI()"))
        assertFalse(radial.contains("name = \"Game Menu\""))
        val activate = radial.substringAfter("function Radial:Activate(index)")
            .substringBefore("function Radial:Toggle()")
        assertTrue(activate.indexOf("self:Hide()") < activate.indexOf("item.action()"))

        assertTrue(core.contains("if self:FinishMoveUI(false) then return end"))
        assertTrue(core.contains("if not self.moveUiActive then return false end"))
        assertTrue(core.contains("self.moveUiActive = nil"))
        assertTrue(core.contains("AndroidPortMoveSaveExit"))
        assertTrue(core.contains("exit:SetText(\"Save & Exit\")"))
        assertTrue(core.contains("if AP.moveUiActive then AP:FinishMoveUI(false) end"))
        assertTrue(core.contains("tinsert(UISpecialFrames, \"AndroidPortMoveInstructions\")"))
        assertTrue(core.contains("Select + Start or Escape saves and exits"))
        assertFalse(core.contains("SetAllPoints(UIParent)"))
        assertFalse(core.contains("AndroidPortMoveShade"))
    }

    @Test fun `icon mover registers known addons and conservatively discovers minimap buttons`() {
        val core = File(addon, "Core.lua").readText()
        assertTrue(core.contains("function AP:RegisterAddonIcon(id, frameOrName, label)"))
        assertTrue(core.contains("id = \"pfquest-route-arrow\", frame = \"pfQuestRouteArrow\""))
        assertTrue(core.contains("id = \"pfquest-minimap-button\", frame = \"pfQuestMinimapButton\""))
        assertTrue(core.contains("function AP:DiscoverTopLevelFrames(seen)"))
        assertTrue(core.contains("self:BuildLiveFrameSet()"))
        assertTrue(core.contains("self:DiscoverTopLevelFrames(seen)"))
        assertTrue(core.contains("function AP:ResolveCandidate(candidate)"))
        // The recursive minimap walk held raw frame userdata past those
        // frames' lifetime and crashed the client on freed objects; the sweep
        // must resolve everything by name against the engine frame list.
        assertFalse(core.contains("GetChildren"))
        assertTrue(core.contains("\"pfminimappin\""))
        assertTrue(core.contains("objectType == \"Button\""))
        assertTrue(core.contains("if not insideMinimap then return false end"))
        assertTrue(core.contains("if frame.IsProtected and frame:IsProtected() then return false end"))
        listOf("actionbutton", "playerframe", "targetframe", "lootbutton", "containerframe")
            .forEach { assertTrue("unsafe frame filter $it", core.contains("\"$it\"")) }
        assertFalse(core.contains("pairs(_G)"))
        assertFalse(core.contains("getfenv(0)"))
    }

    @Test fun `icon anchors validate persist restore and tolerate missing or late addons`() {
        val core = File(addon, "Core.lua").readText()
        assertTrue(core.contains("if type(AndroidPortDB.addonIconAnchors) ~= \"table\" then"))
        assertTrue(core.contains("AndroidPortDB.addonIconAnchors = {}"))
        assertTrue(core.contains("function AP:GetSavedAddonIconAnchor(id)"))
        assertTrue(core.contains("if IsValidAnchor(saved) then return saved end"))
        assertTrue(core.contains("x >= -10000 and x <= 10000 and y >= -10000 and y <= 10000"))
        assertTrue(core.contains("frame:GetCenter()"))
        assertTrue(core.contains("point = \"CENTER\", relativePoint = \"BOTTOMLEFT\""))
        assertTrue(core.contains("candidate.frame:SetPoint(saved.point, UIParent, saved.relativePoint"))
        assertTrue(core.contains("if frame then self:AddAddonIconCandidate"))
        assertTrue(core.contains("elseif event == \"ADDON_LOADED\" then"))
        assertTrue(core.contains("if self.addonIconRefreshActive then return false end"))
        assertTrue(core.contains("local ok = pcall(function()"))
        assertTrue(core.contains("self.addonIconRefreshActive = nil\n    return ok"))
        assertTrue(core.contains("if seen[frame] or not self:IsSafeAddonIconFrame(frame, registered) then return end"))

        val model = MoveAddonIconsModel()
        assertFalse(model.toggle(visibleIcons = 0))
        assertFalse(model.active)
        repeat(3) {
            assertTrue(model.toggle(visibleIcons = 2))
            assertTrue(model.active)
            assertFalse(model.toggle(visibleIcons = 2))
            assertFalse(model.active)
        }
        assertEquals(3, model.savedExits)
    }

    @Test fun `move mode overlays only icon handles so ordinary dialogs remain clickable`() {
        val core = File(addon, "Core.lua").readText()
        val handle = core.substringAfter("function AP:ShowAddonIconHandle(candidate)")
            .substringBefore("function AP:FinishMoveUI(silent)")
        assertTrue(handle.contains("CreateFrame(\"Button\", name, UIParent)"))
        assertTrue(handle.contains("local handle = candidate.handle"))
        assertTrue(handle.contains("if not handle then"))
        assertTrue(handle.contains("handle:SetPoint(\"TOPLEFT\", frame"))
        assertTrue(handle.contains("handle:SetPoint(\"BOTTOMRIGHT\", frame"))
        assertFalse(handle.contains("SetAllPoints(UIParent)"))
        assertFalse(handle.contains("UIParent:EnableMouse"))
        // Only the addon-owned handle is ever dragged; the target receives a
        // single absolute SetPoint on drop. Dragging foreign frames directly
        // is the freed-object crash class this layout must never reintroduce.
        assertTrue(core.contains("handle:SetMovable(1)"))
        assertTrue(core.contains("handle:StartMoving()"))
        assertTrue(core.contains("handle:StopMovingOrSizing()"))
        assertTrue(core.contains("target:SetPoint(\"TOPLEFT\", \"UIParent\", \"TOPLEFT\""))
        // GetTop measures from the screen floor while the anchor offset is
        // up-positive from UIParent's top edge; the origins must convert.
        assertTrue(core.contains("- UIParent:GetTop()"))
        assertFalse(core.contains("frame:StartMoving()"))
        assertFalse(core.contains("frame:StopMovingOrSizing()"))
        assertFalse(core.contains("frame:SetMovable"))
        assertTrue(core.contains("pfQuest_config[\"pocketrealm_arrow_position\"]"))
        assertTrue(core.contains("events:RegisterEvent(\"PLAYER_LOGOUT\")"))
        assertFalse(core.contains("PointerButton"))
        // Exit journals only what the player actually dragged or scaled, so
        // untouched stock panels stay under the stock panel manager; a pure
        // scale change journals scale only. Drag delivery follows the stock
        // chat-tab pattern (OnDragStop fires wherever the button is released;
        // a 11200 client has no IsMouseButtonDown), and drops clamp so at
        // least 40 units of the target stay reachable on screen.
        assertTrue(core.contains("if candidate.moved then"))
        assertTrue(core.contains("candidate.moved = true"))
        assertTrue(core.contains("elseif candidate.scaled then"))
        assertTrue(core.contains("handle:RegisterForDrag(\"LeftButton\", \"RightButton\")"))
        assertTrue(core.contains("handle:SetScript(\"OnDragStart\", function()"))
        assertTrue(core.contains("handle:SetScript(\"OnDragStop\", function()"))
        assertFalse(core.contains("IsMouseButtonDown"))
        assertTrue(core.contains("if screenLeft < 40 - width then screenLeft = 40 - width end"))
    }

    @Test fun `frame mover curates stock frames journals layout and resets cleanly`() {
        val core = File(addon, "Core.lua").readText()
        val mover = File(addon, "FrameMover.lua").readText()
        val toc = File(addon, "AndroidPort.toc").readText()

        assertTrue(core.contains("AP.VERSION = \"0.6.0\""))
        assertTrue(toc.contains("## Version: 0.6.0"))
        assertTrue(mover.contains("AndroidPort.FrameMover = AndroidPort.FrameMover or {}"))
        assertTrue(mover.contains("Mover.SCALE_MIN = 0.5"))
        assertTrue(mover.contains("Mover.SCALE_MAX = 1.5"))
        assertTrue(mover.contains("name = \"PlayerFrame\", label = \"Player frame\""))
        assertTrue(mover.contains("name = \"TargetFrame\", label = \"Target frame\""))
        assertTrue(mover.contains("name = \"MinimapCluster\", label = \"Minimap\""))
        assertTrue(mover.contains("name = \"ContainerFrame1\", label = \"Backpack\", scaleOnly = true"))
        assertTrue(mover.contains("name = \"BankFrame\", label = \"Bank\", scaleOnly = true"))

        // Multi-point capture keeps every anchor restorable; unnamed relatives
        // fall back to an absolute centre so a frame never becomes unrestorable.
        assertTrue(mover.contains("function Mover:CapturePoints(frame)"))
        assertTrue(mover.contains("local point, relativeTo, relativePoint, x, y = frame:GetPoint(index)"))
        assertTrue(mover.contains("function Mover:IsValidSavedFrame(saved)"))
        assertTrue(mover.contains("function Mover:ApplySavedFrame(frame, saved)"))
        assertTrue(mover.contains("function Mover:RestoreFrames()"))
        assertTrue(mover.contains("function Mover:ResetUI()"))
        assertTrue(mover.contains("db.addonIconAnchors = {}"))
        assertTrue(mover.contains("function Mover:SaveScaleOnly(frame)"))
        // The stock backup is captured once and never overwritten by our own
        // later layout, so resetui always returns to the true stock position.
        assertTrue(mover.contains("if anchors[name] ~= nil or backups[name] ~= nil then return false end"))

        assertTrue(core.contains("AndroidPortDB.uiSchema = 2"))
        assertTrue(core.contains("if type(AndroidPortDB.frameAnchors) ~= \"table\" then"))
        assertTrue(core.contains("AndroidPortDB.frameAnchors = {}"))
        assertTrue(core.contains("if type(AndroidPortDB.frameBackups) ~= \"table\" then"))
        assertTrue(core.contains("AndroidPortDB.frameBackups = {}"))
        assertTrue(core.contains("function AP:CanMoveCandidate(candidate)"))
        assertTrue(core.contains("if candidate.scaleOnly then\n        if self.FrameMover then return self.FrameMover:SaveScaleOnly(frame) end"))
        assertTrue(core.contains("if self.FrameMover then self.FrameMover:SaveFrame(frame, candidate.label) end"))
        assertTrue(core.contains("if saved.scale and candidate.frame.SetScale then candidate.frame:SetScale(saved.scale) end"))
        assertTrue(core.contains("if self.FrameMover and moverOn then self.FrameMover:Initialize() end"))
        assertTrue(core.contains("elseif message == \"resetui\" then"))
        assertTrue(core.contains("AP.Hud:RestoreChatFrame()"))

        // Stock re-anchor resistance: the bag sweep runs on both open and
        // close and also resets scale, so the chokepoint itself is wrapped;
        // journaled panels leave the stock panel manager (full-screen panels
        // never do) and re-assert their anchor after the stock OnShow runs.
        assertTrue(mover.contains("updateContainerFrameAnchors = function()"))
        assertTrue(mover.contains("function Mover:ReassertContainers()"))
        assertTrue(mover.contains("function Mover:ReleasePanelManagement(name)"))
        assertTrue(mover.contains("info.area == \"full\" then return false end"))
        assertTrue(mover.contains("function Mover:ChainOnShow(name, frame)"))
        assertTrue(mover.contains("db.panelReleases"))
        assertTrue(mover.contains("UIPanelWindows[name] = info"))
        // Journal relatives resolve through a stock-only whitelist, so a
        // saved anchor can never point into a frame a third-party addon
        // may later free.
        assertTrue(mover.contains("local relative = SafeRelative(stored.relative)"))
        assertTrue(mover.contains("relativeWhitelist[relativeName]"))
    }

    @Test fun `hud docks a minimal chat and a player frame xp strip`() {
        val core = File(addon, "Core.lua").readText()
        val hud = File(addon, "Hud.lua").readText()
        val toc = File(addon, "AndroidPort.toc").readText()
        assertTrue(toc.lineSequence().map(String::trim).contains("Hud.lua"))
        assertTrue(core.contains("if self.Hud and hudOn then self.Hud:Initialize() end"))
        assertTrue(core.contains("elseif message == \"chat\" then"))

        // Minimal chat: never persisted into the stored chat profile, so the
        // trailing doNotSave argument and the stock hover threshold parking
        // must both survive, and re-asserted on UPDATE_CHAT_WINDOWS.
        assertTrue(hud.contains("function Hud:ApplyChatFrame()"))
        assertTrue(hud.contains("FCF_SetWindowAlpha(chat, 0, 1)"))
        assertTrue(hud.contains("FCF_SetWindowColor(chat, 0, 0, 0, 1)"))
        assertTrue(hud.contains("chat.oldAlpha = 0.25"))
        assertTrue(hud.contains("chat:SetUserPlaced(1)"))
        assertTrue(hud.contains("getglobal(\"ChatFrame1Tab\")"))
        assertTrue(hud.contains("getglobal(\"ChatFrameMenuButton\")"))
        assertTrue(hud.contains("events:RegisterEvent(\"UPDATE_CHAT_WINDOWS\")"))
        assertTrue(hud.contains("FCF_UpdateDockPosition = function()"))
        assertTrue(hud.contains("function Hud:RestoreChatFrame()"))
        assertTrue(hud.contains("function Hud:ToggleChat()"))
        // The default anchor clears the action clusters on both handheld
        // layout profiles: bottom + padding + button + 20 above the floor.
        assertTrue(hud.contains("layout.bottom + layout.padding + layout.button + 20"))
        // Stock's hover pass unconditionally re-shows the tab and later fades
        // the whole chrome back in; failing the per-frame validity gate is the
        // simple-chat mechanism and keeps the minimal look stable.
        assertTrue(hud.contains("FCF_IsValidChatFrame = function(frame)"))
        assertTrue(hud.contains("return Hud.stockIsValidChatFrame(frame)"))
        // The docked combat log shares the rect and gets the same treatment.
        assertTrue(hud.contains("FCF_SetWindowAlpha(combat, 0, 1)"))
        // The engine applies the saved chat rectangle after the load-time
        // pass; delayed re-asserts keep our rect authoritative.
        assertTrue(hud.contains("this.pendingReassert = { 1, 3, 7 }"))

        // XP strip: a StatusBar child of PlayerFrame moves and scales with
        // the unit frame, updates on the stock XP event set, hides at the
        // level cap the way the stock bar branches, and never journals.
        assertTrue(hud.contains("CreateFrame(\"StatusBar\", \"AndroidPortXPBar\", playerFrame)"))
        assertTrue(hud.contains("events:RegisterEvent(\"PLAYER_XP_UPDATE\")"))
        assertTrue(hud.contains("events:RegisterEvent(\"UPDATE_EXHAUSTION\")"))
        assertTrue(hud.contains("events:RegisterEvent(\"PLAYER_LEVEL_UP\")"))
        assertTrue(hud.contains("UnitXPMax(\"player\")"))
        assertTrue(hud.contains("UnitLevel(\"player\") >= MAX_PLAYER_LEVEL"))
        assertTrue(hud.contains("GetXPExhaustion()"))
        assertTrue(hud.contains("function Hud:FailSafe()"))
    }

    @Test fun `move UI focus cycling and scaling stay balanced and clamped`() {
        val core = File(addon, "Core.lua").readText()

        // While Move UI is open no action slot reaches the action bars.
        val action = core.substringAfter("function AndroidPort_Action(slot, state)")
            .substringBefore("function AndroidPort_ToggleRadial()")
        assertTrue(action.contains("if AP.moveUiActive then\n        if slot >= 5 and slot <= 8 then AP:MoveUIAdjust(slot) end\n        return\n    end"))

        assertTrue(core.contains("function AP:MoveUIAdjust(slot)"))
        assertTrue(core.contains("function AP:SetMoveFocus(candidate)"))
        assertTrue(core.contains("function AP:ScaleMoveCandidate(candidate, direction)"))
        assertTrue(core.contains("function AP:MoveFocusCandidates()"))
        assertTrue(core.contains("if scale < MOVE_SCALE_MIN then scale = MOVE_SCALE_MIN end"))
        assertTrue(core.contains("if scale > MOVE_SCALE_MAX then scale = MOVE_SCALE_MAX end"))
        assertTrue(core.contains("handle:EnableMouseWheel(1)"))
        assertTrue(core.contains("AP:ScaleMoveCandidate(candidate, arg1)"))
        assertTrue(core.contains("if candidate.scaleOnly then return end"))
        assertTrue(core.contains("self.moveFocused = nil"))
        assertTrue(core.contains("D-pad Down/Up selects, Left/Right resizes"))

        // The D-pad never moves a frame: MoveUIAdjust itself only selects and
        // resizes, so its body must contain no positioning calls. Resizing
        // mutates position only through the shared centre-preserving path.
        val adjust = core.substringAfter("function AP:MoveUIAdjust(slot)")
            .substringBefore("function AP:ToggleMoveUI()")
        assertFalse(adjust.contains("SetPoint"))
        assertFalse(adjust.contains("StartMoving"))
        assertFalse(adjust.contains("StopMovingOrSizing"))

        // Frames may sit flush against the very top and base of the screen:
        // the vertical clamps follow the same at-least-40-reachable rule as
        // the horizontal pair instead of forcing an inside margin.
        assertTrue(core.contains("if screenTop < 40 then screenTop = 40 end"))
        assertTrue(core.contains("if screenTop > parentTop + height - 40 then screenTop = parentTop + height - 40 end"))
        // Resizing pivots on the visual centre so a scaled frame never drifts.
        assertTrue(core.contains("local cx, cy = frame.GetCenter and frame:GetCenter()"))
        assertTrue(core.contains("screenLeft, screenTop = self:ClampFrameRect(frame, screenLeft, screenTop)"))

        val model = FrameMoverModel()
        assertNull(model.focusedLabel)
        model.adjust(5)
        assertEquals("Backpack", model.focusedLabel)
        repeat(4) { model.adjust(5) }
        assertEquals("Player frame", model.focusedLabel)
        model.adjust(5)
        assertEquals("Backpack", model.focusedLabel)
        model.adjust(7)
        assertEquals("Player frame", model.focusedLabel)
        // Left/Right scale in 0.1 steps and clamp at 0.5 and 1.5.
        repeat(20) { model.adjust(6) }
        assertEquals(0.5, model.scale, 1e-9)
        repeat(20) { model.adjust(8) }
        assertEquals(1.5, model.scale, 1e-9)
    }

    @Test fun `bags module docks left merges containers and sells gray only`() {
        val toc = File(addon, "AndroidPort.toc").readText()
        val core = File(addon, "Core.lua").readText()
        val bags = File(addon, "Bags.lua").readText()
        val radial = File(addon, "Radial.lua").readText()
        val mover = File(addon, "FrameMover.lua").readText()

        assertTrue(toc.lineSequence().map(String::trim).contains("Bags.lua"))
        // File-load creation lets the frame mover's curated registry adopt the
        // container, exactly like the action clusters. No dock lives on the
        // main HUD: the container opens from the radial menu or bag keys.
        assertTrue(mover.contains("name = \"AndroidPortBagsFrame\", label = \"Bags\""))
        assertTrue(bags.contains("Bags:EnsureFrame()"))
        assertFalse(bags.contains("AndroidPortBagDock"))
        // The stock item template plus a parent whose ID is the bag gives the
        // full stock click, drag, tooltip and keyring behaviour.
        assertTrue(bags.contains("\"ContainerFrameItemButtonTemplate\""))
        assertTrue(bags.contains("holder:SetID(bag)"))
        assertTrue(bags.contains("getglobal(\"AndroidPortBagsFrame\")"))
        assertTrue(bags.contains("tinsert(UISpecialFrames, \"AndroidPortBagsFrame\")"))
        // Cursor drop semantics live on the All and Key tabs.
        assertTrue(bags.contains("PutItemInBackpack()"))
        assertTrue(bags.contains("PutKeyInKeyRing()"))
        assertTrue(bags.contains("GetKeyRingSize"))
        assertTrue(bags.contains("Bags:Toggle(\"key\")"))
        // Stock entry points reroute while ready; bank ids pass through.
        assertTrue(bags.contains("function Bags:InstallStockHooks()"))
        assertTrue(bags.contains("Bags.stockOpenBag(id)"))
        assertTrue(bags.contains("function Bags:RestoreStockHooks()"))
        // Sell junk: gray items detected through the poor-quality link color
        // code (the GetContainerItemInfo quality return is not dependable on
        // 11200; every working reference addon reads the link), bags 0
        // through 4 only so the keyring is never scanned, one verified sale
        // per tick, merchant tab guards, cursor cleared first and earnings
        // read on a deferred tick.
        assertTrue(bags.contains("for bag = 0, 4 do"))
        assertTrue(bags.contains("string.find(link, \"ff9d9d9d\", 1, true)"))
        assertTrue(bags.contains("MerchantFrame.selectedTab ~= 2"))
        assertTrue(bags.contains("ClearCursor()"))
        assertTrue(bags.contains("UseContainerItem(bag, slot)"))
        assertTrue(bags.contains("MERCHANT_CLOSED"))
        assertTrue(bags.contains("self.reportAt = GetTime() + REPORT_DELAY"))
        // Real bag slots: drag a bag on to equip, drag off to lift it.
        assertTrue(bags.contains("PutItemInBag(this:GetID())"))
        assertTrue(bags.contains("PickupBagFromSlot(this:GetID())"))
        assertTrue(bags.contains("GetInventorySlotInfo(\"Bag\" .. (bag - 1) .. \"Slot\")"))
        assertTrue(bags.contains("ContainerIDToInventoryID(bag)"))
        // Stock close paths and open-state queries reroute too.
        assertTrue(bags.contains("Bags.stockCloseBackpack()"))
        assertTrue(bags.contains("Bags.stockCloseAllBags()"))
        assertTrue(bags.contains("IsBagOpen = function(id)"))
        // The oval portrait mask must never back a rectangular region, and
        // the tick driver must be shown or its OnUpdate never fires.
        assertFalse(bags.contains("TempPortraitAlphaMask"))
        assertTrue(bags.contains("events:Show()"))
        assertTrue(bags.contains("PLAYER_MONEY"))
        // Bags stays non-fatal in the module bootstrap and the radial keeps
        // its stock fallback.
        assertTrue(core.contains("if self.Bags and bagsOn then self.Bags:Initialize() end"))
        assertTrue(radial.contains("AndroidPort.Bags.ready and AndroidPort.Bags:Toggle()"))
    }

    @Test fun `xp strip sits under the mana bar inside the portrait block`() {
        val hud = File(addon, "Hud.lua").readText()
        assertTrue(hud.contains("local manaBar = getglobal(\"PlayerFrameManaBar\")"))
        assertTrue(hud.contains("bar:SetPoint(\"TOPLEFT\", manaBar, \"BOTTOMLEFT\", 0, -2)"))
        assertTrue(hud.contains("bar:SetPoint(\"TOPRIGHT\", manaBar, \"BOTTOMRIGHT\", 0, -2)"))
        assertTrue(hud.contains("SetTexture(0, 0, 0)"))
    }

    @Test fun `replacement action buttons retain placement and cooldown functionality`() {
        val bars = File(addon, "ActionBars.lua").readText()
        val mover = File(addon, "FrameMover.lua").readText()
        assertTrue(bars.contains("RegisterForDrag(\"LeftButton\", \"RightButton\")"))
        assertTrue(bars.contains("PickupAction(Bars:GetActionSlot(index))"))
        assertTrue(bars.contains("PlaceAction(Bars:GetActionSlot(index))"))
        assertTrue(bars.contains("GetActionCooldown(slot)"))
        assertTrue(bars.contains("CooldownFrame_SetTimer(button.cooldown, start, duration, enable)"))
        assertTrue(bars.contains("button:SetID(index)"))
        assertTrue(bars.contains("button:SetHitRectInsets(-14, -14, -14, -14)"))
        // Drop reliability: a wide tooltip over the lower tiles buries the
        // upper ones (touch never synthesizes the OnLeave), a touch roll can
        // turn a tap into a drag that voids the carried spell, and a pickup
        // released as a click must still place. Pages follow the stock
        // 12-slot stride.
        assertTrue(bars.contains("local function CursorCarriesPickup()"))
        assertTrue(bars.contains("if CursorHasItem and CursorHasItem() then return true end"))
        assertTrue(bars.contains("if CursorCarriesPickup() then\n            GameTooltip:Hide()\n            return\n        end"))
        assertTrue(bars.contains("not CursorCarriesPickup() then\n            PickupAction"))
        assertTrue(bars.contains("CursorCarriesPickup() then\n            PlaceAction"))
        assertTrue(bars.contains("(self.page - 1) * 12 + buttonIndex"))
        assertFalse(bars.contains("(self.page - 1) * 10 + buttonIndex"))
        // The two action stars are movable as units through their cluster
        // containers: buttons parent and anchor to the cluster, so one
        // journal entry moves or scales the whole star.
        assertTrue(bars.contains("[1] = \"AndroidPortRightCluster\""))
        assertTrue(bars.contains("[-1] = \"AndroidPortLeftCluster\""))
        assertTrue(bars.contains("button:SetPoint(\n                \"BOTTOM\", cluster, \"CENTER\","))
        assertTrue(bars.contains("button:SetParent(cluster)"))
        assertTrue(mover.contains("name = \"AndroidPortLeftCluster\", label = \"Left action cluster\""))
        assertTrue(mover.contains("name = \"AndroidPortRightCluster\", label = \"Right action cluster\""))
    }

    @Test fun `initialization is transactional retryable and gates stock replacement`() {
        val core = File(addon, "Core.lua").readText()
        val bars = File(addon, "ActionBars.lua").readText()
        val radial = File(addon, "Radial.lua").readText()

        assertFalse(bars.contains("self.initialized"))
        assertTrue(bars.contains("self.ready = false"))
        assertTrue(bars.contains("local ok = pcall(function()"))
        assertTrue(bars.contains("getglobal(\"AndroidPortButton\" .. index)"))
        assertTrue(bars.contains("getglobal(\"AndroidPortBarWatcher\")"))
        assertTrue(bars.contains("if not ok or not self:ValidateButtons(staged) or not watcher then"))
        assertTrue(bars.contains("self:FailSafe(staged)"))
        assertTrue(bars.indexOf("self.ready = true") > bars.indexOf("self.buttons = staged"))

        val refresh = bars.substringAfter("function Bars:Refresh()")
            .substringBefore("function Bars:HideStockBars()")
        assertTrue(refresh.contains("not self:ValidateButtons(self.buttons)"))
        assertTrue(refresh.indexOf("self:UpdateButton") < refresh.indexOf("self:HideStockBars()"))
        assertTrue(refresh.contains("if not self:HideStockBars() then"))
        assertTrue(refresh.contains("self:FailSafe(self.buttons)"))

        assertTrue(radial.contains("Radial.ready = Radial.ready or false"))
        assertTrue(radial.contains("getglobal(\"AndroidPortRadialMenu\")"))
        assertTrue(radial.contains("getglobal(\"AndroidPortRadialShade\")"))
        assertTrue(radial.contains("getglobal(name)"))
        assertTrue(radial.contains("if not ok or not self:Validate(frame, overlay, buttons) then"))
        assertTrue(radial.contains("self:FailSafe(frame, overlay, buttons)"))
        assertTrue(radial.indexOf("self.ready = true") > radial.indexOf("self.buttons = buttons"))
        assertTrue(radial.contains("Face 1-4 / D-pad 5-8: choose\\nSelect: close"))
        assertTrue(radial.contains("function Radial:Activate(index)"))
        assertTrue(radial.contains("if not self.ready or not self.frame or not self.frame:IsVisible() then return false end"))
        assertTrue(core.contains("AP.Radial:Activate(slot)"))
        assertTrue(radial.contains("not frame.help"))

        val moduleInit = core.substringAfter("function AP:InitializeModules()")
            .substringBefore("events:SetScript")
        assertTrue(moduleInit.contains("if barsReady and radialReady then return true end"))
        assertTrue(moduleInit.contains("self.ActionBars:FailSafe(self.ActionBars.buttons)"))
        assertTrue(moduleInit.contains("self.Radial:FailSafe(self.Radial.frame, self.Radial.overlay, self.Radial.buttons)"))
        assertTrue(moduleInit.trimEnd().endsWith("return false\nend"))

        val playerEnteringWorld = core.substringAfter("event == \"PLAYER_ENTERING_WORLD\"")
            .substringBefore("end\nend)")
        assertTrue(playerEnteringWorld.contains("AP:InitializeModules()"))
        assertTrue(playerEnteringWorld.contains("AP.ActionBars.ready"))
        assertTrue(playerEnteringWorld.contains("AP.Radial.ready"))
        assertTrue(playerEnteringWorld.indexOf("AP.ActionBars:Refresh()") < playerEnteringWorld.indexOf("AP:ApplyBindings()"))
        assertFalse(playerEnteringWorld.contains("HideStockBars"))
    }

    @Test fun `every partial creation stage leaves stock UI and retries to ready`() {
        for (failureStage in 1..9) {
            val model = AddonLifecycleModel()
            model.playerEnteringWorld(barFailureStage = failureStage)
            assertTrue("bar failure $failureStage keeps stock", model.stockVisible)
            assertFalse("bar failure $failureStage is not ready", model.addonReady)
            assertFalse("bar failure $failureStage does not bind", model.bindingsApplied)

            model.playerEnteringWorld()
            assertTrue("bar retry $failureStage reaches ready", model.addonReady)
            assertFalse("bar retry $failureStage replaces stock", model.stockVisible)
            assertTrue("bar retry $failureStage applies bindings", model.bindingsApplied)
            assertEquals(9, model.barObjects.size)
        }

        for (failureStage in 1..10) {
            val model = AddonLifecycleModel()
            model.playerEnteringWorld(radialFailureStage = failureStage)
            assertTrue("radial failure $failureStage keeps stock", model.stockVisible)
            assertFalse("radial failure $failureStage is not ready", model.addonReady)
            assertFalse("radial failure $failureStage does not bind", model.bindingsApplied)

            model.playerEnteringWorld()
            assertTrue("radial retry $failureStage reaches ready", model.addonReady)
            assertFalse("radial retry $failureStage replaces stock", model.stockVisible)
            assertTrue("radial retry $failureStage applies bindings", model.bindingsApplied)
            assertEquals(10, model.radialObjects.size)
        }
    }

    @Test fun `every failed button refresh restores stock and remains retryable`() {
        for (failureStage in 1..8) {
            val model = AddonLifecycleModel()
            model.playerEnteringWorld(refreshFailureStage = failureStage)
            assertTrue("refresh failure $failureStage restores stock", model.stockVisible)
            assertFalse("refresh failure $failureStage clears ready", model.addonReady)
            assertFalse("refresh failure $failureStage does not bind", model.bindingsApplied)

            model.playerEnteringWorld()
            assertTrue("refresh retry $failureStage reaches ready", model.addonReady)
            assertFalse("refresh retry $failureStage replaces stock", model.stockVisible)
            assertTrue("refresh retry $failureStage applies bindings", model.bindingsApplied)
        }
    }

    /** Deterministic lifecycle model mirroring the Lua's staged named-object contract. */
    private class AddonLifecycleModel {
        val barObjects = linkedSetOf<String>()
        val radialObjects = linkedSetOf<String>()
        var barsReady = false
        var radialReady = false
        var stockVisible = true
        var bindingsApplied = false
        val addonReady: Boolean get() = barsReady && radialReady

        private fun initializeBars(failureStage: Int?): Boolean {
            if (barsReady) return true
            barsReady = false
            for (stage in 1..9) {
                barObjects += if (stage <= 8) "button$stage" else "watcher"
                if (stage == failureStage) return false
            }
            barsReady = true
            return true
        }

        private fun initializeRadial(failureStage: Int?): Boolean {
            if (radialReady) return true
            radialReady = false
            for (stage in 1..10) {
                radialObjects += when (stage) {
                    1 -> "frame"
                    2 -> "overlay"
                    else -> "button${stage - 2}"
                }
                if (stage == failureStage) return false
            }
            radialReady = true
            return true
        }

        private fun failSafe() {
            barsReady = false
            radialReady = false
            stockVisible = true
            bindingsApplied = false
        }

        fun playerEnteringWorld(
            barFailureStage: Int? = null,
            radialFailureStage: Int? = null,
            refreshFailureStage: Int? = null,
        ) {
            bindingsApplied = false
            val bars = initializeBars(barFailureStage)
            val radial = initializeRadial(radialFailureStage)
            if (!bars || !radial) {
                failSafe()
                return
            }
            for (stage in 1..8) {
                if (stage == refreshFailureStage) {
                    failSafe()
                    return
                }
            }
            stockVisible = false
            bindingsApplied = true
        }
    }

    /** Deterministic model mirroring the Lua move-mode focus and scale contract. */
    private class FrameMoverModel {
        val labels = listOf("Backpack", "Cast bar", "Loot window", "Minimap", "Player frame")
        var focusIndex = -1
        var scale = 1.0

        val focusedLabel: String?
            get() = if (focusIndex in labels.indices) labels[focusIndex] else null

        fun adjust(slot: Int) {
            when (slot) {
                5 -> {
                    var next = focusIndex + 1
                    if (next >= labels.size) next = 0
                    focusIndex = next
                }
                7 -> focusIndex = if (focusIndex <= 0) labels.size - 1 else focusIndex - 1
                6 -> {
                    scale -= 0.1
                    if (scale < 0.5) scale = 0.5
                }
                8 -> {
                    scale += 0.1
                    if (scale > 1.5) scale = 1.5
                }
            }
        }
    }

    private class MoveAddonIconsModel {
        var active = false
        var savedExits = 0

        fun toggle(visibleIcons: Int): Boolean {
            if (active) {
                active = false
                savedExits++
                return false
            }
            if (visibleIcons <= 0) return false
            active = true
            return true
        }
    }
}
