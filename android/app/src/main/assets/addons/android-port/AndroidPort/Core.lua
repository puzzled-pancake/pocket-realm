-- Android Port (installed folder: AndroidPort): clean-room Pocket Realm core for Interface 11200.
AndroidPort = AndroidPort or {}
local AP = AndroidPort

AP.VERSION = "0.6.0"
AP.BINDING_SCHEMA = 5
AP.keys = { "1", "2", "3", "4", "5", "6", "7", "8" }

BINDING_HEADER_ANDROID_PORT = "Android Port"
for slot = 1, 40 do
    setglobal("BINDING_NAME_AP_ACTION_" .. slot, "Controller action " .. slot)
end
BINDING_NAME_AP_TOGGLE_RADIAL = "Controller radial menu"
BINDING_NAME_AP_MOVE_UI = "Move UI"
BINDING_NAME_AP_NEARBY_INTERACT = "Nearby use / open"

function AP:Print(message)
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage("|cff7fff7fAndroid Port:|r " .. message)
    end
end

local function Print(message)
    AP:Print(message)
end

function AP:GetLayout()
    local width = UIParent and UIParent:GetWidth() or 1280
    local height = UIParent and UIParent:GetHeight() or 720
    if height >= 900 then
        return {
            width = width, height = height, button = 80, padding = 86,
            star = 860, bottom = 136, radial = 520, radialButton = 58,
        }
    end
    return {
        width = width, height = height, button = 56, padding = 60,
        star = 640, bottom = 96, radial = 400, radialButton = 44,
    }
end

function AP:InitializeDatabase()
    AndroidPortDB = AndroidPortDB or {}
    AndroidPortCharacterDB = AndroidPortCharacterDB or {}
    AndroidPortDB.schema = 1
    AndroidPortDB.androidKeyboard = true
    AndroidPortDB.touchSidebars = false
    -- uiSchema 2 marks the unified frame layout journal. Legacy icon anchors
    -- keep their own key and remain readable for installations made by 0.3.x.
    AndroidPortDB.uiSchema = 2
    if type(AndroidPortDB.addonIconAnchors) ~= "table" then
        AndroidPortDB.addonIconAnchors = {}
    end
    if type(AndroidPortDB.frameAnchors) ~= "table" then
        AndroidPortDB.frameAnchors = {}
    end
    if type(AndroidPortDB.frameBackups) ~= "table" then
        AndroidPortDB.frameBackups = {}
    end
    -- 0.6.0 renamed this addon's frames from the VanillaConsolePort prefix;
    -- saved journals copied over from the old name still key by the old
    -- frame names, so re-key them once onto the live prefix.
    local journals = { AndroidPortDB.frameAnchors, AndroidPortDB.frameBackups }
    for _, journal in ipairs(journals) do
        for name, saved in pairs(journal) do
            if string.find(name, "VanillaConsolePort", 1, true) == 1 and journal["AndroidPort" .. string.sub(name, 18)] == nil then
                journal["AndroidPort" .. string.sub(name, 18)] = saved
                journal[name] = nil
            end
        end
    end
    if type(AndroidPortDB.bags) ~= "table" then
        AndroidPortDB.bags = {}
    end
    if AndroidPortDB.bags.enabled == nil then
        AndroidPortDB.bags.enabled = true
    end
    if not AndroidPortDB.bags.columns then
        AndroidPortDB.bags.columns = 8
    end
    if not AndroidPortDB.bags.mode then
        AndroidPortDB.bags.mode = "all"
    end
    local bagsModule = AndroidPort.Bags
    if bagsModule then
        bagsModule.mode = AndroidPortDB.bags.mode
    end
end

local anchorPoints = {
    TOPLEFT = true, TOP = true, TOPRIGHT = true,
    LEFT = true, CENTER = true, RIGHT = true,
    BOTTOMLEFT = true, BOTTOM = true, BOTTOMRIGHT = true,
}

local unsafeFrameNameParts = {
    "actionbutton", "bonusaction", "multibar", "playerframe", "targetframe",
    "partyframe", "partymember", "petframe", "castingbar", "buffbutton",
    "debuffbutton", "lootbutton", "containerframe", "androidport",
    "uiparent", "worldframe", "gametooltip", "pfminimappin",
}

local stockMinimapFrames = {
    minimap = true, minimapcluster = true, minimapbackdrop = true,
    minimapborder = true, minimapbordertop = true, minimapzoomin = true,
    minimapzoomout = true, minimaptogglebutton = true, minimaptracking = true,
    minimapmailframe = true, minimapworldmapbutton = true, gametimeframe = true,
    minimapbattlefieldframe = true, minimapmeetingstoneframe = true,
}

local knownAddonIcons = {
    { id = "pfquest-route-arrow", frame = "pfQuestRouteArrow", label = "pfQuest route arrow" },
    { id = "pfquest-minimap-button", frame = "pfQuestMinimapButton", label = "pfQuest minimap button" },
    { id = "pfui-minimap-button", frame = "pfUIMinimapButton", label = "pfUI minimap button" },
    { id = "atlas-minimap-button", frame = "AtlasButton", label = "Atlas minimap button" },
    { id = "atlasloot-minimap-button", frame = "AtlasLootMinimapButton", label = "AtlasLoot minimap button" },
}

AP.addonIconRegistry = AP.addonIconRegistry or {}
AP.addonIconCandidates = AP.addonIconCandidates or {}
AP.addonIconHandleCount = AP.addonIconHandleCount or 0
AP.liveFrameNames = AP.liveFrameNames or {}

local function IsValidAnchor(saved)
    if type(saved) ~= "table" then return false end
    local x, y = tonumber(saved.x), tonumber(saved.y)
    return anchorPoints[saved.point] and anchorPoints[saved.relativePoint] and
        x and y and x >= -10000 and x <= 10000 and y >= -10000 and y <= 10000
end

local function ResolveFrame(reference)
    if type(reference) == "string" then return getglobal(reference) end
    return reference
end

local function HasMoverMethods(frame)
    return frame and frame.GetPoint and frame.GetCenter and frame.GetName and
        frame.ClearAllPoints and frame.SetPoint and frame.SetMovable and
        frame.StartMoving and frame.StopMovingOrSizing
end

local function IsUnsafeFrameName(name)
    if type(name) ~= "string" or name == "" then return true end
    local lower = string.lower(name)
    if stockMinimapFrames[lower] then return true end
    for _, part in ipairs(unsafeFrameNameParts) do
        if string.find(lower, part, 1, true) then return true end
    end
    return false
end

function AP:IsSafeAddonIconFrame(frame, registered)
    if not HasMoverMethods(frame) then return false end
    local name = frame:GetName()
    if IsUnsafeFrameName(name) then return false end
    if frame.IsProtected and frame:IsProtected() then return false end
    if registered then return true end

    local parent = frame.GetParent and frame:GetParent()
    local insideMinimap = false
    local depth = 0
    while parent and depth < 6 do
        if parent == Minimap or parent == MinimapCluster then
            insideMinimap = true
            break
        end
        parent = parent.GetParent and parent:GetParent()
        depth = depth + 1
    end
    if not insideMinimap then return false end

    local lower = string.lower(name)
    local objectType = frame.GetObjectType and frame:GetObjectType()
    return objectType == "Button" or string.find(lower, "button", 1, true) or
        string.find(lower, "icon", 1, true) or string.find(lower, "broker", 1, true)
end

-- Candidates carry names, never frame userdata. Interface 11200 can free a
-- frame while a saved global still points at it, so every candidate use
-- re-resolves the name here: curated stock frames live for the whole session
-- and resolve via getglobal alone, every other candidate must additionally
-- appear in the live set built from the engine's own frame list.
function AP:ResolveCandidate(candidate)
    if not candidate or type(candidate.name) ~= "string" then return nil end
    local frame = getglobal(candidate.name)
    if not frame then return nil end
    if candidate.curated then return frame end
    if self.liveFrameNames and self.liveFrameNames[candidate.name] then
        return frame
    end
    return nil
end

function AP:RegisterAddonIcon(id, frameOrName, label)
    if type(id) ~= "string" or id == "" or not frameOrName then return false end
    self.addonIconRegistry[id] = {
        reference = frameOrName,
        label = type(label) == "string" and label or id,
    }
    if self.moveUiActive and not self.addonIconRefreshActive then self:RefreshAddonIcons() end
    return true
end

function AP:RegisterKnownAddonIcons()
    for _, icon in ipairs(knownAddonIcons) do
        self:RegisterAddonIcon(icon.id, icon.frame, icon.label)
    end
end

function AP:GetSavedAddonIconAnchor(id)
    local anchors = AndroidPortDB and AndroidPortDB.addonIconAnchors
    local saved = type(anchors) == "table" and anchors[id]
    if not saved and id == "pfquest-route-arrow" and type(pfQuest_config) == "table" then
        saved = pfQuest_config["pocketrealm_arrow_position"]
    end
    if IsValidAnchor(saved) then return saved end
end

function AP:RestoreAddonIcon(candidate)
    local saved = self:GetSavedAddonIconAnchor(candidate.id)
    if not saved or not self:IsSafeAddonIconFrame(candidate.frame, candidate.registered) then return false end
    candidate.frame:ClearAllPoints()
    candidate.frame:SetPoint(saved.point, UIParent, saved.relativePoint, tonumber(saved.x), tonumber(saved.y))
    if saved.scale and candidate.frame.SetScale then candidate.frame:SetScale(saved.scale) end
    return true
end

-- One movable-target predicate: curated and swept window candidates are
-- vetted by name resolution and frame capability; icon candidates keep the
-- conservative icon filter.
function AP:CanMoveCandidate(candidate)
    local frame = self:ResolveCandidate(candidate)
    if not frame then return false end
    if candidate.curated or candidate.window then
        return HasMoverMethods(frame) and
            (not frame.IsProtected or not frame:IsProtected())
    end
    return self:IsSafeAddonIconFrame(frame, candidate and candidate.registered)
end

function AP:SaveAddonIcon(candidate)
    local frame = self:ResolveCandidate(candidate)
    if not frame or not self:CanMoveCandidate(candidate) then return false end
    if candidate.scaleOnly then
        if self.FrameMover then return self.FrameMover:SaveScaleOnly(frame) end
        return false
    end
    local x, y = frame:GetCenter()
    x, y = tonumber(x), tonumber(y)
    local saved = { point = "CENTER", relativePoint = "BOTTOMLEFT", x = x, y = y }
    if frame.GetScale then saved.scale = frame:GetScale() end
    if not IsValidAnchor(saved) then return false end
    if candidate.icon and AndroidPortDB.addonIconAnchors then
        AndroidPortDB.addonIconAnchors[candidate.id] = saved
    end
    if candidate.id == "pfquest-route-arrow" and type(pfQuest_config) == "table" then
        pfQuest_config["pocketrealm_arrow_position"] = saved
    end
    if self.FrameMover then self.FrameMover:SaveFrame(frame, candidate.label) end
    return true
end

function AP:AddAddonIconCandidate(id, frame, label, registered, seen)
    if seen[frame] or not self:IsSafeAddonIconFrame(frame, registered) then return end
    seen[frame] = true
    local candidate = self.addonIconCandidates[id]
    if not candidate or candidate.frame ~= frame then
        if candidate and candidate.handle then candidate.handle:Hide() end
        candidate = {
            id = id,
            name = frame.GetName and frame:GetName() or nil,
            frame = frame,
            label = label or id,
            registered = registered,
            icon = true,
        }
        self.addonIconCandidates[id] = candidate
        self:RestoreAddonIcon(candidate)
    end
    if self.moveUiActive then self:ShowAddonIconHandle(candidate) end
end

-- Sweep the engine's own frame list into a live-name set. A non-nil
-- getglobal cannot prove the global still points at a live object in
-- Interface 11200, so third-party candidates are only actionable while
-- their name is a member of this set.
function AP:BuildLiveFrameSet()
    local live = {}
    if type(EnumerateFrames) ~= "function" then
        self.liveFrameNames = live
        return live
    end
    local frame = EnumerateFrames()
    local guard = 0
    while frame and guard < 6000 do
        guard = guard + 1
        if frame.GetName then
            local name = frame:GetName()
            if name and name ~= "" and getglobal(name) == frame then
                live[name] = true
            end
        end
        frame = EnumerateFrames(frame)
    end
    self.liveFrameNames = live
    return live
end

-- Discover visible top-level windows by name. Anchors and drag never touch
-- candidate userdata across ticks, so a freed third-party frame can never be
-- dereferenced: it simply stops resolving and its handle is detached.
function AP:DiscoverTopLevelFrames(seen)
    if type(EnumerateFrames) ~= "function" then return end
    local frame = EnumerateFrames()
    local guard = 0
    while frame and guard < 6000 do
        guard = guard + 1
        local name = frame.GetName and frame:GetName() or nil
        if name and name ~= "" and not seen[name] and
            self.liveFrameNames[name] and getglobal(name) == frame and
            not IsUnsafeFrameName(name) and HasMoverMethods(frame) then
            local parent = frame.GetParent and frame:GetParent() or nil
            if (parent == UIParent or parent == nil) and
                frame.IsShown and frame:IsShown() and
                (not frame.IsProtected or not frame:IsProtected()) then
                local width = frame.GetWidth and frame:GetWidth() or 0
                local height = frame.GetHeight and frame:GetHeight() or 0
                if width >= 48 and height >= 24 then
                    seen[name] = true
                    self:AddWindowCandidate(name)
                end
            end
        end
        frame = EnumerateFrames(frame)
    end
end

function AP:AddWindowCandidate(name)
    local key = "window:" .. name
    local candidate = self.addonIconCandidates[key]
    if not candidate then
        candidate = {
            id = key,
            name = name,
            label = name,
            window = true,
        }
        self.addonIconCandidates[key] = candidate
    end
end

function AP:RefreshAddonIcons()
    if self.addonIconRefreshActive then return false end
    self.addonIconRefreshActive = true
    local ok = pcall(function()
        self:RegisterKnownAddonIcons()
        self:BuildLiveFrameSet()
        local seen = {}
        for id, record in pairs(self.addonIconRegistry) do
            local frame = ResolveFrame(record.reference)
            if frame then self:AddAddonIconCandidate(id, frame, record.label, true, seen) end
        end
        self:DiscoverTopLevelFrames(seen)
    end)
    self.addonIconRefreshActive = nil
    return ok
end

function AP:GetMoveInstructions()
    local frame = getglobal("AndroidPortMoveInstructions")
    if not frame then frame = CreateFrame("Frame", "AndroidPortMoveInstructions", UIParent) end
    frame:SetWidth(700)
    frame:SetHeight(54)
    frame:ClearAllPoints()
    frame:SetPoint("TOP", UIParent, "TOP", 0, -54)
    frame:SetFrameStrata("TOOLTIP")
    local text = frame.text
    if not text then
        text = frame:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
        frame.text = text
    end
    text:ClearAllPoints()
    text:SetPoint("LEFT", frame, "LEFT", 12, 0)
    text:SetText("Move UI: drag a handle to move it; D-pad Down/Up selects, Left/Right resizes\nOpen any window to make it movable; Select + Start or Escape saves and exits")
    local exit = frame.exitButton
    if not exit then
        exit = CreateFrame("Button", "AndroidPortMoveSaveExit", frame, "UIPanelButtonTemplate")
        frame.exitButton = exit
    end
    exit:SetWidth(120)
    exit:SetHeight(30)
    exit:ClearAllPoints()
    exit:SetPoint("RIGHT", frame, "RIGHT", -12, 0)
    exit:SetText("Save & Exit")
    exit:SetScript("OnClick", function() AP:FinishMoveUI(false) end)
    frame:SetScript("OnHide", function()
        if AP.moveUiActive then AP:FinishMoveUI(false) end
    end)
    local listed = false
    for _, name in ipairs(UISpecialFrames) do
        if name == "AndroidPortMoveInstructions" then listed = true end
    end
    if not listed then tinsert(UISpecialFrames, "AndroidPortMoveInstructions") end
    return frame
end

-- Targets are never dragged directly: the handle is an addon-owned child of
-- UIParent, so its userdata can never go stale. On mouse-down the target's
-- screen rectangle is sampled; on mouse-up a single absolute SetPoint moves
-- the freshly re-resolved target. SetPoint stores the resolved object, so a
-- handle never keeps an anchor into a frame the poll has not confirmed.
function AP:ShowAddonIconHandle(candidate)
    if candidate.moving then return true end
    local frame = self:ResolveCandidate(candidate)
    if not frame or not frame.IsShown or not frame:IsShown() then return false end
    local handle = candidate.handle
    if not handle then
        self.addonIconHandleCount = self.addonIconHandleCount + 1
        local name = "AndroidPortMoverHandle" .. self.addonIconHandleCount
        handle = CreateFrame("Button", name, UIParent)
        handle:SetFrameStrata("TOOLTIP")
        local shade = handle:CreateTexture(nil, "BACKGROUND")
        shade:SetAllPoints(handle)
        shade:SetTexture(0.1, 0.8, 0.2)
        shade:SetAlpha(0.35)
        handle.shade = shade
        local label = handle:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        label:SetPoint("BOTTOM", handle, "TOP", 0, 2)
        label:SetText(candidate.label)
        handle:EnableMouse(true)
        -- StartMoving raises an engine error on any frame that was not
        -- flagged movable first; the flag lives on the handle only.
        handle:SetMovable(1)
        handle:EnableMouseWheel(1)
        handle:RegisterForDrag("LeftButton", "RightButton")
        handle:SetScript("OnMouseWheel", function()
            if not AP.moveUiActive then return end
            AP:ScaleMoveCandidate(candidate, arg1)
        end)
        -- Drag delivery is the stock chat-tab pattern: OnDragStop arrives even
        -- when the button is released off the handle, so a touch drop outside
        -- the handle still completes instead of stranding the drag.
        handle:SetScript("OnDragStart", function()
            if not AP.moveUiActive then return end
            if candidate.scaleOnly then return end
            local target = AP:ResolveCandidate(candidate)
            if not target then return end
            candidate.grabLeft = handle:GetLeft()
            candidate.grabTop = handle:GetTop()
            candidate.grabTargetLeft = target.GetLeft and target:GetLeft() or nil
            candidate.grabTargetTop = target.GetTop and target:GetTop() or nil
            if not candidate.grabTargetLeft or not candidate.grabTargetTop then return end
            handle:ClearAllPoints()
            handle:StartMoving()
            candidate.moving = true
        end)
        handle:SetScript("OnDragStop", function()
            if not candidate.moving then return end
            handle:StopMovingOrSizing()
            candidate.moving = nil
            AP:ApplyHandleDrop(candidate, handle)
        end)
        candidate.handle = handle
    end
    handle:ClearAllPoints()
    handle:SetPoint("TOPLEFT", frame, "TOPLEFT", -5, 5)
    handle:SetPoint("BOTTOMRIGHT", frame, "BOTTOMRIGHT", 5, -5)
    handle:Show()
    return true
end

function AP:FinishMoveUI(silent)
    if not self.moveUiActive then return false end
    self.moveUiActive = nil
    self.moveFocused = nil
    for _, candidate in pairs(self.addonIconCandidates) do
        local handle = candidate.handle
        if candidate.moving and handle then
            -- Exiting mid-drag still completes the in-flight move.
            handle:StopMovingOrSizing()
            candidate.moving = nil
            self:ApplyHandleDrop(candidate, handle)
        end
        candidate.moving = nil
        -- Only candidates actually dragged or scaled this session are
        -- journaled; saving untouched stock panels would de-register them
        -- from the stock panel manager without the player asking. A pure
        -- scale change journals scale only so the panel stays managed.
        if candidate.moved then
            self:SaveAddonIcon(candidate)
        elseif candidate.scaled then
            local frame = self:ResolveCandidate(candidate)
            if frame and self.FrameMover then self.FrameMover:SaveScaleOnly(frame) end
        end
        if handle then
            -- Detach before hiding: a shown mouse-enabled handle anchored
            -- into freed memory can fault the client without any Lua call.
            handle:ClearAllPoints()
            handle:Hide()
        end
    end
    if self.moveUiInstructions then self.moveUiInstructions:Hide() end
    self.moveUiInstructions = nil
    if not silent then Print("layout saved; move mode closed") end
    return true
end

-- Shared drop/scale clamp. The target may sit flush against any screen edge
-- and may hang partly off one, exactly as the horizontal pair has always
-- allowed; at least 40 units stay reachable so a frame can always be
-- re-grabbed. Inputs and outputs are in UIParent screen space (screenTop
-- measured up from the floor).
function AP:ClampFrameRect(target, screenLeft, screenTop)
    local parentWidth = UIParent:GetWidth()
    local parentTop = UIParent:GetTop()
    local scale = target.GetScale and target:GetScale() or 1
    if type(scale) ~= "number" or scale <= 0 then scale = 1 end
    local width = (target.GetWidth and target:GetWidth() or 0) * scale
    local height = (target.GetHeight and target:GetHeight() or 0) * scale
    if screenLeft < 40 - width then screenLeft = 40 - width end
    if screenLeft > parentWidth - 40 then screenLeft = parentWidth - 40 end
    if screenTop < 40 then screenTop = 40 end
    if screenTop > parentTop + height - 40 then screenTop = parentTop + height - 40 end
    return screenLeft, screenTop
end

-- Move the target by the exact distance the handle was dragged, then journal
-- the new absolute anchor through the frame mover.
function AP:ApplyHandleDrop(candidate, handle)
    local target = self:ResolveCandidate(candidate)
    if not target or not target.SetPoint then return false end
    local left = handle.GetLeft and handle:GetLeft() or nil
    local top = handle.GetTop and handle:GetTop() or nil
    if not left or not top or not candidate.grabLeft or not candidate.grabTop or
        not candidate.grabTargetLeft or not candidate.grabTargetTop then
        return false
    end
    -- GetTop measures from the screen floor and GetLeft/GetTop readouts are
    -- divided by the target's own scale, while SetPoint offsets render
    -- multiplied by it: convert the drag delta and the top-origin term into
    -- the target's coordinate space so scaled targets land under the finger.
    local scale = target.GetScale and target:GetScale() or 1
    if type(scale) ~= "number" or scale <= 0 then scale = 1 end
    local x = candidate.grabTargetLeft + (left - candidate.grabLeft) / scale
    local y = candidate.grabTargetTop + (top - candidate.grabTop - UIParent:GetTop()) / scale
    local parentTop = UIParent:GetTop()
    local screenLeft = x * scale
    local screenTop = parentTop + y * scale
    screenLeft, screenTop = self:ClampFrameRect(target, screenLeft, screenTop)
    target:ClearAllPoints()
    target:SetPoint("TOPLEFT", "UIParent", "TOPLEFT", screenLeft / scale, (screenTop - parentTop) / scale)
    candidate.moved = true
    return self:SaveAddonIcon(candidate)
end

-- Revalidate every candidate against a fresh live-set sweep: rebind live
-- handles, detach handles whose target vanished, and retire swept windows
-- that stay dead for several polls so transient frames do not accumulate.
function AP:RefreshMoveHandles()
    for key, candidate in pairs(self.addonIconCandidates) do
        local handle = candidate.handle
        local frame = self:ResolveCandidate(candidate)
        local shown = frame and frame.IsShown and frame:IsShown()
        if shown then
            candidate.missed = nil
            self:ShowAddonIconHandle(candidate)
        else
            if handle then
                -- A mid-drag vanish must also end the engine move state, or
                -- the hidden handle keeps fighting its next rebind.
                if candidate.moving then handle:StopMovingOrSizing() end
                handle:ClearAllPoints()
                handle:Hide()
            end
            candidate.moving = nil
            if candidate.window then
                candidate.missed = (candidate.missed or 0) + 1
                if candidate.missed >= 4 then
                    self.addonIconCandidates[key] = nil
                end
            end
        end
    end
end

function AP:PulseMoveUI()
    if not self.moveUiActive then return end
    self:RefreshAddonIcons()
    self:RefreshMoveHandles()
end

local MOVE_SCALE_MIN = 0.5
local MOVE_SCALE_MAX = 1.5
local MOVE_SCALE_STEP = 0.1

-- Visible candidates in label order, so D-pad focus cycling is deterministic.
function AP:MoveFocusCandidates()
    local ordered = {}
    for _, entry in pairs(self.addonIconCandidates) do
        if entry.handle and entry.handle:IsVisible() then tinsert(ordered, entry) end
    end
    table.sort(ordered, function(a, b) return (a.label or "") < (b.label or "") end)
    return ordered
end

function AP:SetMoveFocus(candidate)
    self.moveFocused = candidate
    for _, entry in pairs(self.addonIconCandidates) do
        local handle = entry.handle
        if handle and handle.shade then
            if entry == candidate then
                handle.shade:SetTexture(0.2, 0.5, 1.0)
            else
                handle.shade:SetTexture(0.1, 0.8, 0.2)
            end
        end
    end
end

function AP:ScaleMoveCandidate(candidate, direction)
    local frame = self:ResolveCandidate(candidate)
    if not frame or not frame.GetScale or not frame.SetScale then return false end
    if type(direction) ~= "number" or direction == 0 then return false end
    -- Scaling alone pivots on the anchored corner and drifts the visible
    -- rect, which reads as the D-pad moving the frame. Sample the centre
    -- first, then re-anchor so resizing keeps it fixed.
    local oldScale = frame:GetScale()
    if type(oldScale) ~= "number" or oldScale <= 0 then oldScale = 1 end
    local cx, cy = frame.GetCenter and frame:GetCenter() or nil
    local scale = oldScale + direction * MOVE_SCALE_STEP
    if scale < MOVE_SCALE_MIN then scale = MOVE_SCALE_MIN end
    if scale > MOVE_SCALE_MAX then scale = MOVE_SCALE_MAX end
    frame:SetScale(scale)
    candidate.scaled = true
    if candidate.scaleOnly or not cx or not cy then
        -- Scale alone must not de-register a stock panel; FinishMoveUI routes
        -- this flag to the scale-only journal. Stock-position frames keep the
        -- anchored-corner behaviour because the bag sweep owns their anchor.
        return true
    end
    local width = (frame.GetWidth and frame:GetWidth() or 0) * scale
    local height = (frame.GetHeight and frame:GetHeight() or 0) * scale
    local screenLeft = cx * oldScale - width / 2
    local screenTop = cy * oldScale + height / 2
    screenLeft, screenTop = self:ClampFrameRect(frame, screenLeft, screenTop)
    local parentTop = UIParent:GetTop()
    frame:ClearAllPoints()
    frame:SetPoint("TOPLEFT", "UIParent", "TOPLEFT", screenLeft / scale, (screenTop - parentTop) / scale)
    candidate.moved = true
    -- A scale that lands mid-drag shifts the sampled drag origin; re-sample
    -- so the in-flight drop still lands under the finger.
    if candidate.moving then
        candidate.grabTargetLeft = frame.GetLeft and frame:GetLeft() or candidate.grabTargetLeft
        candidate.grabTargetTop = frame.GetTop and frame:GetTop() or candidate.grabTargetTop
    end
    return true
end

-- While Move UI is open the action buttons stop firing abilities: D-pad
-- Down/Up selects the next target and D-pad Left/Right resizes it. Dragging
-- a handle is the only way a frame ever moves.
function AP:MoveUIAdjust(slot)
    local candidates = self:MoveFocusCandidates()
    local count = table.getn(candidates)
    if count == 0 then return false end
    if slot == 5 or slot == 7 then
        local current = 0
        for index, entry in ipairs(candidates) do
            if entry == self.moveFocused then current = index end
        end
        local nextIndex = current + (slot == 5 and 1 or -1)
        if nextIndex > count then nextIndex = 1 end
        if nextIndex < 1 then nextIndex = count end
        self:SetMoveFocus(candidates[nextIndex])
        return true
    elseif slot == 6 then
        return self:ScaleMoveCandidate(self.moveFocused, -1)
    elseif slot == 8 then
        return self:ScaleMoveCandidate(self.moveFocused, 1)
    end
    return false
end

function AP:ToggleMoveUI()
    if self:FinishMoveUI(false) then return end
    self:RefreshAddonIcons()
    self.moveUiActive = true
    for _, candidate in pairs(self.addonIconCandidates) do
        candidate.moved = nil
        candidate.scaled = nil
    end
    local visible = 0
    for _, candidate in pairs(self.addonIconCandidates) do
        if self:ShowAddonIconHandle(candidate) then visible = visible + 1 end
    end
    if visible == 0 then
        self.moveUiActive = nil
        Print("no movable windows are currently visible; open a window and try again")
        return
    end
    self.moveUiInstructions = self:GetMoveInstructions()
    self.moveUiInstructions:Show()
    Print("drag a handle to move it; D-pad Down/Up selects, Left/Right resizes; Select + Start saves and exits")
end

-- Slow poll while move mode is open: refreshes the live set, picks up newly
-- shown windows, and detaches handles whose target has vanished.
local movePoll = getglobal("AndroidPortMovePoll")
if not movePoll then
    movePoll = CreateFrame("Frame", "AndroidPortMovePoll", UIParent)
end
movePoll:SetScript("OnUpdate", function()
    if not AP.moveUiActive then
        this.elapsed = nil
        return
    end
    this.elapsed = (this.elapsed or 0) + arg1
    if this.elapsed < 0.25 then return end
    this.elapsed = 0
    AP:PulseMoveUI()
end)

function AP:GetBindingJournal()
    if GetCurrentBindingSet() == 2 then return AndroidPortCharacterDB end
    return AndroidPortDB
end

function AP:BindingCommand(slot)
    return "AP_ACTION_" .. slot
end

function AndroidPort_NearbyInteract()
    if not SendChatMessage then return end
    -- Vanilla 1.12 blocks solo PARTY addon traffic client-side, so the
    -- trigger rides a self-whisper. The Pocket Realm server consumes this
    -- exact authenticated-session request before normal whisper delivery,
    -- so nothing appears in chat and it works solo or grouped.
    SendChatMessage("PR6I:1 INTERACT", "WHISPER", nil, UnitName("player"))
end

function AP:ApplyBindings()
    local db = self:GetBindingJournal()
    if db.bindingSchema == self.BINDING_SCHEMA then return end
    db.bindingBackup = db.bindingBackup or {}

    -- Schema 1 used 9/0 for RB/LB. Target and use now live directly on those
    -- shoulders, so release only the exact old bindings before assigning the
    -- eight reachable face/D-pad actions on each modifier page.
    if db.bindingSchema == 1 then
        local retired = { "9", "0" }
        local oldModifiers = { "", "SHIFT-", "CTRL-", "CTRL-SHIFT-" }
        for page, modifier in ipairs(oldModifiers) do
            for offset, key in ipairs(retired) do
                local chord = modifier .. key
                local oldSlot = (page - 1) * 10 + 8 + offset
                if GetBindingAction(chord) == self:BindingCommand(oldSlot) then
                    local prior = db.bindingBackup[chord]
                    if prior and prior ~= "" then SetBinding(chord, prior) else SetBinding(chord) end
                end
            end
        end
    end

    local slot = 1
    local modifiers = { "", "SHIFT-", "CTRL-", "CTRL-SHIFT-" }
    for page, modifier in ipairs(modifiers) do
        for _, key in ipairs(self.keys) do
            local chord = modifier .. key
            local owned = self:BindingCommand(slot)
            local current = GetBindingAction(chord)
            local oldIndex = tonumber(key) + (page - 1) * 10
            local oldOwned = self:BindingCommand(oldIndex)
            local mayClaim = db.bindingSchema == nil or current == owned or
                (db.bindingSchema == 1 and current == oldOwned)
            if db.bindingBackup[chord] == nil then
                db.bindingBackup[chord] = GetBindingAction(chord) or ""
                mayClaim = true
            end
            -- On a later schema migration, keep any binding the player has
            -- changed since installation. Only untouched prior ownership is
            -- eligible for remapping.
            if mayClaim then SetBinding(chord, owned) end
            slot = slot + 1
        end
    end
    local mayClaimF12 = db.bindingSchema == nil or GetBindingAction("F12") == "" or
        GetBindingAction("F12") == "AP_TOGGLE_RADIAL"
    if db.bindingBackup["F12"] == nil then
        db.bindingBackup["F12"] = GetBindingAction("F12") or ""
        mayClaimF12 = true
    end
    if mayClaimF12 then
        SetBinding("F12", "AP_TOGGLE_RADIAL")
    end
    local mayClaimF8 = db.bindingSchema == nil or GetBindingAction("F8") == "" or
        GetBindingAction("F8") == "AP_MOVE_UI"
    if db.bindingBackup["F8"] == nil then
        db.bindingBackup["F8"] = GetBindingAction("F8") or ""
    end
    if mayClaimF8 then
        SetBinding("F8", "AP_MOVE_UI")
    end
    local mayClaimF9 = db.bindingSchema == nil or GetBindingAction("F9") == "" or
        GetBindingAction("F9") == "TOGGLEAUTORUN"
    if db.bindingBackup["F9"] == nil then
        db.bindingBackup["F9"] = GetBindingAction("F9") or ""
    end
    if mayClaimF9 then
        SetBinding("F9", "TOGGLEAUTORUN")
    end
    local mayClaimF7 = db.bindingSchema == nil or GetBindingAction("F7") == "" or
        GetBindingAction("F7") == "AP_NEARBY_INTERACT"
    if db.bindingBackup["F7"] == nil then
        db.bindingBackup["F7"] = GetBindingAction("F7") or ""
    end
    if mayClaimF7 then
        SetBinding("F7", "AP_NEARBY_INTERACT")
    end
    SaveBindings(GetCurrentBindingSet())
    db.bindingSchema = self.BINDING_SCHEMA
end

function AP:RestoreBindings()
    local db = self:GetBindingJournal()
    if not db or not db.bindingBackup then return end
    for key, command in pairs(db.bindingBackup) do
        local owned
        if key == "F12" then
            owned = "AP_TOGGLE_RADIAL"
        elseif key == "F8" then
            owned = "AP_MOVE_UI"
        elseif key == "F9" then
            owned = "TOGGLEAUTORUN"
        elseif key == "F7" then
            owned = "AP_NEARBY_INTERACT"
        else
            local _, _, modifier, number = string.find(key, "^(.-)([0-9])$")
            local index = tonumber(number)
            if index and index >= 1 and index <= 8 then
                if string.find(modifier or "", "SHIFT", 1, true) then index = index + 8 end
                if string.find(modifier or "", "CTRL", 1, true) then index = index + 16 end
                owned = self:BindingCommand(index)
            end
        end
        -- Restore only bindings that are still exactly ours. A player edit
        -- made after installation always wins.
        if owned and GetBindingAction(key) == owned then
            if command == "" then SetBinding(key) else SetBinding(key, command) end
        end
    end
    SaveBindings(GetCurrentBindingSet())
    db.bindingBackup = nil
    db.bindingSchema = nil
    Print("prior keyboard bindings restored")
end

function AndroidPort_Action(slot, state)
    if state ~= "down" then return end
    if AP.moveUiActive then
        if slot >= 5 and slot <= 8 then AP:MoveUIAdjust(slot) end
        return
    end
    if slot >= 1 and slot <= 8 and AP.Radial and AP.Radial:Activate(slot) then return end
    if AP.ActionBars then AP.ActionBars:UseSlot(slot) end
end

function AndroidPort_ToggleRadial()
    if AP.Radial then AP.Radial:Toggle() end
end

function AndroidPort_ToggleMoveUI()
    AP:ToggleMoveUI()
end

SLASH_ANDROIDPORT1 = "/ap"
SlashCmdList["ANDROIDPORT"] = function(message)
    message = string.lower(message or "")
    if message == "restore" then
        AP:RestoreBindings()
    elseif message == "resetui" then
        if AP.FrameMover then AP.FrameMover:ResetUI() end
        -- resetui reverts the chat treatment too, matching the documented
        -- "back to exactly stock" contract.
        if AP.Hud and type(AndroidPortDB) == "table" then
            AndroidPortDB.chatMinimal = false
            AP.Hud:RestoreChatFrame()
        end
    elseif message == "chat" then
        if AP.Hud then AP.Hud:ToggleChat() end
    elseif message == "radial" then
        AndroidPort_ToggleRadial()
    elseif message == "bags" then
        if AP.Bags then
            AP.Bags:SetEnabled(not (AndroidPortDB and AndroidPortDB.bags and AndroidPortDB.bags.enabled))
        end
    else
        Print("/ap radial opens the menu; /ap chat toggles the minimal chat; /ap bags toggles the all-in-one bag window; /ap restore restores pre-install key bindings; /ap resetui restores the stock frame layout")
    end
end

local events = CreateFrame("Frame", "AndroidPortEvents", UIParent)
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("PLAYER_LOGOUT")
function AP:InitializeModules()
    self:InitializeDatabase()
    self:RefreshAddonIcons()
    if self.FrameMover then self.FrameMover:Initialize() end
    if self.Hud then self.Hud:Initialize() end
    -- Bags is deliberately non-fatal: a failure here must never trip the
    -- bars or radial fail-safe below nor block ApplyBindings.
    if self.Bags then self.Bags:Initialize() end
    local barsReady = self.ActionBars and self.ActionBars:Initialize()
    local radialReady = self.Radial and self.Radial:Initialize()
    if barsReady and radialReady then return true end
    if self.ActionBars then self.ActionBars:FailSafe(self.ActionBars.buttons) end
    if self.Radial then self.Radial:FailSafe(self.Radial.frame, self.Radial.overlay, self.Radial.buttons) end
    return false
end

events:SetScript("OnEvent", function()
    if event == "ADDON_LOADED" and arg1 == "AndroidPort" then
        AP:InitializeModules()
    elseif event == "ADDON_LOADED" then
        AP:RefreshAddonIcons()
    elseif event == "PLAYER_ENTERING_WORLD" then
        if AP:InitializeModules()
            and AP.ActionBars.ready
            and AP.Radial.ready
            and AP.ActionBars:Refresh() then
            AP:ApplyBindings()
        end
    elseif event == "PLAYER_LOGOUT" then
        AP:FinishMoveUI(true)
    end
end)
