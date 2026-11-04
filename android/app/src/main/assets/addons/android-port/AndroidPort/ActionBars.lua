AndroidPort.ActionBars = AndroidPort.ActionBars or {}
local Bars = AndroidPort.ActionBars
local AP = AndroidPort

-- Lua 5.0 (Interface 11200) only has math.mod; the 5.1 clients ship fmod.
local mod = math.mod or math.fmod

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

-- Forever layout: every set is one row per side, read left to right as
-- left, up, down, right (D-pad 6 7 5 8, face 2 3 1 4).
local foreverColumn = { 3, 1, 2, 4, 3, 1, 2, 4 }
local FOREVER_ROW_LABELS = { "", "LT", "RT", "LT+RT" }

-- Forever keeps the base set's four face buttons on fixed jobs: bottom
-- jumps, left interacts, top opens the radial menu, right cancels (closes
-- windows, then clears the target). The command is the stock or add-on
-- binding the 2.0+ clients run through an override binding.
local foreverFixed = {
    [1] = { label = "Jump", icon = "Interface\\Icons\\Ability_Rogue_Sprint", command = "JUMP" },
    [2] = { label = "Use", icon = "Interface\\Icons\\INV_Misc_Bag_10", command = "AP_NEARBY_INTERACT" },
    [3] = { label = "Menu", icon = "Interface\\Icons\\INV_Misc_Gear_01", command = "AP_TOGGLE_RADIAL" },
    [4] = { label = "Back", icon = "Interface\\Icons\\Spell_ChargeNegative", command = "TOGGLEGAMEMENU" },
}

-- Four modifier pages over the same eight face/D-pad positions: 32 fixed
-- buttons, each owning its action slot for the whole session. Fixed
-- attributes are what keep the 2.0+ clients correct in combat, where
-- secure attributes may not be edited; only the page-1 bonus-bar re-point
-- ever mutates one, and that defers itself out of combat.
local BUTTON_COUNT = 32
local PAGE_KEYS = { "", "SHIFT-", "CTRL-", "CTRL-SHIFT-" }
-- Modifier prefixes the secure template reads on a star-layout button: a
-- touch or key press with Shift/Ctrl held fires that page's slot.
local PAGE_ATTRIBUTE_PREFIX = { nil, "shift-", "ctrl-", "ctrl-shift-" }
local INACTIVE_ROW_ALPHA = 0.45

-- Movable containers for the two action stars. They exist from file load so
-- the frame mover's curated registry can adopt them, the buttons anchor to
-- them, and one journaled move or scale carries the whole cluster.
local clusterNames = {
    [1] = "AndroidPortRightCluster",
    [-1] = "AndroidPortLeftCluster",
}
Bars.clusterFrames = Bars.clusterFrames or {}

local function StarClusterSize(layout)
    return layout.button + 2 * layout.padding + 8
end

local function ForeverMetrics(layout)
    local size = math.floor(layout.button * 0.62 + 0.5)
    local gap = math.floor(size * 0.12 + 0.5)
    return size, gap, 4 * size + 3 * gap + 16
end

local function EnsureCluster(side)
    local layout = AndroidPort:GetLayout()
    local name = clusterNames[side]
    local cluster = getglobal(name)
    if not cluster then
        cluster = CreateFrame("Frame", name, UIParent)
        cluster:EnableMouse(false)
        cluster:SetFrameStrata("BACKGROUND")
        local size = StarClusterSize(layout)
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

function Bars:IsForever()
    return AP.GetControlLayout and AP:GetControlLayout() == "forever"
end

local function PageButtonIndex(number)
    return math.floor((number - 1) / 8) + 1, mod(number - 1, 8) + 1
end

local function PageSlot(page, buttonIndex)
    return (page - 1) * 12 + buttonIndex
end

local function BonusSlot(buttonIndex)
    local bonus = GetBonusBarOffset and GetBonusBarOffset() or 0
    if bonus and bonus > 0 then return 60 + bonus * 12 + buttonIndex end
    return buttonIndex
end

function Bars:GetActionSlot(buttonIndex)
    if self.page == 1 then return BonusSlot(buttonIndex) end
    return PageSlot(self.page, buttonIndex)
end

-- The absolute action slot one of the 32 buttons owns. Page 1 honours the
-- stance/stealth bonus bar exactly like the stock bar.
function Bars:ActionSlotForNumber(number)
    local page, buttonIndex = PageButtonIndex(number)
    if page == 1 then return BonusSlot(buttonIndex) end
    return PageSlot(page, buttonIndex)
end

-- The fixed Forever job a button carries, or nil for an ordinary action.
function Bars:FixedRole(number)
    if number >= 1 and number <= 4 and self:IsForever() then return foreverFixed[number] end
    return nil
end

-- The slot a button shows. On the 2.0+ clients the star layout keeps the
-- eight page-1 buttons on screen for every page (protected frames cannot be
-- shown or hidden in combat), so they display the held page; page 1 reads
-- the attribute the secure click really fires.
function Bars:DisplaySlot(number)
    local page, buttonIndex = PageButtonIndex(number)
    if AP.SECURE and page == 1 then
        local button = self.buttons[number]
        if not self:IsForever() and self.page > 1 then return PageSlot(self.page, buttonIndex) end
        local fired = button and button.GetAttribute and button:GetAttribute("action")
        if type(fired) == "number" then return fired end
    end
    return self:ActionSlotForNumber(number)
end

-- Forever's fixed base-set jobs on the 1.12 client, where add-on code may
-- call these directly. The 2.0+ clients reach them through override
-- bindings and the secure button attributes instead.
function Bars:RunFixed(number)
    local role = self:FixedRole(number)
    if not role then return false end
    if role.command == "AP_NEARBY_INTERACT" then
        AndroidPort_NearbyInteract()
    elseif role.command == "AP_TOGGLE_RADIAL" then
        AndroidPort_ToggleRadial()
    elseif AP.SECURE then
        return true
    elseif role.command == "JUMP" then
        if Jump then Jump() end
    elseif role.command == "TOGGLEGAMEMENU" then
        if ToggleGameMenu then ToggleGameMenu() end
    end
    return true
end

function Bars:UseSlot(slot)
    -- The 2.0+ clients block add-on calls to UseAction outright; there the
    -- keys and clicks ride the secure buttons instead, so this legacy path
    -- must never run on them.
    if AP.SECURE then return end
    local buttonIndex = mod(slot - 1, 8) + 1
    local page = math.floor((slot - 1) / 8) + 1
    local actionSlot
    if page == 1 then
        actionSlot = self:GetActionSlot(buttonIndex)
    else
        actionSlot = PageSlot(page, buttonIndex)
    end
    if HasAction(actionSlot) then UseAction(actionSlot) end
end

-- Interface 11200 has no GetCursorInfo; the stock pickup checks are the
-- CursorHas family, with the spell variant optional in case the host build
-- predates it.
local function CursorCarriesPickup()
    if CursorHasItem and CursorHasItem() then return true end
    if CursorHasSpell and CursorHasSpell() then return true end
    return false
end

function Bars:CreateButton(number)
    local name = "AndroidPortButton" .. number
    local button = getglobal(name)
    local _, index = PageButtonIndex(number)
    local cluster = self.clusterFrames[layoutMap[index].side]
    if not button then
        if AP.SECURE then
            -- SecureActionButtonTemplate hands the click to Blizzard's own
            -- secure handler, which reads the type/action attributes; the
            -- add-on never calls the protected API itself. No OnClick
            -- script is set here on purpose: it would shadow the secure
            -- dispatch. The attributes are written by ApplySecureAttributes.
            button = CreateFrame("Button", name, cluster or UIParent, "SecureActionButtonTemplate")
            button:SetAttribute("type", "action")
            button:SetAttribute("action", self:ActionSlotForNumber(number))
            -- A Forever menu or interact tap still has to reach add-on
            -- code; PostClick runs after the secure dispatch, untainted.
            button:SetScript("PostClick", function()
                local role = Bars:FixedRole(number)
                if role and (role.command == "AP_NEARBY_INTERACT" or role.command == "AP_TOGGLE_RADIAL") then
                    Bars:RunFixed(number)
                end
            end)
        else
            button = CreateFrame("Button", name, cluster or UIParent)
        end
    elseif cluster and button:GetParent() ~= cluster then
        -- Children inherit cluster scale, so a journaled resize of the star
        -- has to reach the buttons through parenting, not anchors alone.
        button:SetParent(cluster)
    end
    button:Hide()
    button:SetFrameStrata("MEDIUM")
    -- ActionButton_CalculateAction ignores the "action" attribute on any
    -- button whose id is above zero, computing id + page offset instead, so
    -- the secure buttons must keep id 0.
    if AP.SECURE then button:SetID(0) else button:SetID(number) end
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

    -- Interface 11200 implements CooldownFrameTemplate as a Model; the
    -- 2.4.3 and 3.3.5a clients ship it on the native Cooldown widget. Both
    -- drive through the same CooldownFrame_SetTimer entry point.
    local cooldownName = name .. "Cooldown"
    local cooldown = button.cooldown or getglobal(cooldownName)
    if not cooldown then
        if AP.SECURE then
            cooldown = CreateFrame("Cooldown", cooldownName, button, "CooldownFrameTemplate")
        else
            cooldown = CreateFrame("Model", cooldownName, button, "CooldownFrameTemplate")
        end
    end
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

    if not AP.SECURE then
        button:SetScript("OnClick", function()
            if Bars:RunFixed(number) then return end
            -- A held pickup places directly, the stock unit-frame click idiom,
            -- so a tap-drop still lands when the host delivers the release as
            -- a click instead of a receive-drag.
            if LOCK_ACTIONBAR ~= "1" and CursorCarriesPickup() then
                PlaceAction(Bars:DisplaySlot(number))
                Bars:Refresh()
                return
            end
            if Bars:IsForever() then
                Bars:UseSlot(number)
            else
                Bars:UseSlot((Bars.page - 1) * 8 + index)
            end
        end)
    end
    button:SetScript("OnDragStart", function()
        -- Action-bar editing is allowed out of combat only on the 2.0+
        -- clients; the guard is a permanent no-op on 1.12.
        if AP:InCombat() or Bars:FixedRole(number) then return end
        -- Never self-pickup while the cursor already carries something: a
        -- touch roll would otherwise void the spell being carried.
        if LOCK_ACTIONBAR ~= "1" and not CursorCarriesPickup() then
            PickupAction(Bars:DisplaySlot(number))
        end
    end)
    button:SetScript("OnReceiveDrag", function()
        if AP:InCombat() or Bars:FixedRole(number) then return end
        if LOCK_ACTIONBAR ~= "1" then PlaceAction(Bars:DisplaySlot(number)); Bars:Refresh() end
    end)
    button:SetScript("OnEnter", function()
        -- Tooltips render above these buttons and a wide one over the lower
        -- tiles physically buries the upper tiles; never raise one while a
        -- pickup is held, and dismiss any earlier one.
        if CursorCarriesPickup() then
            GameTooltip:Hide()
            return
        end
        GameTooltip:SetOwner(this, "ANCHOR_TOP")
        local role = Bars:FixedRole(number)
        if role then
            GameTooltip:SetText(role.label)
            GameTooltip:Show()
            return
        end
        GameTooltip:SetAction(Bars:DisplaySlot(number))
    end)
    button:SetScript("OnLeave", function() GameTooltip:Hide() end)
    return button
end

function Bars:ValidateButtons(buttons)
    if not buttons then return false end
    for number = 1, BUTTON_COUNT do
        local button = buttons[number]
        if not button or not button.background or not button.icon or not button.shade
            or not button.cooldown or not button.prompt or not button.count then
            return false
        end
    end
    return true
end

local function SetRowLabels(cluster, size, gap, shown)
    if not cluster then return end
    cluster.rowLabels = cluster.rowLabels or {}
    for row = 1, 4 do
        local label = cluster.rowLabels[row]
        if not label then
            label = cluster:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
            cluster.rowLabels[row] = label
        end
        label:ClearAllPoints()
        label:SetPoint("RIGHT", cluster, "TOPLEFT", 4, -(8 + (row - 1) * (size + gap) + size / 2))
        label:SetText(FOREVER_ROW_LABELS[row])
        if shown then label:Show() else label:Hide() end
    end
end

-- Geometry for the active control layout. On the 2.0+ clients this moves
-- and resizes protected frames, so it only ever runs out of combat.
function Bars:ApplyLayout(buttons)
    local layout = AndroidPort:GetLayout()
    local halfGap = layout.star / 2
    local forever = self:IsForever()
    local size, gap, clusterSize = ForeverMetrics(layout)
    for side, cluster in pairs(self.clusterFrames) do
        if forever then
            cluster:SetWidth(clusterSize)
            cluster:SetHeight(clusterSize)
        else
            cluster:SetWidth(StarClusterSize(layout))
            cluster:SetHeight(StarClusterSize(layout))
        end
        SetRowLabels(cluster, size, gap, forever and side == -1)
    end
    for number = 1, BUTTON_COUNT do
        local button = buttons[number]
        local page, index = PageButtonIndex(number)
        local item = layoutMap[index]
        local cluster = self.clusterFrames[item.side]
        local role = self:FixedRole(number)
        button:ClearAllPoints()
        if forever then
            button:SetWidth(size)
            button:SetHeight(size)
            local x = 8 + (foreverColumn[index] - 1) * (size + gap)
            local y = -(8 + (page - 1) * (size + gap))
            if cluster then
                button:SetPoint("TOPLEFT", cluster, "TOPLEFT", x, y)
            else
                button:SetPoint("BOTTOM", UIParent, "BOTTOM",
                    item.side * halfGap + x - clusterSize / 2, layout.bottom - y)
            end
            button.prompt:SetWidth(role and math.max(34, size) or math.max(20, math.floor(size * 0.5)))
            button.prompt:SetHeight(math.max(16, math.floor(size * 0.32)))
            button.prompt.text:SetText(role and role.label or layoutMap[index].label)
        else
            button:SetWidth(layout.button)
            button:SetHeight(layout.button)
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
            button.prompt.text:SetText(layoutMap[index].label)
        end
    end
end

-- Which buttons are on screen. The 1.12 star shows the held page only; the
-- 2.0+ star keeps the eight page-1 buttons up for every page; Forever shows
-- all four sets at once. Protected frames, so out of combat only there.
function Bars:IsButtonShown(number)
    if self:IsForever() then return true end
    if AP.SECURE then return number <= 8 end
    local first = (self.page - 1) * 8 + 1
    return number >= first and number < first + 8
end

function Bars:ApplyVisibility(buttons)
    buttons = buttons or self.buttons
    for number = 1, BUTTON_COUNT do
        local button = buttons[number]
        if button then
            if self:IsButtonShown(number) then button:Show() else button:Hide() end
        end
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

function Bars:UpdateButton(number, buttons)
    local button = (buttons or self.buttons)[number]
    if not button then return end
    -- Forever dims the sets that are not held; SetAlpha is never
    -- combat-locked, so it may run mid-fight.
    if self:IsForever() then
        local page = PageButtonIndex(number)
        button:SetAlpha(page == self.page and 1 or INACTIVE_ROW_ALPHA)
    else
        button:SetAlpha(1)
    end
    local role = self:FixedRole(number)
    if role then
        button.icon:SetTexture(role.icon)
        button.icon:Show()
        button.icon:SetVertexColor(1, 1, 1)
        button.count:SetText("")
        CooldownFrame_SetTimer(button.cooldown, 0, 0, 0)
        return
    end
    local slot = self:DisplaySlot(number)
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

-- The full secure attribute set for the active layout. Fixed Forever jobs
-- carry no action (the cancel job clears the target through a macro); the
-- star layout's page-1 buttons also carry the modifier pages so a Shift or
-- Ctrl touch fires the page that is on screen.
function Bars:ApplySecureAttributes()
    if not AP.SECURE then return true end
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(function() Bars:ApplySecureAttributes() end)
        return false
    end
    local forever = self:IsForever()
    for number = 1, BUTTON_COUNT do
        local button = self.buttons[number]
        if button then
            local page, index = PageButtonIndex(number)
            local role = self:FixedRole(number)
            if role and role.command == "TOGGLEGAMEMENU" then
                button:SetAttribute("type", "macro")
                button:SetAttribute("macrotext", "/cleartarget")
            elseif role then
                button:SetAttribute("type", nil)
                button:SetAttribute("macrotext", nil)
            else
                button:SetAttribute("type", "action")
                button:SetAttribute("macrotext", nil)
            end
            button:SetAttribute("action", self:ActionSlotForNumber(number))
            for modifierPage = 2, 4 do
                local value = nil
                if page == 1 and not forever then value = PageSlot(modifierPage, index) end
                button:SetAttribute(PAGE_ATTRIBUTE_PREFIX[modifierPage] .. "action*", value)
            end
        end
    end
    return true
end

-- The page-1 buttons follow the stance/stealth/possess bonus bar. Secure
-- attributes may only be written out of combat, so a mid-combat shapeshift
-- re-points them the moment combat ends; on 1.12 the stock UseSlot path
-- already reads the bonus offset live and nothing runs here. The 3.3.5a
-- client also gets a secure state handler (EnsureBonusDriver) that re-points
-- them inside combat.
function Bars:ApplyBonusBarAttributes()
    if not AP.SECURE then return true end
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(function() Bars:ApplyBonusBarAttributes() end)
        return false
    end
    for index = 1, 8 do
        local button = self.buttons[index]
        if button then button:SetAttribute("action", BonusSlot(index)) end
    end
    return true
end

-- 3.3.5a only: SecureHandlerStateTemplate runs a restricted snippet on every
-- bonus-bar change, combat included. 2.4.3 has no such template and keeps
-- the out-of-combat re-point above.
function Bars:EnsureBonusDriver()
    if not AP.SECURE or self.bonusDriver then return true end
    if type(SecureHandlerSetFrameRef) ~= "function" or type(RegisterStateDriver) ~= "function" then
        return false
    end
    if AndroidPort:InCombat() then return false end
    local ok = pcall(function()
        local header = getglobal("AndroidPortBonusDriver") or
            CreateFrame("Frame", "AndroidPortBonusDriver", UIParent, "SecureHandlerStateTemplate")
        for index = 1, 8 do
            SecureHandlerSetFrameRef(header, "b" .. index, self.buttons[index])
        end
        header:SetAttribute("_onstate-bonus", [[
            local offset = tonumber(newstate) or 0
            for index = 1, 8 do
                local button = self:GetFrameRef("b" .. index)
                if button then
                    if offset > 0 then
                        button:SetAttribute("action", 60 + offset * 12 + index)
                    else
                        button:SetAttribute("action", index)
                    end
                end
            end
        ]])
        RegisterStateDriver(header, "bonus",
            "[bonusbar:5] 5; [bonusbar:4] 4; [bonusbar:3] 3; [bonusbar:2] 2; [bonusbar:1] 1; 0")
        self.bonusDriver = header
    end)
    return ok
end

-- Owner frame for the controller's override bindings. Owner-scoped
-- bindings never touch the player's saved binding set and vanish with the
-- frame on reload.
function Bars:BindingOwner()
    local owner = getglobal("AndroidPortBindingOwner")
    if not owner and AP.SECURE then
        owner = CreateFrame("Frame", "AndroidPortBindingOwner", UIParent)
        owner:Hide()
    end
    return owner
end

-- Bind every controller chord straight to the secure button owning that
-- (page, position) pair: the key press arrives as a hardware click on a
-- secure frame, so the cast works in combat with no add-on code involved.
-- Forever's fixed base-set keys bind to their stock or add-on command.
function Bars:ApplyOverrideBindings()
    if not AP.SECURE then return true end
    if self.suspended then return true end
    -- With the bars module off the buttons do not exist and the stock keys
    -- already drive the visible stock bars.
    if not self.ready then return true end
    local owner = self:BindingOwner()
    if not owner then return false end
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(function() Bars:ApplyOverrideBindings() end)
        return false
    end
    ClearOverrideBindings(owner)
    local keys = AndroidPort.keys
    for page = 1, 4 do
        local modifier = PAGE_KEYS[page]
        for index = 1, 8 do
            local number = (page - 1) * 8 + index
            local role = self:FixedRole(number)
            if role then
                SetOverrideBinding(owner, true, modifier .. keys[index], role.command)
            else
                SetOverrideBindingClick(owner, true, modifier .. keys[index], "AndroidPortButton" .. number, "LeftButton")
            end
        end
    end
    return true
end

-- While the radial menu or Move UI is open the face/D-pad keys must reach
-- the ordinary add-on handlers instead of casting; the plain keys are
-- re-bound to the stock binding commands, and the modifier chords keep
-- casting exactly as they do on 1.12.
function Bars:SuspendOverrideKeys()
    if not AP.SECURE or self.suspended then return end
    local owner = self:BindingOwner()
    if not owner then return end
    if AndroidPort:InCombat() then return end
    ClearOverrideBindings(owner)
    local keys = AndroidPort.keys
    for index = 1, 8 do
        SetOverrideBinding(owner, true, keys[index], AndroidPort:BindingCommand(index))
    end
    self.suspended = true
end

function Bars:ResumeOverrideKeys()
    if not AP.SECURE or not self.suspended then return end
    local owner = self:BindingOwner()
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(function()
            Bars.suspended = nil
            Bars:ApplyOverrideBindings()
        end)
        return
    end
    self.suspended = nil
    -- Clear before re-applying: with the bars module off the apply path
    -- stops early, and the plain-key overrides from the suspension must
    -- not outlive it.
    if owner and ClearOverrideBindings then ClearOverrideBindings(owner) end
    self:ApplyOverrideBindings()
end

function Bars:ClearOverrideBindings()
    if not AP.SECURE then return end
    local owner = self:BindingOwner()
    if owner and ClearOverrideBindings then ClearOverrideBindings(owner) end
    self.suspended = nil
end

-- Switch between the star and Forever layouts. Everything it touches is
-- protected on the 2.0+ clients, so a switch asked for in combat lands the
-- moment combat ends.
function Bars:ApplyControlLayout()
    if not self.ready then return false end
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(function() Bars:ApplyControlLayout() end)
        return false
    end
    self:ApplyLayout(self.buttons)
    self:ApplySecureAttributes()
    self:ApplyVisibility(self.buttons)
    self:ApplyOverrideBindings()
    self:Refresh()
    return true
end

function Bars:DeactivateWatcher()
    local watcher = self.watcher or getglobal("AndroidPortBarWatcher")
    if not watcher then return end
    for _, eventName in ipairs(watcherEvents) do pcall(watcher.UnregisterEvent, watcher, eventName) end
    watcher:SetScript("OnEvent", nil)
    watcher:SetScript("OnUpdate", nil)
end

function Bars:RestoreStockBars()
    if not self.stockHidden then return end
    local restore = function()
        if not Bars.stockHidden or not Bars.stockState then return end
        for frame, state in pairs(Bars.stockState) do
            frame:SetAlpha(state.alpha or 1)
            if state.shown then frame:Show() else frame:Hide() end
        end
        Bars.stockState = nil
        Bars.stockHidden = false
    end
    if AndroidPort:InCombat() then
        AndroidPort:DeferCombat(restore)
        return
    end
    restore()
end

function Bars:FailSafe(buttons)
    self.ready = false
    self:DeactivateWatcher()
    local candidates = buttons or self.buttons or {}
    local hide = function()
        for number = 1, BUTTON_COUNT do
            local button = candidates[number] or getglobal("AndroidPortButton" .. number)
            if button then button:Hide() end
        end
    end
    -- The secure buttons are protected: hiding them mid-combat would be
    -- blocked, so the hide waits for combat to end.
    if AP.SECURE and AndroidPort:InCombat() then
        AndroidPort:DeferCombat(hide)
    else
        hide()
    end
    self.buttons = {}
    self:RestoreStockBars()
    if AP.SECURE then self:ClearOverrideBindings() end
end

function Bars:Refresh()
    if not self.ready or not self:ValidateButtons(self.buttons) then
        self:FailSafe(self.buttons)
        return false
    end
    local ok = pcall(function()
        for number = 1, BUTTON_COUNT do self:UpdateButton(number, self.buttons) end
        -- The 2.0+ button set never changes with the page; only the 1.12
        -- star swaps which buttons are up.
        if not AP.SECURE then self:ApplyVisibility(self.buttons) end
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

-- The stock bars hidden are the ones the controller stars replace. The
-- 2.0+ clients add the two right-hand bars, and combat locks every one of
-- them: the hide defers itself until combat ends instead of erroring.
function Bars:HideStockBars()
    if self.stockHidden then return true end
    local frames
    if AP.SECURE then
        frames = {
            MainMenuBar, BonusActionBarFrame,
            MultiBarBottomLeft, MultiBarBottomRight,
            MultiBarLeft, MultiBarRight,
        }
    else
        frames = { MainMenuBar, BonusActionBarFrame, MultiBarBottomLeft, MultiBarBottomRight }
    end
    local hide = function()
        if Bars.stockHidden or not Bars.ready then return true end
        local stockState = {}
        local captured = pcall(function()
            for _, frame in ipairs(frames) do
                if frame then stockState[frame] = { shown = frame:IsShown(), alpha = frame:GetAlpha() } end
            end
        end)
        if not captured then return false end
        Bars.stockState = stockState
        Bars.stockHidden = true
        local hidden = pcall(function()
            for frame, _ in pairs(stockState) do
                frame:Hide()
                frame:SetAlpha(0)
            end
        end)
        if not hidden then
            Bars.stockState = nil
            Bars.stockHidden = false
            return false
        end
        return true
    end
    if AndroidPort:InCombat() then
        -- Not an error: the stars already carry the actions, the stock bars
        -- step aside the moment combat allows.
        AndroidPort:DeferCombat(hide)
        return true
    end
    return hide()
end

function Bars:Initialize()
    if self.ready and self:ValidateButtons(self.buttons) then return true end
    self:FailSafe(self.buttons)

    local staged = {}
    local watcher
    local ok = pcall(function()
        for number = 1, BUTTON_COUNT do staged[number] = self:CreateButton(number) end
        self.buttons = staged
        self:ApplyLayout(staged)
        for number = 1, BUTTON_COUNT do self:UpdateButton(number, staged) end

        watcher = getglobal("AndroidPortBarWatcher")
        if not watcher then watcher = CreateFrame("Frame", "AndroidPortBarWatcher", UIParent) end
        watcher.elapsed = 0
        watcher:SetScript("OnEvent", function()
            if not Bars.ready then return end
            if event == "UPDATE_BONUS_ACTIONBAR" then
                Bars:ApplyBonusBarAttributes()
            end
            Bars:Refresh()
        end)
        watcher:SetScript("OnUpdate", function()
            if not Bars.ready then return end
            this.elapsed = this.elapsed + arg1
            if this.elapsed >= 0.08 then
                this.elapsed = 0
                Bars:UpdatePage()
            end
        end)
        -- PLAYER_AURAS_CHANGED is gone from the 3.3.5a client; a missing
        -- event name must not abort the watcher on any client.
        for _, eventName in ipairs(watcherEvents) do pcall(watcher.RegisterEvent, watcher, eventName) end
        if self:IsForever() or AP.SECURE then
            self:ApplyVisibility(staged)
        else
            local first = (self.page - 1) * 8 + 1
            for number = first, first + 7 do staged[number]:Show() end
        end
    end)
    if not ok or not self:ValidateButtons(staged) or not watcher then
        self.watcher = watcher
        self:FailSafe(staged)
        return false
    end

    self.buttons = staged
    self.watcher = watcher
    self.ready = true
    if AP.SECURE then
        self:ApplySecureAttributes()
        self:ApplyBonusBarAttributes()
        self:EnsureBonusDriver()
        self:ApplyOverrideBindings()
    end
    return true
end
