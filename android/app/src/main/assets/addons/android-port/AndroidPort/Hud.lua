-- Android Port HUD: clean-room Interface 11200 chat placement plus an
-- experience strip docked to the player frame. Chat styling never writes to
-- the client's stored chat profile, so /ap resetui or /ap chat fully
-- reverts it; the look is simply re-applied at every login.
AndroidPort.Hud = AndroidPort.Hud or {}
local Hud = AndroidPort.Hud
Hud.ready = Hud.ready or false

local CHAT_WIDTH = 340
local CHAT_HEIGHT = 100
-- E3: while a whisper conversation is live the frame grows to a readable
-- transcript (~9 lines); the minimal 4-line strip scrolls the player's own
-- words away mid-conversation
local CHAT_TALK_HEIGHT = 220
-- whisper activity keeps the tall rect armed this long after the last line
local TALK_ACTIVE_SECONDS = 10
-- a burst (journal dump: the diary paces several lines a second) reveals
-- the scroll chrome so the newest lines can actually be reached
local BURST_MESSAGES = 5
local BURST_WINDOW = 2.5
local BURST_LINGER = 6
local CHAT_MARGIN = 28
local STOCK_CHAT_WIDTH = 430
local STOCK_CHAT_HEIGHT = 120
local MAX_PLAYER_LEVEL = 60

local chatButtons = { "UpButton", "DownButton", "BottomButton" }

-- Freed-frame gate for stock-frame lookups: see AP:LiveFrame in Core.lua.
-- A bare getglobal nil-check cannot tell a freed frame (whose userdata
-- lingers, C++ object gone) from a live one, and the first method read on
-- a freed frame faults the client (ERROR #132).
local function Live(name)
    local host = AndroidPort
    if host and host.LiveFrame then return host:LiveFrame(name) end
    return nil
end

function Hud:ChatEnabled()
    local db = AndroidPortDB
    if type(db) ~= "table" then return true end
    return db.chatMinimal ~= false
end

-- Default anchor raised past the action clusters so the chat never overlaps
-- either one on any supported screen; the offset follows the shared layout
-- so short and tall profiles both clear the buttons, with headroom for the
-- edit box that hangs below the frame.
function Hud:ChatAnchorY()
    local layout = AndroidPort:GetLayout()
    return layout.bottom + layout.padding + layout.button + 20
end

function Hud:HideChatChrome()
    -- E3: a live burst keeps the scroll buttons reachable even under the
    -- minimal treatment (a journal dump scrolls the player's words away)
    if self:BurstActive() then return end
    for _, suffix in ipairs(chatButtons) do
        local button = Live("ChatFrame1" .. suffix)
        if button then button:Hide() end
    end
    local tab = Live("ChatFrame1Tab")
    if tab then tab:Hide() end
    local combatTab = Live("ChatFrame2Tab")
    if combatTab then combatTab:Hide() end
    local menu = Live("ChatFrameMenuButton")
    if menu then menu:Hide() end
end

function Hud:ShowChatChrome()
    for _, suffix in ipairs(chatButtons) do
        local button = Live("ChatFrame1" .. suffix)
        if button then button:Show() end
    end
    local tab = Live("ChatFrame1Tab")
    if tab then tab:Show() end
    local combatTab = Live("ChatFrame2Tab")
    if combatTab then combatTab:Show() end
    local menu = Live("ChatFrameMenuButton")
    if menu then menu:Show() end
end

-- E3: is a whisper conversation live (recent whisper traffic in either
-- direction)? Drives the tall conversation rect.
function Hud:ConversationActive()
    return (self.talkActiveUntil or 0) > GetTime()
end

-- E3: is a message burst in flight (>= BURST_MESSAGES whisper lines inside
-- BURST_WINDOW, lingering BURST_LINGER after the last)? Drives the scroll
-- chrome reveal.
function Hud:BurstActive()
    return (self.burstUntil or 0) > GetTime()
end

-- E3: whisper traffic observer (both directions). Only TRANSITIONS apply
-- the rect - the composer's own echo must not resize the frame under the
-- player's fingers on every line.
function Hud:NoteWhisperActivity()
    local now = GetTime()
    self.talkActiveUntil = now + TALK_ACTIVE_SECONDS
    if not self.burstWindowAt or now - self.burstWindowAt > BURST_WINDOW then
        self.burstWindowAt = now
        self.burstCount = 0
    end
    self.burstCount = (self.burstCount or 0) + 1
    local burst = self.burstCount >= BURST_MESSAGES
    if burst then
        self.burstUntil = now + BURST_LINGER
    end
    if not self.chatTall then
        self.chatTall = true
        pcall(function() if self:ChatEnabled() then self:ApplyChatFrame() end end)
    end
    if burst and not self.chromeShown then
        self.chromeShown = true
        pcall(function() self:ShowChatChrome() end)
    end
end

-- Position the chat unless the player has journaled it with Move UI, then
-- strip the chrome. Typing never depended on the tab: the chat binding opens
-- the shared edit box directly, exactly like the stock simple-chat mode.
function Hud:ApplyChatFrame()
    if not self:ChatEnabled() then return false end
    local chat = Live("ChatFrame1")
    if not chat or not chat.SetPoint then return false end
    local db = AndroidPortDB
    local journaled = type(db) == "table" and type(db.frameAnchors) == "table" and
        db.frameAnchors["ChatFrame1"] ~= nil
    chat:SetUserPlaced(1)
    if not journaled then
        chat:ClearAllPoints()
        chat:SetWidth(CHAT_WIDTH)
        -- E3: tall while talking, minimal at rest (a journaled rect is the
        -- player's own choice and is never resized out from under them)
        chat:SetHeight(self:ConversationActive() and CHAT_TALK_HEIGHT or CHAT_HEIGHT)
        chat:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", CHAT_MARGIN, self:ChatAnchorY())
    end
    -- The trailing 1 is doNotSave: the stored chat profile stays stock so a
    -- reset returns the exact pre-install look; we re-apply each login.
    if type(FCF_SetWindowAlpha) == "function" then FCF_SetWindowAlpha(chat, 0, 1) end
    if type(FCF_SetWindowColor) == "function" then FCF_SetWindowColor(chat, 0, 0, 0, 1) end
    -- The stock hover path re-shows the tab and fades the background in when
    -- oldAlpha is below its own fade threshold; park it exactly at that
    -- threshold so the minimal look survives hovering.
    chat.oldAlpha = 0.25
    -- The docked combat log shares the rect, so its chrome gets the same
    -- treatment or it reappears alongside the minimal chat.
    local combat = Live("ChatFrame2")
    if combat and combat.SetPoint then
        if type(FCF_SetWindowAlpha) == "function" then FCF_SetWindowAlpha(combat, 0, 1) end
        if type(FCF_SetWindowColor) == "function" then FCF_SetWindowColor(combat, 0, 0, 0, 1) end
        combat.oldAlpha = 0.25
    end
    self:HideChatChrome()
    return true
end

function Hud:RestoreChatFrame()
    local chat = Live("ChatFrame1")
    if not chat or not chat.SetPoint then return false end
    chat:ClearAllPoints()
    chat:SetWidth(STOCK_CHAT_WIDTH)
    chat:SetHeight(STOCK_CHAT_HEIGHT)
    chat:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 32, 95)
    chat:SetUserPlaced(nil)
    if type(FCF_SetWindowAlpha) == "function" then FCF_SetWindowAlpha(chat, 0.25, 1) end
    if type(FCF_SetWindowColor) == "function" then FCF_SetWindowColor(chat, 0, 0, 0, 1) end
    chat.oldAlpha = 0.25
    local combat = Live("ChatFrame2")
    if combat and type(FCF_SetWindowAlpha) == "function" then
        FCF_SetWindowAlpha(combat, 0.25, 1)
    end
    -- Let the stock dock update carry the docked combat log back with the
    -- frame; calling the captured original skips our minimal re-assert.
    if Hud.stockDockUpdate then Hud.stockDockUpdate() end
    self:ShowChatChrome()
    return true
end

function Hud:ToggleChat()
    local db = AndroidPortDB
    if type(db) ~= "table" then return false end
    if self:ChatEnabled() then
        db.chatMinimal = false
        self:RestoreChatFrame()
        AndroidPort:Print("stock chat restored")
    else
        db.chatMinimal = true
        self:ApplyChatFrame()
        AndroidPort:Print("minimal chat enabled")
    end
    return true
end

-- Stock dock updates re-anchor the default chat frame; run ours afterwards
-- so the journal (or the default minimal anchor) always wins.
function Hud:InstallChatHooks()
    if self.chatHooksInstalled then return true end
    self.chatHooksInstalled = true
    if type(FCF_UpdateDockPosition) == "function" then
        Hud.stockDockUpdate = FCF_UpdateDockPosition
        FCF_UpdateDockPosition = function()
            Hud.stockDockUpdate()
            if Hud:ChatEnabled() then Hud:ApplyChatFrame() end
        end
    end
    if type(FCF_UpdateCombatLogPosition) == "function" then
        Hud.stockCombatLogUpdate = FCF_UpdateCombatLogPosition
        FCF_UpdateCombatLogPosition = function()
            Hud.stockCombatLogUpdate()
            if Hud:ChatEnabled() then Hud:ApplyChatFrame() end
        end
    end
    -- Stock's hover pass unconditionally re-shows the tab and, once a hover
    -- cycle has reset oldAlpha, fades the whole chrome back in. Simple chat
    -- avoids that by failing the per-frame validity gate; do the same for
    -- the treated frames so the minimal look survives pointer traffic.
    if type(FCF_IsValidChatFrame) == "function" then
        Hud.stockIsValidChatFrame = FCF_IsValidChatFrame
        FCF_IsValidChatFrame = function(frame)
            if Hud:ChatEnabled() then
                if frame == getglobal("ChatFrame1") or frame == getglobal("ChatFrame2") then
                    return nil
                end
            end
            return Hud.stockIsValidChatFrame(frame)
        end
    end
    return true
end

-- Experience strip docked to the player frame: a child StatusBar moves and
-- scales with the unit frame for free and never appears in the Move UI
-- sweep because it is not a top-level window. The strip sits directly under
-- the mana bar with a two-unit gap, so it reads as part of the portrait
-- block instead of floating below the frame art. The frame art is untouched.
function Hud:CreateXPBar()
    local bar = getglobal("AndroidPortXPBar")
    if bar then return bar end
    local playerFrame = Live("PlayerFrame")
    if not playerFrame then return nil end
    local manaBar = Live("PlayerFrameManaBar")
    bar = CreateFrame("StatusBar", "AndroidPortXPBar", playerFrame)
    bar:SetHeight(8)
    if manaBar then
        bar:SetPoint("TOPLEFT", manaBar, "BOTTOMLEFT", 0, -2)
        bar:SetPoint("TOPRIGHT", manaBar, "BOTTOMRIGHT", 0, -2)
    else
        bar:SetPoint("TOPLEFT", playerFrame, "BOTTOMLEFT", 4, 9)
        bar:SetPoint("TOPRIGHT", playerFrame, "BOTTOMRIGHT", -4, 9)
    end
    local backing = bar:CreateTexture(nil, "BACKGROUND")
    backing:SetTexture(0, 0, 0)
    backing:SetAlpha(0.6)
    backing:SetAllPoints(bar)
    bar.backing = backing
    bar:SetStatusBarTexture("Interface\\TargetingFrame\\UI-StatusBar")
    bar:SetMinMaxValues(0, 1)
    bar:SetValue(0)
    bar:EnableMouse(true)
    local tick = bar:CreateTexture(nil, "OVERLAY")
    tick:SetWidth(2)
    tick:SetHeight(8)
    tick:SetTexture(1, 1, 1)
    tick:SetPoint("LEFT", bar, "LEFT", 0, 0)
    tick:Hide()
    bar.tick = tick
    bar:SetScript("OnEnter", function()
        if not this:IsShown() then return end
        GameTooltip:SetOwner(this, "ANCHOR_TOPLEFT")
        GameTooltip:SetText("Experience")
        local maxXp = this.maxXp or 0
        local xp = this.xp or 0
        if maxXp > 0 then
            GameTooltip:AddLine(string.format("%d / %d (%.1f%%)", xp, maxXp, xp * 100 / maxXp))
            GameTooltip:AddLine(string.format("To level: %d", maxXp - xp))
        end
        if this.rested then
            GameTooltip:AddLine(string.format("Rested bonus: +%d", this.rested))
        end
        GameTooltip:Show()
    end)
    bar:SetScript("OnLeave", function() GameTooltip:Hide() end)
    return bar
end

function Hud:UpdateXPBar()
    local bar = self:CreateXPBar()
    if not bar then return false end
    -- Stock branches on level rather than the XP maximum at the level cap.
    if UnitLevel("player") >= MAX_PLAYER_LEVEL or UnitXPMax("player") == 0 then
        bar:Hide()
        return true
    end
    local maxXp = UnitXPMax("player")
    local xp = UnitXP("player")
    local rested = GetXPExhaustion()
    bar.maxXp = maxXp
    bar.xp = xp
    bar.rested = rested
    bar:SetMinMaxValues(0, maxXp)
    bar:SetValue(xp)
    if rested then
        bar:SetStatusBarColor(0.0, 0.39, 0.88)
        local fraction = (xp + rested) / maxXp
        if fraction > 1 then fraction = 1 end
        bar.tick:ClearAllPoints()
        bar.tick:SetPoint("LEFT", bar, "LEFT", fraction * bar:GetWidth(), 0)
        if fraction < 1 then bar.tick:Show() else bar.tick:Hide() end
    else
        bar:SetStatusBarColor(0.58, 0.0, 0.55)
        bar.tick:Hide()
    end
    bar:Show()
    return true
end

-- pcall in this module guards Lua errors only; it is not crash protection.
function Hud:Initialize()
    if self.ready then return true end
    local host = AndroidPort
    if not host or type(host.GetLayout) ~= "function" then return false end
    local ok = pcall(function()
        Hud:InstallChatHooks()
        Hud:ApplyChatFrame()
        Hud:UpdateXPBar()
    end)
    if not ok then
        Hud:FailSafe()
        return false
    end
    Hud.ready = true
    return true
end

function Hud:FailSafe()
    Hud.ready = false
    pcall(function()
        local bar = getglobal("AndroidPortXPBar")
        if bar then bar:Hide() end
    end)
end

local events = CreateFrame("Frame", "AndroidPortHudEvents", UIParent)
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("UPDATE_CHAT_WINDOWS")
events:RegisterEvent("PLAYER_XP_UPDATE")
events:RegisterEvent("UPDATE_EXHAUSTION")
events:RegisterEvent("PLAYER_LEVEL_UP")
-- E3: whisper traffic drives the tall conversation rect + burst chrome
events:RegisterEvent("CHAT_MSG_WHISPER")
events:RegisterEvent("CHAT_MSG_WHISPER_INFORM")
events:SetScript("OnEvent", function()
    -- Crash-bisection switch ("/ap off hud"): skip ALL world-entry work so
    -- a surviving crash genuinely exonerates this module.
    if AndroidPort and not AndroidPort:IsModuleEnabled("hud") then return end
    if event == "CHAT_MSG_WHISPER" or event == "CHAT_MSG_WHISPER_INFORM" then
        Hud:NoteWhisperActivity()
        return
    end
    if event == "PLAYER_ENTERING_WORLD" or event == "UPDATE_CHAT_WINDOWS" then
        -- The engine applies the saved chat rectangle after the load-time
        -- pass, silently dropping the window back onto the action cluster;
        -- schedule delayed re-asserts so our rect wins no matter the timing.
        if event == "PLAYER_ENTERING_WORLD" then
            this.pendingReassert = { 1, 3, 7 }
            this.elapsed = 0
        end
        pcall(function() Hud:ApplyChatFrame() end)
    end
    Hud:UpdateXPBar()
end)
events:SetScript("OnUpdate", function()
    -- E3 decay: the resting rect returns once the conversation quiets and
    -- the burst lapses (state flags make each decay apply exactly once)
    if Hud.chatTall and not Hud:ConversationActive() then
        Hud.chatTall = nil
        pcall(function() if Hud:ChatEnabled() then Hud:ApplyChatFrame() end end)
    end
    if Hud.chromeShown and not Hud:BurstActive() then
        Hud.chromeShown = nil
        pcall(function() if Hud:ChatEnabled() then Hud:ApplyChatFrame() end end)
    end
    if not this.pendingReassert then return end
    if AndroidPort and not AndroidPort:IsModuleEnabled("hud") then
        this.pendingReassert = nil
        return
    end
    this.elapsed = (this.elapsed or 0) + arg1
    if this.elapsed < this.pendingReassert[1] then return end
    this.elapsed = 0
    tremove(this.pendingReassert, 1)
    if table.getn(this.pendingReassert) == 0 then this.pendingReassert = nil end
    -- pcall guards Lua errors only; it is not crash protection.
    pcall(function() if Hud:ChatEnabled() then Hud:ApplyChatFrame() end end)
end)
