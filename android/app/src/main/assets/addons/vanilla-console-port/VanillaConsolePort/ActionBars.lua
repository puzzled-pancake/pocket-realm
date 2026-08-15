VanillaConsolePort.ActionBars = VanillaConsolePort.ActionBars or {}
local Bars = VanillaConsolePort.ActionBars

Bars.buttons = Bars.buttons or {}
Bars.page = Bars.page or 1
Bars.ready = Bars.ready or false

local watcherEvents = {
    "ACTIONBAR_SLOT_CHANGED",
    "ACTIONBAR_UPDATE_USABLE",
    "ACTIONBAR_UPDATE_COOLDOWN",
    "UPDATE_BONUS_ACTIONBAR",
    "PLAYER_AURAS_CHANGED",
}

local layoutMap = {
    -- Action numbers are truthful in both RP6 Xbox and Retro face modes;
    -- fixed A/B/X/Y glyphs would describe different physical positions.
    { side = 1, x = 0, y = -1, label = "1" },
    { side = 1, x = -1, y = 0, label = "2" },
    { side = 1, x = 0, y = 1, label = "3" },
    { side = 1, x = 1, y = 0, label = "4" },
    { side = -1, x = 0, y = -1, label = "5 v" },
    { side = -1, x = -1, y = 0, label = "6 <" },
    { side = -1, x = 0, y = 1, label = "7 ^" },
    { side = -1, x = 1, y = 0, label = "8 >" },
}

-- Movable containers for the two action stars. They exist from file load so
-- the frame mover's curated registry can adopt them, the buttons anchor to
-- them, and one journaled move or scale carries the whole cluster.
local clusterNames = {
    [1] = "VanillaConsolePortRightCluster",
    [-1] = "VanillaConsolePortLeftCluster",
}
Bars.clusterFrames = Bars.clusterFrames or {}

local function EnsureCluster(side)
    local layout = VanillaConsolePort:GetLayout()
    local name = clusterNames[side]
    local cluster = getglobal(name)
    if not cluster then
        cluster = CreateFrame("Frame", name, UIParent)
        cluster:EnableMouse(false)
        cluster:SetFrameStrata("BACKGROUND")
        local size = layout.button + 2 * layout.padding + 8
        cluster:SetWidth(size)
        cluster:SetHeight(size)
        cluster:ClearAllPoints()
        cluster:SetPoint("CENTER", UIParent, "BOTTOM", side * layout.star / 2, layout.bottom)
    end
    cluster:Show()
    return cluster
end

-- Errors here leave the fallback UIParent anchoring in ApplyLayout active.
pcall(function()
    Bars.clusterFrames[1] = EnsureCluster(1)
    Bars.clusterFrames[-1] = EnsureCluster(-1)
end)

function Bars:GetActionSlot(buttonIndex)
    if self.page == 1 then
        local bonus = GetBonusBarOffset and GetBonusBarOffset() or 0
        if bonus and bonus > 0 then return 60 + bonus * 12 + buttonIndex end
    end
    return (self.page - 1) * 10 + buttonIndex
end

function Bars:UseSlot(slot)
    local buttonIndex = math.mod(slot - 1, 8) + 1
    local page = math.floor((slot - 1) / 8) + 1
    local actionSlot
    if page == 1 then
        actionSlot = self:GetActionSlot(buttonIndex)
    else
        actionSlot = (page - 1) * 10 + buttonIndex
    end
    if HasAction(actionSlot) then UseAction(actionSlot) end
end

function Bars:CreateButton(index)
    local name = "VanillaConsolePortButton" .. index
    local button = getglobal(name)
    local cluster = self.clusterFrames[layoutMap[index].side]
    if not button then
        button = CreateFrame("Button", name, cluster or UIParent)
    elseif cluster and button:GetParent() ~= cluster then
        -- Children inherit cluster scale, so a journaled resize of the star
        -- has to reach the buttons through parenting, not anchors alone.
        button:SetParent(cluster)
    end
    button:Hide()
    button:SetFrameStrata("MEDIUM")
    button:SetID(index)
    button:RegisterForClicks("LeftButtonUp", "RightButtonUp")
    button:RegisterForDrag("LeftButton", "RightButton")
    -- The native movement overlay on the host can shadow the star's edge, so
    -- grow the release surface past the visible tile.
    button:SetHitRectInsets(-14, -14, -14, -14)

    local background = button.background
    if not background then
        background = button:CreateTexture(nil, "BACKGROUND")
        button.background = background
    end
    background:SetTexture("Interface\\CHARACTERFRAME\\TempPortraitAlphaMask")
    background:SetVertexColor(0.04, 0.06, 0.09, 0.92)
    background:SetAllPoints(button)

    local icon = button.icon
    if not icon then
        icon = button:CreateTexture(nil, "ARTWORK")
        button.icon = icon
    end
    icon:ClearAllPoints()
    icon:SetPoint("TOPLEFT", button, "TOPLEFT", 5, -5)
    icon:SetPoint("BOTTOMRIGHT", button, "BOTTOMRIGHT", -5, 5)

    local shade = button.shade
    if not shade then
        shade = button:CreateTexture(nil, "OVERLAY")
        button.shade = shade
    end
    shade:SetAllPoints(icon)
    shade:SetTexture(0, 0, 0)
    shade:SetAlpha(0.7)
    shade:Hide()

    -- Interface 11200 implements CooldownFrameTemplate as a Model. The
    -- modern "Cooldown" CreateFrame type does not exist in this client.
    local cooldownName = name .. "Cooldown"
    local cooldown = button.cooldown or getglobal(cooldownName)
    if not cooldown then cooldown = CreateFrame("Model", cooldownName, button, "CooldownFrameTemplate") end
    button.cooldown = cooldown
    cooldown:SetAllPoints(icon)

    local prompt = button.prompt
    if not prompt then
        prompt = CreateFrame("Frame", nil, button)
        button.prompt = prompt
    end
    prompt:SetWidth(28)
    prompt:SetHeight(22)
    prompt:ClearAllPoints()
    prompt:SetPoint("TOP", button, "TOP", 0, 8)
    local promptBg = prompt.background
    if not promptBg then
        promptBg = prompt:CreateTexture(nil, "BACKGROUND")
        prompt.background = promptBg
    end
    promptBg:SetTexture("Interface\\CHARACTERFRAME\\TempPortraitAlphaMask")
    promptBg:SetAllPoints(prompt)
    promptBg:SetVertexColor(0.08, 0.12, 0.18, 1)
    local promptText = prompt.text
    if not promptText then
        promptText = prompt:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        prompt.text = promptText
    end
    promptText:ClearAllPoints()
    promptText:SetPoint("CENTER", prompt, "CENTER", 0, 0)
    promptText:SetText(layoutMap[index].label)

    local count = button.count
    if not count then
        count = button:CreateFontString(nil, "OVERLAY", "NumberFontNormal")
        button.count = count
    end
    count:ClearAllPoints()
    count:SetPoint("BOTTOMRIGHT", button, "BOTTOMRIGHT", -4, 4)

    button:SetScript("OnClick", function()
        Bars:UseSlot((Bars.page - 1) * 8 + index)
    end)
    button:SetScript("OnDragStart", function()
        if LOCK_ACTIONBAR ~= "1" then PickupAction(Bars:GetActionSlot(index)) end
    end)
    button:SetScript("OnReceiveDrag", function()
        if LOCK_ACTIONBAR ~= "1" then PlaceAction(Bars:GetActionSlot(index)); Bars:Refresh() end
    end)
    button:SetScript("OnEnter", function()
        local slot = Bars:GetActionSlot(index)
        GameTooltip:SetOwner(this, "ANCHOR_TOP")
        GameTooltip:SetAction(slot)
    end)
    button:SetScript("OnLeave", function() GameTooltip:Hide() end)
    return button
end

function Bars:ValidateButtons(buttons)
    if not buttons then return false end
    for index = 1, 8 do
        local button = buttons[index]
        if not button or not button.background or not button.icon or not button.shade
            or not button.cooldown or not button.prompt or not button.count then
            return false
        end
    end
    return true
end

function Bars:ApplyLayout(buttons)
    local layout = VanillaConsolePort:GetLayout()
    local halfGap = layout.star / 2
    for index = 1, 8 do
        local button = buttons[index]
        local item = layoutMap[index]
        button:SetWidth(layout.button)
        button:SetHeight(layout.button)
        button:ClearAllPoints()
        local cluster = self.clusterFrames[item.side]
        if cluster then
            button:SetPoint(
                "BOTTOM", cluster, "CENTER",
                item.x * layout.padding, item.y * layout.padding
            )
        else
            button:SetPoint(
                "BOTTOM", UIParent, "BOTTOM",
                item.side * halfGap + item.x * layout.padding,
                layout.bottom + item.y * layout.padding
            )
        end
        button.prompt:SetWidth(math.max(28, math.floor(layout.button * 0.42)))
        button.prompt:SetHeight(math.max(22, math.floor(layout.button * 0.30)))
    end
end

function Bars:UpdatePage()
    local nextPage = 1
    if IsShiftKeyDown() then nextPage = nextPage + 1 end
    if IsControlKeyDown() then nextPage = nextPage + 2 end
    if nextPage ~= self.page then
        self.page = nextPage
        self:Refresh()
    end
end

function Bars:UpdateButton(index, buttons)
    local button = (buttons or self.buttons)[index]
    local slot = self:GetActionSlot(index)
    local texture = GetActionTexture(slot)
    if texture then button.icon:SetTexture(texture); button.icon:Show() else button.icon:Hide() end
    local count = GetActionCount(slot)
    button.count:SetText(count and count > 1 and count or "")
    local start, duration, enable = GetActionCooldown(slot)
    CooldownFrame_SetTimer(button.cooldown, start, duration, enable)
    local usable, noMana = IsUsableAction(slot)
    if not usable then
        if noMana then button.icon:SetVertexColor(0.4, 0.4, 1) else button.icon:SetVertexColor(0.4, 0.4, 0.4) end
    elseif IsActionInRange(slot) == 0 then
        button.icon:SetVertexColor(1, 0.2, 0.2)
    else
        button.icon:SetVertexColor(1, 1, 1)
    end
end

function Bars:DeactivateWatcher()
    local watcher = self.watcher or getglobal("VanillaConsolePortBarWatcher")
    if not watcher then return end
    for _, eventName in ipairs(watcherEvents) do watcher:UnregisterEvent(eventName) end
    watcher:SetScript("OnEvent", nil)
    watcher:SetScript("OnUpdate", nil)
end

function Bars:RestoreStockBars()
    if not self.stockHidden then return end
    for frame, state in pairs(self.stockState or {}) do
        frame:SetAlpha(state.alpha or 1)
        if state.shown then frame:Show() else frame:Hide() end
    end
    self.stockState = nil
    self.stockHidden = false
end

function Bars:FailSafe(buttons)
    self.ready = false
    self:DeactivateWatcher()
    local candidates = buttons or self.buttons or {}
    for index = 1, 8 do
        local button = candidates[index] or getglobal("VanillaConsolePortButton" .. index)
        if button then button:Hide() end
    end
    self.buttons = {}
    self:RestoreStockBars()
end

function Bars:Refresh()
    if not self.ready or not self:ValidateButtons(self.buttons) then
        self:FailSafe(self.buttons)
        return false
    end
    local ok = pcall(function()
        for index = 1, 8 do self:UpdateButton(index, self.buttons) end
        for index = 1, 8 do self.buttons[index]:Show() end
    end)
    if not ok then
        self:FailSafe(self.buttons)
        return false
    end
    if not self:HideStockBars() then
        self:FailSafe(self.buttons)
        return false
    end
    return true
end

function Bars:HideStockBars()
    if self.stockHidden then return true end
    local frames = { MainMenuBar, BonusActionBarFrame, MultiBarBottomLeft, MultiBarBottomRight }
    local stockState = {}
    local captured = pcall(function()
        for _, frame in ipairs(frames) do
            if frame then stockState[frame] = { shown = frame:IsShown(), alpha = frame:GetAlpha() } end
        end
    end)
    if not captured then return false end

    self.stockState = stockState
    self.stockHidden = true
    local hidden = pcall(function()
        for frame, _ in pairs(stockState) do
            frame:Hide()
            frame:SetAlpha(0)
        end
    end)
    if not hidden then
        self:RestoreStockBars()
        return false
    end
    return true
end

function Bars:Initialize()
    if self.ready and self:ValidateButtons(self.buttons) then return true end
    self:FailSafe(self.buttons)

    local staged = {}
    local watcher
    local ok = pcall(function()
        for index = 1, 8 do staged[index] = self:CreateButton(index) end
        self:ApplyLayout(staged)
        for index = 1, 8 do self:UpdateButton(index, staged) end

        watcher = getglobal("VanillaConsolePortBarWatcher")
        if not watcher then watcher = CreateFrame("Frame", "VanillaConsolePortBarWatcher", UIParent) end
        watcher.elapsed = 0
        watcher:SetScript("OnEvent", function()
            if Bars.ready then Bars:Refresh() end
        end)
        watcher:SetScript("OnUpdate", function()
            if not Bars.ready then return end
            this.elapsed = this.elapsed + arg1
            if this.elapsed >= 0.08 then
                this.elapsed = 0
                Bars:UpdatePage()
            end
        end)
        for _, eventName in ipairs(watcherEvents) do watcher:RegisterEvent(eventName) end
        for index = 1, 8 do staged[index]:Show() end
    end)
    if not ok or not self:ValidateButtons(staged) or not watcher then
        self.watcher = watcher
        self:FailSafe(staged)
        return false
    end

    self.buttons = staged
    self.watcher = watcher
    self.ready = true
    return true
end
