-- Minimal WoW client mock for the Android Port addon, parameterized by flavor.
-- FLAVOR is set by the driver: "vanilla" | "tbc" | "wotlk".
--
-- Only what the addon touches is modelled, but the parts that decide whether
-- a 2.4.3/3.3.5a client blocks or misfires are taken from those clients' own
-- FrameXML: the secure click dispatch (SecureActionButton_OnClick and
-- ActionButton_CalculateAction, including the id > 0 page arithmetic), the
-- documented modifier-attribute lookup order, combat lockdown on protected
-- frames (explicit, plus implicit through protected children or anchored
-- dependants), protected-API calls from add-on code, and the per-client
-- presence of functions such as ToggleQuestLog, hooksecurefunc and the
-- 3.3.5a secure state handler. Blocked actions are recorded, not raised, the
-- way the client reports them.
local M = {}
_G.MOCK = M
M.errors = {}
M.blocked = {}
M.casts = {}
M.log = {}
M.unknownMethods = {}
M.combat = false
M.shift = false
M.ctrl = false
M.bonus = 0
M.time = 100
M.chat = {}
M.commandsRun = {}

local SECURE = FLAVOR ~= "vanilla"
M.secure = SECURE

if FLAVOR == "vanilla" then
    math.fmod = nil
else
    -- The 5.1 clients: be strict, pretend the compat alias is gone.
    math.mod = nil
end

function getglobal(name) return _G[name] end
function setglobal(name, value) _G[name] = value end
tinsert = table.insert
tremove = table.remove
strmatch = string.match
function GetTime() return M.time end

local toc = { vanilla = nil, tbc = 20400, wotlk = 30300 }
function GetBuildInfo()
    if FLAVOR == "vanilla" then return "1.12.1", "5875", "Sep 19 2006" end
    return (FLAVOR == "tbc" and "2.4.3" or "3.3.5"), "1", "x", toc[FLAVOR]
end

if SECURE then
    function InCombatLockdown() return M.combat end
end

local function block(what, frame)
    table.insert(M.blocked, what .. " on " .. tostring(frame and frame.__name))
end

-- ---------------------------------------------------------------- frames
local Frame = {}
Frame.__index = function(self, key)
    local v = rawget(Frame, key)
    if v ~= nil then return v end
    if type(key) == "string" and string.find(key, "^[A-Z]") then
        M.unknownMethods[key] = true
        return function() return nil end
    end
    return nil
end

local allFrames = {}
local function isProtected(f)
    if not SECURE then return false end
    if f.__protected then return true end
    -- implicit: a frame with a protected child or anchored dependant
    for _, c in ipairs(f.__children) do if isProtected(c) then return true end end
    for d in pairs(f.__dependants) do if isProtected(d) then return true end end
    return false
end
M.isProtected = isProtected

local function guard(f, what)
    if SECURE and M.combat and isProtected(f) then block(what, f); return false end
    return true
end

function CreateFrame(ftype, name, parent, template)
    local f = setmetatable({
        __type = ftype, __name = name, __parent = parent, __children = {}, __dependants = {},
        __attrs = {}, __scripts = {}, __shown = true, __points = {}, __w = 0, __h = 0,
        __scale = 1, __alpha = 1, __id = 0,
    }, Frame)
    if template and string.find(template, "Secure") then
        if not SECURE then error("secure template on vanilla: " .. template) end
        f.__protected = true
        f.__template = template
    end
    if template == "SecureHandlerStateTemplate" and FLAVOR ~= "wotlk" then
        error("SecureHandlerStateTemplate unknown on " .. FLAVOR)
    end
    if template == "ContainerFrameItemButtonTemplate" or template == "ItemButtonTemplate" then
        for _, suffix in ipairs({ "IconTexture", "NormalTexture", "Cooldown", "Count" }) do
            _G[name .. suffix] = CreateFrame("Frame")
        end
    end
    if template == "UIPanelButtonTemplate" then f.__protected = false end
    if parent then table.insert(parent.__children, f) end
    if name then _G[name] = f end
    table.insert(allFrames, f)
    return f
end

function Frame:GetName() return self.__name end
function Frame:GetObjectType() return self.__type end
function Frame:IsProtected() return isProtected(self), self.__protected end
function Frame:GetParent() return self.__parent end
function Frame:SetParent(p)
    if not guard(self, "SetParent") then return end
    self.__parent = p
    table.insert(p.__children, self)
end
function Frame:Show() if not guard(self, "Show") then return end
    local was = self:IsVisible(); self.__shown = true
    if not was and self:IsVisible() and self.__scripts.OnShow then M.run(self, "OnShow") end end
function Frame:Hide() if not guard(self, "Hide") then return end
    local was = self.__shown; self.__shown = false
    if was and self.__scripts.OnHide then M.run(self, "OnHide") end end
function Frame:IsShown() return self.__shown end
function Frame:IsVisible()
    local f = self
    while f do if not f.__shown then return false end f = f.__parent end
    return true
end
function Frame:SetPoint(point, rel, relPoint, x, y)
    if not guard(self, "SetPoint") then return end
    if type(rel) == "string" then rel = _G[rel] end
    if type(rel) == "table" and rel.__dependants then rel.__dependants[self] = true end
    table.insert(self.__points, { point, rel, relPoint, x or 0, y or 0 })
end
function Frame:ClearAllPoints() if not guard(self, "ClearAllPoints") then return end self.__points = {} end
function Frame:SetAllPoints(rel) self:SetPoint("TOPLEFT", rel); self:SetPoint("BOTTOMRIGHT", rel) end
function Frame:GetNumPoints() return table.getn(self.__points) end
function Frame:GetPoint(i) local p = self.__points[i or 1]; if p then return p[1], p[2], p[3], p[4], p[5] end end
function Frame:SetWidth(w) if not guard(self, "SetWidth") then return end self.__w = w end
function Frame:SetHeight(h) if not guard(self, "SetHeight") then return end self.__h = h end
function Frame:GetWidth() return self.__w end
function Frame:GetHeight() return self.__h end
function Frame:SetScale(s) if not guard(self, "SetScale") then return end self.__scale = s end
function Frame:GetScale() return self.__scale end
function Frame:GetEffectiveScale() return self.__scale end
function Frame:SetAlpha(a) self.__alpha = a end
function Frame:GetAlpha() return self.__alpha end
function Frame:GetCenter() return 640, 360 end
function Frame:GetLeft() return 600 end
function Frame:GetTop() return 400 end
function Frame:SetID(id) self.__id = id end
function Frame:GetID() return self.__id end
function Frame:SetAttribute(k, v)
    if SECURE and M.combat and isProtected(self) and not M.secureContext then block("SetAttribute(" .. k .. ")", self); return end
    self.__attrs[k] = v
    if self.__onAttr then self.__onAttr(self, k, v) end
end
function Frame:GetAttribute(a, b, c)
    if b == nil then return self.__attrs[a] end
    local prefix, name, suffix = a, b, c
    -- Blizzard's documented lookup: a bare attribute counts as *name*.
    local order = { prefix .. name .. suffix, "*" .. name .. suffix, prefix .. name .. "*", "*" .. name .. "*", name }
    for _, key in ipairs(order) do
        if self.__attrs[key] ~= nil then return self.__attrs[key] end
    end
    return nil
end
function Frame:SetScript(name, fn)
    if SECURE and M.combat and isProtected(self) then block("SetScript(" .. name .. ")", self); return end
    self.__scripts[name] = fn
end
function Frame:GetScript(name) return self.__scripts[name] end
function Frame:HookScript(name, fn)
    local old = self.__scripts[name]
    self.__scripts[name] = function() if old then old() end fn() end
end
function Frame:RegisterEvent(e)
    if FLAVOR == "wotlk" and e == "PLAYER_AURAS_CHANGED" then error("unknown event " .. e) end
    self.__events = self.__events or {}; self.__events[e] = true
end
function Frame:UnregisterEvent(e) if self.__events then self.__events[e] = nil end end
function Frame:CreateTexture() return setmetatable({ __name = "tex", __shown = true, __children = {}, __dependants = {}, __attrs = {}, __scripts = {}, __points = {} }, Frame) end
function Frame:CreateFontString(name)
    local fs = setmetatable({ __name = name, __shown = true, __children = {}, __dependants = {}, __attrs = {}, __scripts = {}, __points = {} }, Frame)
    if name then _G[name] = fs end
    return fs
end
function Frame:SetText(t) self.__text = t end
function Frame:GetText() return self.__text end
function Frame:SetTexture(a) self.__texture = a end
function Frame:SetVertexColor() end
function Frame:SetTextColor() end
function Frame:EnableMouse() end
function Frame:EnableMouseWheel() end
function Frame:SetMovable() end
function Frame:SetUserPlaced() end
function Frame:IsUserPlaced() return false end
function Frame:SetFrameStrata() end
function Frame:SetFrameLevel() end
function Frame:RegisterForClicks() end
function Frame:RegisterForDrag() end
function Frame:SetHitRectInsets() end
function Frame:StartMoving() end
function Frame:StopMovingOrSizing() end
function Frame:SetStatusBarTexture() end
function Frame:SetStatusBarColor() end
function Frame:SetMinMaxValues() end
function Frame:SetValue() end
function Frame:SetFocus() end
function Frame:GetButtonState() return "NORMAL" end
function Frame:SetButtonState() end

-- Script runner with global this/event/argN, errors recorded.
function M.run(frame, script, ...)
    local fn = frame.__scripts[script]
    if not fn then return end
    local saveThis, saveArg1 = this, arg1
    this = frame
    arg1 = select(1, ...)
    local ok, err = pcall(fn, frame, ...)
    this, arg1 = saveThis, saveArg1
    if not ok then table.insert(M.errors, script .. ": " .. tostring(err)) end
end

function M.fire(eventName, a1, a2)
    for _, f in ipairs(allFrames) do
        if f.__events and f.__events[eventName] and f.__scripts.OnEvent then
            event = eventName; arg1 = a1; arg2 = a2; this = f
            local ok, err = pcall(f.__scripts.OnEvent, f, eventName, a1, a2)
            if not ok then table.insert(M.errors, "OnEvent " .. eventName .. ": " .. tostring(err)) end
        end
    end
end

function M.tick(dt)
    M.time = M.time + dt
    for _, f in ipairs(allFrames) do
        if f.__scripts.OnUpdate and f:IsVisible() then
            this = f; arg1 = dt
            local ok, err = pcall(f.__scripts.OnUpdate, f, dt)
            if not ok then table.insert(M.errors, "OnUpdate " .. tostring(f.__name) .. ": " .. tostring(err)) end
        end
    end
end

-- ---------------------------------------------------------------- stock UI
UIParent = CreateFrame("Frame", "UIParent")
UIParent.__w = 1280; UIParent.__h = 720
function UIParent:GetTop() return 720 end
WorldFrame = CreateFrame("Frame", "WorldFrame")
UISpecialFrames = {}
SlashCmdList = {}
DEFAULT_CHAT_FRAME = { AddMessage = function(_, msg) table.insert(M.chat, msg) end }
GameTooltip = CreateFrame("GameTooltip", "GameTooltip", UIParent)
for _, n in ipairs({ "MainMenuBar", "MultiBarBottomLeft", "MultiBarBottomRight", "MultiBarLeft", "MultiBarRight",
    "PlayerFrame", "TargetFrame", "Minimap", "MinimapCluster", "ChatFrame1", "ChatFrame2", "QuestLogFrame",
    "WorldMapFrame", "MerchantFrame", "CastingBarFrame", "ContainerFrame1", "PlayerFrameManaBar", "ChatFrameEditBox" }) do
    CreateFrame("Frame", n, UIParent)
end
BonusActionBarFrame = CreateFrame("Frame", "BonusActionBarFrame", MainMenuBar)
MerchantFrame:Hide()
QuestLogFrame:Hide(); WorldMapFrame:Hide(); ContainerFrame1:Hide(); ChatFrameEditBox:Hide()
if SECURE then
    -- 2.x/3.x player and target frames are secure unit buttons.
    PlayerFrame.__protected = true; TargetFrame.__protected = true
    MainMenuBar.__protected = true; MultiBarBottomLeft.__protected = true
    MultiBarBottomRight.__protected = true; MultiBarLeft.__protected = true; MultiBarRight.__protected = true
    BonusActionBarFrame.__protected = true
end

function EnumerateFrames(prev)
    local i = 0
    if prev then for k, f in ipairs(allFrames) do if f == prev then i = k end end end
    return allFrames[i + 1]
end

function ToggleFrame(f) if f:IsShown() then f:Hide() else f:Show() end end
function ToggleCharacter() M.log[#M.log + 1] = "ToggleCharacter" end
function ToggleSpellBook() M.log[#M.log + 1] = "ToggleSpellBook" end
function ToggleTalentFrame() M.log[#M.log + 1] = "ToggleTalentFrame" end
if FLAVOR ~= "wotlk" then
    function ToggleQuestLog() ToggleFrame(QuestLogFrame) end
    function ToggleWorldMap() ToggleFrame(WorldMapFrame) end
end
function ToggleGameMenu() M.log[#M.log + 1] = "ToggleGameMenu" end
if FLAVOR == "vanilla" then function Jump() M.log[#M.log + 1] = "Jump" end end
function CloseAllBags() end
function OpenAllBags() end
function OpenBag() end
function OpenBackpack() end
function ToggleBag() end
function ToggleBackpack() end
function ToggleKeyRing() end
function CloseBag() end
function CloseBackpack() end
function IsBagOpen() end
function CanOpenPanels() return 1 end
function updateContainerFrameAnchors() end
function FCF_UpdateDockPosition() end
function FCF_SetWindowAlpha() end
function FCF_SetWindowColor() end
function FCF_IsValidChatFrame() return 1 end
if FLAVOR ~= "wotlk" then function FCF_UpdateCombatLogPosition() end end
function ChatFrame_OpenChat(text) M.openedChat = text end
function SetItemButtonTexture() end
function SetItemButtonCount() end
function SetItemButtonDesaturated() end
function ContainerFrame_UpdateCooldown() end
function CooldownFrame_SetTimer() end
ITEM_QUALITY_COLORS = {}
BOOKTYPE_SPELL = "spell"

-- hooksecurefunc: 2.0+ only
if SECURE then
    function hooksecurefunc(name, fn)
        local orig = _G[name]
        _G[name] = function(...) local r = { orig(...) }; fn(...); return unpack(r) end
        M.hooked = M.hooked or {}; M.hooked[name] = true
    end
end

-- ---------------------------------------------------------------- game state
M.actions = {}
for slot = 1, 132 do M.actions[slot] = "Interface\\Icons\\Slot" .. slot end
function GetActionTexture(s) return M.actions[s] end
function GetActionCount() return 0 end
function GetActionCooldown() return 0, 0, 1 end
function IsUsableAction() return 1 end
function IsActionInRange() return 1 end
function HasAction(s) return M.actions[s] ~= nil end
function GetBonusBarOffset() return M.bonus end
function IsShiftKeyDown() return M.shift end
function IsControlKeyDown() return M.ctrl end
function IsAltKeyDown() return false end
function CursorHasItem() return false end
function CursorHasSpell() return false end
function PickupAction() end
function PlaceAction() end
function GetActionBarPage() return 1 end
NUM_ACTIONBAR_BUTTONS = 12

local function protectedApi(name, fn)
    _G[name] = function(...)
        if SECURE and not M.secureContext then
            table.insert(M.blocked, "insecure call " .. name)
            return
        end
        return fn(...)
    end
end
protectedApi("UseAction", function(slot) table.insert(M.casts, slot) end)
protectedApi("UseContainerItem", function(b, s) table.insert(M.casts, "item" .. b .. ":" .. s) end)
protectedApi("JumpOrAscendStart", function() M.log[#M.log + 1] = "Jump" end)
protectedApi("ClearTarget", function() M.log[#M.log + 1] = "ClearTarget" end)
if not SECURE then
    _G.UseAction = function(slot) table.insert(M.casts, slot) end
    _G.UseContainerItem = function(b, s) table.insert(M.casts, "item" .. b .. ":" .. s) end
end

function UnitName() return "Hero" end
function UnitExists() return false end
function UnitIsPlayer() return false end
function UnitIsUnit() return false end
function UnitLevel() return 10 end
function UnitXPMax() return 1000 end
function UnitXP() return 100 end
function GetXPExhaustion() return nil end
function SendChatMessage(msg, kind, _, target) table.insert(M.chat, "SEND " .. msg .. " " .. kind .. " " .. tostring(target)) end
function GetMoney() return 12345 end
function GetContainerNumSlots(bag) if bag == -2 then return 4 end if bag == 0 then return 16 end return 0 end
function GetKeyRingSize() return 4 end
function GetContainerItemInfo(bag, slot) if bag == 0 and slot == 3 then return "tex", 1, nil, 0 end end
function GetContainerItemLink(bag, slot) if bag == 0 and slot == 3 and not M.junkSold then return "|cff9d9d9d|Hitem:1|h[Junk]|h|r" end end
function ContainerIDToInventoryID(bag) return 19 + bag end
function GetInventorySlotInfo() return 20, "tex" end
function GetInventoryItemTexture() return nil end
function IsInventoryItemLocked() return false end
function ClearCursor() end
function PutItemInBag() return false end
function PickupBagFromSlot() end
function PutItemInBackpack() end
function PutKeyInKeyRing() end

-- ---------------------------------------------------------------- bindings
M.bindings = {}
M.bindingSet = 1
function GetCurrentBindingSet() return M.bindingSet end
function GetBindingAction(key) return M.bindings[key] or "" end
function SetBinding(key, cmd)
    if SECURE and M.combat then block("SetBinding", nil); return end
    M.bindings[key] = cmd
end
function SaveBindings() end
M.overrides = {}
if SECURE then
    function SetOverrideBinding(owner, prio, key, cmd)
        if M.combat then block("SetOverrideBinding", owner); return end
        M.overrides[key] = { cmd = cmd }
    end
    function SetOverrideBindingClick(owner, prio, key, button, mouse)
        if M.combat then block("SetOverrideBindingClick", owner); return end
        M.overrides[key] = { click = button, mouse = mouse }
    end
    function ClearOverrideBindings(owner)
        if M.combat then block("ClearOverrideBindings", owner); return end
        M.overrides = {}
    end
end

-- Stock binding commands the harness can execute.
local stockCommands = {
    JUMP = function() M.log[#M.log + 1] = "Jump" end,
    TOGGLEGAMEMENU = function() M.log[#M.log + 1] = "ToggleGameMenu" end,
    TOGGLEAUTORUN = function() end,
}

-- Real 2.4.3/3.3.5a secure dispatch, condensed from their SecureTemplates.lua
-- and ActionButton.lua.
local function modifierPrefix()
    local prefix = ""
    if M.shift then prefix = "shift-" .. prefix end
    if M.ctrl then prefix = "ctrl-" .. prefix end
    return prefix
end
local function modified(frame, name, button)
    local suffix = button == "LeftButton" and "1" or (button == "RightButton" and "2" or "")
    return frame:GetAttribute(modifierPrefix(), name, suffix)
end
function M.secureClick(frame, button)
    button = button or "LeftButton"
    if frame.__template ~= "SecureActionButtonTemplate" then
        M.run(frame, "OnClick", button); return
    end
    M.secureContext = true
    local kind = modified(frame, "type", button)
    if kind == "action" then
        local action
        if frame:GetID() > 0 then
            local page = modified(frame, "actionpage", button) or GetActionBarPage()
            action = frame:GetID() + (page - 1) * NUM_ACTIONBAR_BUTTONS
        else
            action = modified(frame, "action", button) or 1
        end
        UseAction(action)
    elseif kind == "macro" then
        local text = modified(frame, "macrotext", button)
        if text == "/cleartarget" then ClearTarget() end
    end
    M.secureContext = false
    M.run(frame, "PostClick", button)
end

-- Press a key chord such as "SHIFT-3": modifiers held as the host sends them.
function M.press(key)
    M.shift = string.find(key, "SHIFT-", 1, true) ~= nil
    M.ctrl = string.find(key, "CTRL-", 1, true) ~= nil
    local override = M.overrides[key]
    local cmd
    if override then
        if override.click then
            M.secureClick(_G[override.click], override.mouse); M.shift = false; M.ctrl = false; return
        end
        cmd = override.cmd
    else
        cmd = M.bindings[key]
    end
    if cmd and stockCommands[cmd] then
        M.secureContext = true; stockCommands[cmd](); M.secureContext = false
    elseif cmd and string.find(cmd, "^AP_ACTION_") then
        local n = tonumber(string.sub(cmd, 11))
        local ok, err = pcall(AndroidPort_Action, n, "down")
        if not ok then table.insert(M.errors, "AP_ACTION: " .. err) end
    elseif cmd == "AP_TOGGLE_RADIAL" then pcall(AndroidPort_ToggleRadial)
    elseif cmd == "AP_MOVE_UI" then pcall(AndroidPort_ToggleMoveUI)
    elseif cmd == "AP_NEARBY_INTERACT" then pcall(AndroidPort_NearbyInteract)
    else
        M.log[#M.log + 1] = "unbound " .. key
    end
    M.shift = false; M.ctrl = false
end

-- ---------------------------------------------------------------- state driver (wotlk)
if FLAVOR == "wotlk" then
    function SecureHandlerSetFrameRef(header, label, ref)
        header.__refs = header.__refs or {}
        header.__refs[label] = ref
    end
    M.drivers = {}
    function RegisterStateDriver(frame, state, values)
        table.insert(M.drivers, { frame = frame, state = state, values = values })
    end
    function M.updateDrivers()
        for _, d in ipairs(M.drivers) do
            local newstate = "0"
            if M.bonus > 0 then newstate = tostring(M.bonus) end
            local body = d.frame:GetAttribute("_onstate-" .. d.state)
            if body and d.last ~= newstate then
                d.last = newstate
                local env = { tonumber = tonumber, newstate = newstate,
                    self = { GetFrameRef = function(_, label) return d.frame.__refs[label] end } }
                local chunk = assert(loadstring(body))
                setfenv(chunk, env)
                M.secureContext = true
                local ok, err = pcall(chunk)
                M.secureContext = false
                if not ok then table.insert(M.errors, "snippet: " .. err) end
            end
        end
    end
else
    function M.updateDrivers() end
end

return M
