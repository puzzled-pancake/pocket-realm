VanillaConsolePort.Radial = VanillaConsolePort.Radial or {}
local Radial = VanillaConsolePort.Radial
Radial.buttons = Radial.buttons or {}
Radial.ready = Radial.ready or false

local items = {
    { name = "Character", icon = "Interface\\Icons\\INV_Shirt_White_01", action = function() ToggleCharacter("PaperDollFrame") end },
    { name = "Inventory", icon = "Interface\\Icons\\INV_Misc_Bag_08", action = function()
        if ContainerFrame1 and ContainerFrame1:IsVisible() then CloseAllBags() else OpenAllBags() end
    end },
    { name = "Spellbook", icon = "Interface\\Icons\\INV_Misc_Book_09", action = function() ToggleSpellBook(BOOKTYPE_SPELL) end },
    { name = "Talents", icon = "Interface\\Icons\\Ability_Marksmanship", action = function() ToggleTalentFrame() end },
    { name = "Quest Log", icon = "Interface\\Icons\\INV_Misc_Note_01", action = function() ToggleQuestLog() end },
    { name = "World Map", icon = "Interface\\Icons\\INV_Misc_Map_01", action = function() ToggleWorldMap() end },
    { name = "Social", icon = "Interface\\Icons\\INV_Letter_02", action = function() ToggleFriendsFrame(1) end },
    -- Start already opens the normal game menu. Keep the scarce eighth radial
    -- slot for the setup action that otherwise required an awkward chord.
    { name = "Move UI", icon = "Interface\\Icons\\INV_Misc_Gear_01", action = function()
        VanillaConsolePort:ToggleMoveUI()
    end },
}

function Radial:CreateButton(index, item, radius, size, frame)
    local angle = math.rad(90 - (index - 1) * (360 / table.getn(items)))
    local name = "VanillaConsolePortRadial" .. index
    local button = getglobal(name)
    if not button then button = CreateFrame("Button", name, frame) end
    button:Hide()
    button:SetWidth(size)
    button:SetHeight(size)
    button:ClearAllPoints()
    button:SetPoint("CENTER", frame, "CENTER", radius * math.cos(angle), radius * math.sin(angle))

    local bg = button.background
    if not bg then
        bg = button:CreateTexture(nil, "BACKGROUND")
        button.background = bg
    end
    bg:SetTexture("Interface\\CHARACTERFRAME\\TempPortraitAlphaMask")
    bg:SetAllPoints(button)
    bg:SetVertexColor(0.05, 0.08, 0.12, 0.98)
    local icon = button.icon
    if not icon then
        icon = button:CreateTexture(nil, "ARTWORK")
        button.icon = icon
    end
    icon:SetTexture(item.icon)
    icon:ClearAllPoints()
    icon:SetPoint("TOPLEFT", button, "TOPLEFT", 5, -5)
    icon:SetPoint("BOTTOMRIGHT", button, "BOTTOMRIGHT", -5, 5)
    local text = button.text
    if not text then
        text = button:CreateFontString(nil, "OVERLAY", "GameFontNormal")
        button.text = text
    end
    text:ClearAllPoints()
    text:SetPoint("TOP", button, "BOTTOM", 0, -4)
    text:SetText(index .. " - " .. item.name)

    button:SetScript("OnClick", function() Radial:Activate(index) end)
    button:SetScript("OnEnter", function() bg:SetVertexColor(0.12, 0.45, 0.75, 1) end)
    button:SetScript("OnLeave", function() bg:SetVertexColor(0.05, 0.08, 0.12, 0.98) end)
    return button
end

function Radial:Validate(frame, overlay, buttons)
    if not frame or not frame.help or not overlay or not overlay.shade or not buttons then return false end
    for index = 1, table.getn(items) do
        local button = buttons[index]
        if not button or not button.background or not button.icon or not button.text then return false end
    end
    return true
end

function Radial:FailSafe(frame, overlay, buttons)
    self.ready = false
    if overlay then overlay:Hide() end
    if frame then frame:Hide() end
    local candidates = buttons or self.buttons or {}
    for index = 1, table.getn(items) do
        local button = candidates[index] or getglobal("VanillaConsolePortRadial" .. index)
        if button then button:Hide() end
    end
    self.frame = nil
    self.overlay = nil
    self.buttons = {}
end

function Radial:AddSpecialFrame()
    for _, name in ipairs(UISpecialFrames) do
        if name == "VanillaConsolePortRadialMenu" then return end
    end
    tinsert(UISpecialFrames, "VanillaConsolePortRadialMenu")
end

function Radial:Initialize()
    if self.ready and self:Validate(self.frame, self.overlay, self.buttons) then return true end
    self:FailSafe(self.frame, self.overlay, self.buttons)

    local layout = VanillaConsolePort:GetLayout()
    local frame
    local overlay
    local buttons = {}
    local ok = pcall(function()
        frame = getglobal("VanillaConsolePortRadialMenu")
        if not frame then frame = CreateFrame("Frame", "VanillaConsolePortRadialMenu", UIParent) end
        frame:SetWidth(layout.radial)
        frame:SetHeight(layout.radial)
        frame:ClearAllPoints()
        frame:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
        frame:SetFrameStrata("FULLSCREEN_DIALOG")
        frame:Hide()

        local help = frame.help
        if not help then
            help = frame:CreateFontString(nil, "OVERLAY", "GameFontNormal")
            frame.help = help
        end
        help:ClearAllPoints()
        help:SetPoint("CENTER", frame, "CENTER", 0, 0)
        help:SetText("Face 1-4 / D-pad 5-8: choose\nSelect: close")

        overlay = getglobal("VanillaConsolePortRadialShade")
        if not overlay then overlay = CreateFrame("Button", "VanillaConsolePortRadialShade", UIParent) end
        overlay:SetAllPoints(UIParent)
        overlay:SetFrameStrata("FULLSCREEN")
        local shade = overlay.shade
        if not shade then
            shade = overlay:CreateTexture(nil, "BACKGROUND")
            overlay.shade = shade
        end
        shade:SetAllPoints(overlay)
        shade:SetTexture(0, 0, 0)
        shade:SetAlpha(0.72)
        overlay:SetScript("OnClick", function() Radial:Hide() end)
        overlay:Hide()
        frame:SetScript("OnHide", function()
            if Radial.overlay and Radial.overlay:IsVisible() then Radial.overlay:Hide() end
        end)

        local radius = layout.radial * 0.34
        for index, item in ipairs(items) do
            buttons[index] = self:CreateButton(index, item, radius, layout.radialButton, frame)
        end
        for index = 1, table.getn(items) do buttons[index]:Show() end
        self:AddSpecialFrame()
    end)
    if not ok or not self:Validate(frame, overlay, buttons) then
        self:FailSafe(frame, overlay, buttons)
        return false
    end

    self.frame = frame
    self.overlay = overlay
    self.buttons = buttons
    self.ready = true
    return true
end

function Radial:Show()
    if not self.ready then return end
    self.overlay:Show()
    self.frame:Show()
end

function Radial:Hide()
    if self.overlay then self.overlay:Hide() end
    if self.frame then self.frame:Hide() end
end

function Radial:Activate(index)
    if not self.ready or not self.frame or not self.frame:IsVisible() then return false end
    local item = items[index]
    if not item then return false end
    self:Hide()
    item.action()
    return true
end

function Radial:Toggle()
    if not self.ready and not self:Initialize() then return end
    if self.frame:IsVisible() then self:Hide() else self:Show() end
end
