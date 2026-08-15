package com.pocketrealm.addons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VanillaConsolePortAssetTest {
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
        ).first { it.isDirectory }
    }
    private val addon = File(assetRoot, "addons/vanilla-console-port/VanillaConsolePort")

    @Test fun `built in package is a clean Interface 11200 Lua addon`() {
        val toc = File(addon, "VanillaConsolePort.toc").readText()
        assertTrue(toc.lineSequence().any { it.trim() == "## Interface: 11200" })
        val declared = toc.lineSequence().map(String::trim)
            .filter { it.endsWith(".lua") || it.endsWith(".xml") }.toList()
        assertEquals(listOf("Core.lua", "ActionBars.lua", "Radial.lua", "FrameMover.lua", "Hud.lua"), declared)
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
        val toc = File(addon, "VanillaConsolePort.toc").readText()
        val core = File(addon, "Core.lua").readText()
        val bars = File(addon, "ActionBars.lua").readText()
        val radial = File(addon, "Radial.lua").readText()
        val bindings = File(addon, "Bindings.xml").readText()

        assertFalse(toc.lineSequence().map(String::trim).any { it == "Bindings.xml" })
        assertTrue(core.contains("events:RegisterEvent(\"ADDON_LOADED\")"))
        assertTrue(core.contains("event == \"ADDON_LOADED\" and arg1 == \"VanillaConsolePort\""))
        assertFalse(core.contains("this:UnregisterEvent(\"ADDON_LOADED\")"))
        assertTrue(core.contains("elseif event == \"ADDON_LOADED\" then"))
        assertTrue(core.contains("VCP:RefreshAddonIcons()"))
        assertTrue(core.contains("self.ActionBars:Initialize()"))
        assertTrue(core.contains("self.Radial:Initialize()"))
        assertTrue(core.contains("event == \"PLAYER_ENTERING_WORLD\""))
        assertTrue(core.contains("VCP:ApplyBindings()"))

        // CooldownFrameTemplate is a Model in Interface 11200. A modern
        // `Cooldown` frame type aborts the first button before any UI appears.
        assertTrue(bars.contains("CreateFrame(\"Model\""))
        assertTrue(bars.contains("\"CooldownFrameTemplate\""))
        assertFalse(bars.contains("CreateFrame(\"Cooldown\""))
        assertTrue(bars.contains("staged[index] = self:CreateButton(index)"))
        assertTrue(bars.contains("staged[index]:Show()"))
        assertTrue(radial.contains("CreateFrame(\"Frame\", \"VanillaConsolePortRadialMenu\""))

        assertEquals(40, Regex("<Binding name=\"VCP_ACTION_[0-9]+\"").findAll(bindings).count())
        assertTrue(bindings.contains("<Binding name=\"VCP_TOGGLE_RADIAL\""))
        assertTrue(bindings.contains("<Binding name=\"VCP_MOVE_UI\""))
        assertTrue(bindings.contains("<Binding name=\"VCP_NEARBY_INTERACT\""))
        assertTrue(bindings.contains("VanillaConsolePort_Action(1, keystate)"))
        assertTrue(bindings.contains("VanillaConsolePort_ToggleRadial()"))
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
        assertTrue(core.contains("VCP.BINDING_SCHEMA = 5"))
        assertTrue(core.contains("VCP.keys = { \"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\" }"))
        assertTrue(core.contains("if db.bindingSchema == 1 then"))
        assertTrue(core.contains("db.bindingSchema == 1 and current == oldOwned"))
        assertTrue(core.contains("SetBinding(\"F12\", \"VCP_TOGGLE_RADIAL\")"))
        assertTrue(core.contains("SetBinding(\"F8\", \"VCP_MOVE_UI\")"))
        assertTrue(core.contains("SetBinding(\"F9\", \"TOGGLEAUTORUN\")"))
        assertTrue(core.contains("SetBinding(\"F7\", \"VCP_NEARBY_INTERACT\")"))
        assertTrue(core.contains("local mayClaimF9 = db.bindingSchema == nil or GetBindingAction(\"F9\") == \"\" or"))
        assertFalse(core.contains("db.bindingBackup[\"F8\"] = GetBindingAction(\"F8\") or \"\"\n        mayClaimF8 = true"))
        assertFalse(core.contains("db.bindingBackup[\"F9\"] = GetBindingAction(\"F9\") or \"\"\n        mayClaimF9 = true"))
        assertFalse(core.contains("db.bindingBackup[\"F7\"] = GetBindingAction(\"F7\") or \"\"\n        mayClaimF7 = true"))
        assertTrue(core.contains("GetBindingAction(key) == owned"))
        assertFalse(core.contains("SHIFT-ESCAPE"))
        assertTrue(File(addon, "VanillaConsolePort.toc").readText().contains("## SavedVariables: VanillaConsolePortDB"))
    }

    @Test fun `nearby use sends one exact authenticated-session request and never auto loots`() {
        val core = File(addon, "Core.lua").readText()
        val bindings = File(addon, "Bindings.xml").readText()
        assertTrue(bindings.contains("VanillaConsolePort_NearbyInteract()"))
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
        assertTrue(bindings.contains("VanillaConsolePort_ToggleMoveUI()"))
        assertTrue(radial.contains("name = \"Move UI\""))
        assertTrue(radial.contains("VanillaConsolePort:ToggleMoveUI()"))
        assertFalse(radial.contains("name = \"Game Menu\""))
        val activate = radial.substringAfter("function Radial:Activate(index)")
            .substringBefore("function Radial:Toggle()")
        assertTrue(activate.indexOf("self:Hide()") < activate.indexOf("item.action()"))

        assertTrue(core.contains("if self:FinishMoveUI(false) then return end"))
        assertTrue(core.contains("if not self.moveUiActive then return false end"))
        assertTrue(core.contains("self.moveUiActive = nil"))
        assertTrue(core.contains("VanillaConsolePortMoveSaveExit"))
        assertTrue(core.contains("exit:SetText(\"Save & Exit\")"))
        assertTrue(core.contains("if VCP.moveUiActive then VCP:FinishMoveUI(false) end"))
        assertTrue(core.contains("tinsert(UISpecialFrames, \"VanillaConsolePortMoveInstructions\")"))
        assertTrue(core.contains("Select + Start or Escape saves and exits"))
        assertFalse(core.contains("SetAllPoints(UIParent)"))
        assertFalse(core.contains("VanillaConsolePortMoveShade"))
    }

    @Test fun `icon mover registers known addons and conservatively discovers minimap buttons`() {
        val core = File(addon, "Core.lua").readText()
        assertTrue(core.contains("function VCP:RegisterAddonIcon(id, frameOrName, label)"))
        assertTrue(core.contains("id = \"pfquest-route-arrow\", frame = \"pfQuestRouteArrow\""))
        assertTrue(core.contains("id = \"pfquest-minimap-button\", frame = \"pfQuestMinimapButton\""))
        assertTrue(core.contains("function VCP:DiscoverTopLevelFrames(seen)"))
        assertTrue(core.contains("self:BuildLiveFrameSet()"))
        assertTrue(core.contains("self:DiscoverTopLevelFrames(seen)"))
        assertTrue(core.contains("function VCP:ResolveCandidate(candidate)"))
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
        assertTrue(core.contains("if type(VanillaConsolePortDB.addonIconAnchors) ~= \"table\" then"))
        assertTrue(core.contains("VanillaConsolePortDB.addonIconAnchors = {}"))
        assertTrue(core.contains("function VCP:GetSavedAddonIconAnchor(id)"))
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
        val handle = core.substringAfter("function VCP:ShowAddonIconHandle(candidate)")
            .substringBefore("function VCP:FinishMoveUI(silent)")
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
        val toc = File(addon, "VanillaConsolePort.toc").readText()

        assertTrue(core.contains("VCP.VERSION = \"0.5.0\""))
        assertTrue(toc.contains("## Version: 0.5.0"))
        assertTrue(mover.contains("VanillaConsolePort.FrameMover = VanillaConsolePort.FrameMover or {}"))
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

        assertTrue(core.contains("VanillaConsolePortDB.uiSchema = 2"))
        assertTrue(core.contains("if type(VanillaConsolePortDB.frameAnchors) ~= \"table\" then"))
        assertTrue(core.contains("VanillaConsolePortDB.frameAnchors = {}"))
        assertTrue(core.contains("if type(VanillaConsolePortDB.frameBackups) ~= \"table\" then"))
        assertTrue(core.contains("VanillaConsolePortDB.frameBackups = {}"))
        assertTrue(core.contains("function VCP:CanMoveCandidate(candidate)"))
        assertTrue(core.contains("if candidate.scaleOnly then\n        if self.FrameMover then return self.FrameMover:SaveScaleOnly(frame) end"))
        assertTrue(core.contains("if self.FrameMover then self.FrameMover:SaveFrame(frame, candidate.label) end"))
        assertTrue(core.contains("if saved.scale and candidate.frame.SetScale then candidate.frame:SetScale(saved.scale) end"))
        assertTrue(core.contains("if self.FrameMover then self.FrameMover:Initialize() end"))
        assertTrue(core.contains("elseif message == \"resetui\" then"))
        assertTrue(core.contains("VCP.Hud:RestoreChatFrame()"))

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
        val toc = File(addon, "VanillaConsolePort.toc").readText()
        assertTrue(toc.lineSequence().map(String::trim).contains("Hud.lua"))
        assertTrue(core.contains("if self.Hud then self.Hud:Initialize() end"))
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
        assertTrue(hud.contains("CreateFrame(\"StatusBar\", \"VanillaConsolePortXPBar\", playerFrame)"))
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
        val action = core.substringAfter("function VanillaConsolePort_Action(slot, state)")
            .substringBefore("function VanillaConsolePort_ToggleRadial()")
        assertTrue(action.contains("if VCP.moveUiActive then\n        if slot >= 5 and slot <= 8 then VCP:MoveUIAdjust(slot) end\n        return\n    end"))

        assertTrue(core.contains("function VCP:MoveUIAdjust(slot)"))
        assertTrue(core.contains("function VCP:SetMoveFocus(candidate)"))
        assertTrue(core.contains("function VCP:ScaleMoveCandidate(candidate, direction)"))
        assertTrue(core.contains("function VCP:MoveFocusCandidates()"))
        assertTrue(core.contains("if scale < MOVE_SCALE_MIN then scale = MOVE_SCALE_MIN end"))
        assertTrue(core.contains("if scale > MOVE_SCALE_MAX then scale = MOVE_SCALE_MAX end"))
        assertTrue(core.contains("handle:EnableMouseWheel(1)"))
        assertTrue(core.contains("VCP:ScaleMoveCandidate(candidate, arg1)"))
        assertTrue(core.contains("if candidate.scaleOnly then return end"))
        assertTrue(core.contains("self.moveFocused = nil"))
        assertTrue(core.contains("D-pad Down/Up focuses, Left/Right scales"))

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
        // The two action stars are movable as units through their cluster
        // containers: buttons parent and anchor to the cluster, so one
        // journal entry moves or scales the whole star.
        assertTrue(bars.contains("[1] = \"VanillaConsolePortRightCluster\""))
        assertTrue(bars.contains("[-1] = \"VanillaConsolePortLeftCluster\""))
        assertTrue(bars.contains("button:SetPoint(\n                \"BOTTOM\", cluster, \"CENTER\","))
        assertTrue(bars.contains("button:SetParent(cluster)"))
        assertTrue(mover.contains("name = \"VanillaConsolePortLeftCluster\", label = \"Left action cluster\""))
        assertTrue(mover.contains("name = \"VanillaConsolePortRightCluster\", label = \"Right action cluster\""))
    }

    @Test fun `initialization is transactional retryable and gates stock replacement`() {
        val core = File(addon, "Core.lua").readText()
        val bars = File(addon, "ActionBars.lua").readText()
        val radial = File(addon, "Radial.lua").readText()

        assertFalse(bars.contains("self.initialized"))
        assertTrue(bars.contains("self.ready = false"))
        assertTrue(bars.contains("local ok = pcall(function()"))
        assertTrue(bars.contains("getglobal(\"VanillaConsolePortButton\" .. index)"))
        assertTrue(bars.contains("getglobal(\"VanillaConsolePortBarWatcher\")"))
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
        assertTrue(radial.contains("getglobal(\"VanillaConsolePortRadialMenu\")"))
        assertTrue(radial.contains("getglobal(\"VanillaConsolePortRadialShade\")"))
        assertTrue(radial.contains("getglobal(name)"))
        assertTrue(radial.contains("if not ok or not self:Validate(frame, overlay, buttons) then"))
        assertTrue(radial.contains("self:FailSafe(frame, overlay, buttons)"))
        assertTrue(radial.indexOf("self.ready = true") > radial.indexOf("self.buttons = buttons"))
        assertTrue(radial.contains("Face 1-4 / D-pad 5-8: choose\\nSelect: close"))
        assertTrue(radial.contains("function Radial:Activate(index)"))
        assertTrue(radial.contains("if not self.ready or not self.frame or not self.frame:IsVisible() then return false end"))
        assertTrue(core.contains("VCP.Radial:Activate(slot)"))
        assertTrue(radial.contains("not frame.help"))

        val moduleInit = core.substringAfter("function VCP:InitializeModules()")
            .substringBefore("events:SetScript")
        assertTrue(moduleInit.contains("if barsReady and radialReady then return true end"))
        assertTrue(moduleInit.contains("self.ActionBars:FailSafe(self.ActionBars.buttons)"))
        assertTrue(moduleInit.contains("self.Radial:FailSafe(self.Radial.frame, self.Radial.overlay, self.Radial.buttons)"))
        assertTrue(moduleInit.trimEnd().endsWith("return false\nend"))

        val playerEnteringWorld = core.substringAfter("event == \"PLAYER_ENTERING_WORLD\"")
            .substringBefore("end\nend)")
        assertTrue(playerEnteringWorld.contains("VCP:InitializeModules()"))
        assertTrue(playerEnteringWorld.contains("VCP.ActionBars.ready"))
        assertTrue(playerEnteringWorld.contains("VCP.Radial.ready"))
        assertTrue(playerEnteringWorld.indexOf("VCP.ActionBars:Refresh()") < playerEnteringWorld.indexOf("VCP:ApplyBindings()"))
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
