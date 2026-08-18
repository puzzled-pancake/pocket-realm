-- Android Port bags: a OneBag-style all-in-one container that replaces the
-- stock containers while this module is ready, opened from the radial menu
-- or the usual bag keys. The header carries real bag slots (drag a bag onto
-- one to equip it, drag a slot off to lift the bag) plus the all and key
-- views. Sell junk sells gray quality items only, one per tick, re-verified
-- immediately before every sale, and never scans the keyring. Clean-room
-- Interface 11200 source.
AndroidPort.Bags = AndroidPort.Bags or {}
local Bags = AndroidPort.Bags

Bags.ready = Bags.ready or false
Bags.mode = Bags.mode or "all"
Bags.selling = false
Bags.lastStep = 0
Bags.soldCount = 0
Bags.sellMoneyStart = 0
Bags.refreshAt = 0
Bags.reportAt = 0
Bags.hookedStock = false
Bags.buttonCount = 0

local KEYRING_ID = -2
local SELL_TICK = 0.2
local REFRESH_TICK = 0.2
local REPORT_DELAY = 0.5

local stockBags = { 0, 1, 2, 3, 4 }
local allBags = { 0, 1, 2, 3, 4, -2 }
local equipBags = { 1, 2, 3, 4 }

local function BagSize(bag)
    if bag == KEYRING_ID then
        if GetKeyRingSize then return GetKeyRingSize() end
        return 0
    end
    return GetContainerNumSlots(bag)
end

local function MoneyText(copper)
    if type(copper) ~= "number" or copper < 0 then copper = 0 end
    local gold = math.floor(copper / 10000)
    local silver = math.mod(math.floor(copper / 100), 100)
    local rest = math.mod(copper, 100)
    if gold > 0 then return gold .. "g " .. silver .. "s " .. rest .. "c" end
    if silver > 0 then return silver .. "s " .. rest .. "c" end
    return rest .. "c"
end

-- Inventory slot for an equipped bag. ContainerIDToInventoryID is the stock
-- mapping every reference addon uses; the fallback covers stripped clients.
local function BagInvSlot(bag)
    if ContainerIDToInventoryID then return ContainerIDToInventoryID(bag) end
    if GetInventorySlotInfo then
        local id = GetInventorySlotInfo("Bag" .. (bag - 1) .. "Slot")
        if id then return id end
    end
    return 19 + bag
end

local function BagEmptyTexture(bag)
    if GetInventorySlotInfo then
        local _, texture = GetInventorySlotInfo("Bag" .. (bag - 1) .. "Slot")
        if texture then return texture end
    end
    return "Interface\\Paperdoll\\UI-PaperDoll-Slot-Bag"
end

-- The container exists from file load so the frame mover's curated
-- registry adopts it on its first Initialize pass, exactly like the action
-- clusters. Errors here leave the frame hidden and the module not ready.
pcall(function()
    Bags:EnsureFrame()
end)

function Bags:EnsureFrame()
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame then
        frame = CreateFrame("Frame", "AndroidPortBagsFrame", UIParent)
        frame:SetFrameStrata("MEDIUM")
        frame:EnableMouse(true)
        frame:SetMovable(1)
        local layout = AndroidPort:GetLayout()
        frame:ClearAllPoints()
        frame:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, layout.bottom + layout.button + 150)
        frame.bags = {}
        local money = frame:CreateFontString("AndroidPortBagsMoney", "OVERLAY", "GameFontNormalSmall")
        money:SetPoint("BOTTOMRIGHT", frame, "BOTTOMRIGHT", -10, 5)
        money:SetTextColor(1, 1, 1, 1)
        money:SetText("")
        frame.money = money
        local listed = false
        for _, name in ipairs(UISpecialFrames) do
            if name == "AndroidPortBagsFrame" then listed = true end
        end
        if not listed then tinsert(UISpecialFrames, "AndroidPortBagsFrame") end
    end
    frame:Hide()
    return frame
end

function Bags:ModeBags()
    if self.mode == "key" then return { KEYRING_ID } end
    if self.mode == "all" then return allBags end
    return { tonumber(self.mode) or 0 }
end

function Bags:EnsureHolder(frame, bag)
    local name = "AndroidPortBagsBag" .. (bag == KEYRING_ID and "Key" or tostring(bag))
    local holder = frame.bags[bag]
    if holder and holder:GetName() == name then return holder end
    holder = getglobal(name)
    if not holder then
        holder = CreateFrame("Frame", name, frame)
        holder:SetWidth(1)
        holder:SetHeight(1)
        holder:ClearAllPoints()
        holder:SetPoint("TOPLEFT", frame, "TOPLEFT", 0, 0)
    end
    holder:SetID(bag)
    frame.bags[bag] = holder
    return holder
end

function Bags:AcquireButton(index, holder)
    local name = "AndroidPortBagsItem" .. index
    local button = getglobal(name)
    if not button then
        -- The stock template plus a parent whose ID is the bag gives the
        -- full stock item behaviour: pickup, use, merchant sell, dressing,
        -- chat links, stack splits, lock feedback and the keyring tooltips.
        button = CreateFrame("Button", name, holder, "ContainerFrameItemButtonTemplate")
        if index > self.buttonCount then self.buttonCount = index end
    elseif button:GetParent() ~= holder then
        button:SetParent(holder)
    end
    return button
end

-- Rebuild the grid for the current mode. The frame keeps its BOTTOM anchor,
-- so a changed row count grows upward and the journal keeps owning position.
function Bags:Layout()
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame then return false end
    local layout = AndroidPort:GetLayout()
    local slotSize = math.floor(layout.button * 0.7 + 0.5)
    -- The header scales with the layout profile so the controls stay
    -- finger-sized on small screens, not fixed 22px widgets.
    local headerSize = math.floor(layout.button * 0.66 + 0.5)
    local columns = 8
    local db = AndroidPortDB and AndroidPortDB.bags
    if db and db.columns and db.columns >= 4 and db.columns <= 12 then columns = db.columns end

    local used = 0
    local column = 0
    local row = 0
    local bagsShown = self:ModeBags()
    for index, bag in ipairs(bagsShown) do
        if index > 1 and self.mode == "all" then
            -- Bag break: start a fresh row between bag groups.
            if column ~= 0 then
                column = 0
                row = row + 1
            end
        end
        local holder = self:EnsureHolder(frame, bag)
        for slot = 1, BagSize(bag) do
            used = used + 1
            local button = self:AcquireButton(used, holder)
            button:SetID(slot)
            button:ClearAllPoints()
            button:SetPoint(
                "TOPLEFT", frame, "TOPLEFT",
                10 + column * (slotSize + 4),
                -(headerSize + 14) - row * (slotSize + 4)
            )
            button:SetWidth(slotSize)
            button:SetHeight(slotSize)
            self:UpdateSlot(button, bag, slot)
            button:Show()
            column = column + 1
            if column >= columns then
                column = 0
                row = row + 1
            end
        end
    end
    -- Retire pool buttons beyond the current layout.
    for index = used + 1, self.buttonCount do
        local button = getglobal("AndroidPortBagsItem" .. index)
        if button then button:Hide() end
    end
    local rows = row + (column > 0 and 1 or 0)
    -- The header chain (all, four real bag slots, key, sell and close) can
    -- outrun a narrow grid, so the frame never shrinks below it.
    local gridWidth = columns * (slotSize + 4) + 16
    local minWidth = headerSize * 11
    if gridWidth < minWidth then gridWidth = minWidth end
    frame:SetWidth(gridWidth)
    frame:SetHeight(headerSize + 16 + rows * (slotSize + 4) + 22)
    self:UpdateHeader()
    self:UpdateMoney()
    return true
end

function Bags:UpdateSlot(button, bag, slot)
    local texture, count, locked, quality, readable = GetContainerItemInfo(bag, slot)
    if SetItemButtonTexture then SetItemButtonTexture(button, texture) end
    if SetItemButtonCount then SetItemButtonCount(button, count) end
    if SetItemButtonDesaturated then SetItemButtonDesaturated(button, locked, 0.5, 0.5, 0.5) end
    if texture and ContainerFrame_UpdateCooldown then
        ContainerFrame_UpdateCooldown(bag, button)
        button.hasItem = 1
    else
        local cooldown = getglobal(button:GetName() .. "Cooldown")
        if cooldown then cooldown:Hide() end
        button.hasItem = nil
    end
    button.readable = readable
    local normal = getglobal(button:GetName() .. "NormalTexture")
    if normal then
        if bag == KEYRING_ID then
            -- Keyring slots read as keys: warm gold border, and an empty
            -- slot shows the keyring art instead of a blank square.
            normal:SetVertexColor(0.85, 0.68, 0.35, 1)
        else
            local colors = ITEM_QUALITY_COLORS
            if quality and quality > 0 and colors and colors[quality] then
                normal:SetVertexColor(colors[quality].r, colors[quality].g, colors[quality].b, 1)
            else
                normal:SetVertexColor(1, 1, 1, 1)
            end
        end
    end
    if bag == KEYRING_ID and not texture and SetItemButtonTexture then
        SetItemButtonTexture(button, "Interface\\ContainerFrame\\KeyRing-Bag-Icon")
    end
end

function Bags:RefreshSlots()
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame or not frame:IsVisible() then return end
    -- Slot buttons resolve through their own holder parent and ids, so no
    -- layout index is ever assumed and bag sizes may change freely.
    for index = 1, self.buttonCount do
        local button = getglobal("AndroidPortBagsItem" .. index)
        if button and button:IsVisible() then
            local holder = button:GetParent()
            if holder then
                self:UpdateSlot(button, holder:GetID(), button:GetID())
            end
        end
    end
end

function Bags:UpdateMoney()
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame or not frame.money then return end
    frame.money:SetText(MoneyText(GetMoney()))
end

function Bags:HeaderButton(name, text, anchorName, width)
    local frame = getglobal("AndroidPortBagsFrame")
    local layout = AndroidPort:GetLayout()
    local headerSize = math.floor(layout.button * 0.66 + 0.5)
    local button = getglobal(name)
    if not button then
        button = CreateFrame("Button", name, frame)
        local bg = button:CreateTexture(nil, "BACKGROUND")
        bg:SetTexture("Interface\\ChatFrame\\ChatFrameBackground")
        bg:SetVertexColor(0.10, 0.16, 0.24, 0.95)
        bg:SetAllPoints(button)
        button.background = bg
        local label = button:CreateFontString(nil, "OVERLAY", "GameFontNormal")
        label:SetAllPoints(button)
        button.label = label
        button:RegisterForClicks("LeftButtonUp")
    end
    button:SetWidth(width)
    button:SetHeight(headerSize)
    button.label:SetText(text)
    button:ClearAllPoints()
    local anchor = getglobal(anchorName)
    if anchor then
        button:SetPoint("LEFT", anchor, "RIGHT", 4, 0)
    else
        button:SetPoint("TOPLEFT", frame, "TOPLEFT", 10, -6)
    end
    button:Show()
    return button
end

-- Real bag slots: ItemButtonTemplate buttons whose id is the inventory slot
-- of the equipped bag, exactly the stock action-bar idiom. A click with a
-- bag on the cursor equips it (PutItemInBag), a drag lifts the equipped bag
-- (PickupBagFromSlot), a plain click toggles that bag's view.
function Bags:BagSlotButton(bag, anchorName)
    local frame = getglobal("AndroidPortBagsFrame")
    local layout = AndroidPort:GetLayout()
    local headerSize = math.floor(layout.button * 0.66 + 0.5)
    local name = "AndroidPortBagsSlot" .. bag
    local button = getglobal(name)
    if not button then
        button = CreateFrame("Button", name, frame, "ItemButtonTemplate")
        button:RegisterForClicks("LeftButtonUp")
        button:RegisterForDrag("LeftButton")
        local invSlot = BagInvSlot(bag)
        button:SetID(invSlot)
        button.bagIndex = bag
        local icon = getglobal(name .. "IconTexture")
        if icon then
            icon:ClearAllPoints()
            icon:SetPoint("TOPLEFT", button, "TOPLEFT", 3, -3)
            icon:SetPoint("BOTTOMRIGHT", button, "BOTTOMRIGHT", -3, 3)
        end
        button:SetScript("OnClick", function()
            -- Stock BagSlotButton_OnClick: PutItemInBag reports whether the
            -- cursor carried something; only a plain click toggles the view.
            local hadItem = PutItemInBag(this:GetID())
            if not hadItem then
                ToggleBag(this.bagIndex)
            end
        end)
        button:SetScript("OnDragStart", function()
            PickupBagFromSlot(this:GetID())
        end)
        button:SetScript("OnReceiveDrag", function()
            local hadItem = PutItemInBag(this:GetID())
            if not hadItem then
                ToggleBag(this.bagIndex)
            end
        end)
        button:SetScript("OnEnter", function()
            GameTooltip:SetOwner(this, "ANCHOR_LEFT")
            if not GameTooltip:SetInventoryItem("player", this:GetID()) then
                GameTooltip:SetText("Equip a bag", 1.0, 1.0, 1.0, 1.0)
            end
        end)
        button:SetScript("OnLeave", function() GameTooltip:Hide() end)
    end
    button:SetWidth(headerSize)
    button:SetHeight(headerSize)
    button:ClearAllPoints()
    local anchor = getglobal(anchorName)
    if anchor then
        button:SetPoint("LEFT", anchor, "RIGHT", 4, 0)
    else
        button:SetPoint("TOPLEFT", frame, "TOPLEFT", 10, -6)
    end
    self:UpdateBagSlotButton(bag)
    button:Show()
    return button
end

function Bags:UpdateBagSlotButton(bag)
    local button = getglobal("AndroidPortBagsSlot" .. bag)
    if not button then return end
    local texture = GetInventoryItemTexture("player", button:GetID())
    local icon = getglobal(button:GetName() .. "IconTexture")
    if icon then
        if texture then
            icon:SetTexture(texture)
        else
            icon:SetTexture(BagEmptyTexture(bag))
        end
    end
    local locked = IsInventoryItemLocked and IsInventoryItemLocked(button:GetID())
    if SetItemButtonDesaturated then SetItemButtonDesaturated(button, locked, 0.5, 0.5, 0.5) end
    local normal = getglobal(button:GetName() .. "NormalTexture")
    if normal then
        if self.mode == tostring(bag) then
            normal:SetVertexColor(0.35, 0.85, 0.45, 1)
        else
            normal:SetVertexColor(1, 1, 1, 1)
        end
    end
end

function Bags:UpdateHeader()
    if not getglobal("AndroidPortBagsFrame") then return end
    local layout = AndroidPort:GetLayout()
    local headerSize = math.floor(layout.button * 0.66 + 0.5)
    local all = self:HeaderButton("AndroidPortBagsTabAll", "All", nil, headerSize + 16)
    if all.background then
        if self.mode == "all" then
            all.background:SetVertexColor(0.22, 0.40, 0.30, 0.95)
        else
            all.background:SetVertexColor(0.10, 0.16, 0.24, 0.95)
        end
    end
    all:SetScript("OnClick", function()
        -- Cursor semantics from the removed dock: an item on the cursor
        -- taps into the backpack instead of switching views.
        if CursorHasItem and CursorHasItem() then
            PutItemInBackpack()
        else
            Bags:SetMode("all")
        end
    end)

    local previous = all
    for _, bag in ipairs(equipBags) do
        local slot = self:BagSlotButton(bag, previous:GetName())
        previous = slot
    end

    local key = self:HeaderButton("AndroidPortBagsTabKey", "Key", previous:GetName(), headerSize + 8)
    if key.background then
        if self.mode == "key" then
            key.background:SetVertexColor(0.22, 0.40, 0.30, 0.95)
        else
            key.background:SetVertexColor(0.10, 0.16, 0.24, 0.95)
        end
    end
    key:SetScript("OnClick", function()
        -- Cursor semantics from the removed dock: a key on the cursor goes
        -- into the keyring instead of switching views.
        if CursorHasItem and CursorHasItem() then
            PutKeyInKeyRing()
        else
            Bags:SetMode("key")
        end
    end)
    previous = key

    local sell = self:HeaderButton("AndroidPortBagsSell", self.selling and "Stop" or "Sell Junk", previous:GetName(), headerSize * 2 + 14)
    sell:SetScript("OnClick", function() Bags:SellJunk() end)
    local merchantOpen = MerchantFrame and MerchantFrame:IsVisible() and MerchantFrame.selectedTab ~= 2
    if sell.label then
        local r, g, b
        if self.selling then
            r, g, b = 0.35, 0.85, 0.35
        elseif merchantOpen then
            r, g, b = 1, 0.82, 0
        else
            r, g, b = 0.45, 0.45, 0.45
        end
        sell.label:SetTextColor(r, g, b)
    end
    previous = sell

    local close = self:HeaderButton("AndroidPortBagsClose", "X", previous:GetName(), headerSize - 8)
    close:SetScript("OnClick", function()
        local frame = getglobal("AndroidPortBagsFrame")
        if frame then frame:Hide() end
    end)
end

function Bags:SetMode(mode)
    self.mode = mode
    local frame = getglobal("AndroidPortBagsFrame")
    if frame and frame:IsVisible() then
        self:Layout()
    end
end

function Bags:Toggle(mode)
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame then return false end
    if frame:IsVisible() and (not mode or self.mode == mode) then
        frame:Hide()
        return true
    end
    if mode then self.mode = mode end
    self:Layout()
    frame:Show()
    return true
end

-- While the module is ready the stock container entry points route here, so
-- vendor and mailbox auto-open land on the all-in-one window and vendor
-- close closes it again, exactly like every reference bag addon. Bank
-- container ids pass through to the captured originals.
function Bags:InstallStockHooks()
    if self.hookedStock then return true end
    if type(OpenBag) ~= "function" or type(OpenBackpack) ~= "function" or
        type(OpenAllBags) ~= "function" or type(ToggleBag) ~= "function" or
        type(ToggleBackpack) ~= "function" or type(ToggleKeyRing) ~= "function" or
        type(CloseBag) ~= "function" or type(CloseBackpack) ~= "function" or
        type(CloseAllBags) ~= "function" then
        return false
    end
    self.stockOpenBag = OpenBag
    self.stockOpenBackpack = OpenBackpack
    self.stockOpenAllBags = OpenAllBags
    self.stockToggleBag = ToggleBag
    self.stockToggleBackpack = ToggleBackpack
    self.stockToggleKeyRing = ToggleKeyRing
    self.stockCloseBag = CloseBag
    self.stockCloseBackpack = CloseBackpack
    self.stockCloseAllBags = CloseAllBags
    self.stockIsBagOpen = IsBagOpen
    local function reroutes(id)
        return id == KEYRING_ID or (id >= 0 and id <= 4)
    end
    -- The stock open paths refuse while dead through CanOpenPanels; the
    -- reroutes keep exactly that contract.
    local function mayOpen()
        if CanOpenPanels then return CanOpenPanels() end
        return true
    end
    local function frame()
        return getglobal("AndroidPortBagsFrame")
    end
    OpenBag = function(id)
        if Bags.ready and reroutes(id) then
            if mayOpen() and BagSize(id) > 0 then
                Bags.mode = tostring(id)
                Bags:Layout()
                local container = frame()
                if container then container:Show() end
            end
            return
        end
        Bags.stockOpenBag(id)
    end
    OpenBackpack = function()
        if Bags.ready then
            local container = frame()
            if container and not container:IsVisible() and mayOpen() then
                Bags.mode = "all"
                Bags:Layout()
                container:Show()
            end
            return
        end
        Bags.stockOpenBackpack()
    end
    OpenAllBags = function(forceOpen)
        local container = frame()
        if Bags.ready and container then
            local visible = container:IsVisible()
            if visible and not forceOpen then
                container:Hide()
            elseif not visible then
                Bags.mode = "all"
                Bags:Layout()
                container:Show()
            end
            return
        end
        Bags.stockOpenAllBags(forceOpen)
    end
    ToggleBag = function(id)
        if Bags.ready and reroutes(id) then
            local container = frame()
            local mode = id == KEYRING_ID and "key" or tostring(id)
            if container and container:IsVisible() and Bags.mode == mode then
                container:Hide()
            elseif mayOpen() and BagSize(id) > 0 then
                Bags:Toggle(mode)
            end
            return
        end
        Bags.stockToggleBag(id)
    end
    ToggleBackpack = function()
        if Bags.ready then
            local container = frame()
            if container and container:IsVisible() and Bags.mode == "0" then
                container:Hide()
            elseif mayOpen() then
                Bags:Toggle("0")
            end
            return
        end
        Bags.stockToggleBackpack()
    end
    ToggleKeyRing = function()
        if Bags.ready then
            local container = frame()
            if container and container:IsVisible() and Bags.mode == "key" then
                container:Hide()
            elseif mayOpen() then
                Bags:Toggle("key")
            end
            return
        end
        Bags.stockToggleKeyRing()
    end
    -- Stock close paths: the vendor window's OnHide closes the backpack and
    -- the UI panel sweep closes all bags, so our container follows suit.
    CloseBag = function(id)
        local container = frame()
        if Bags.ready and container and container:IsVisible() and
            (id == KEYRING_ID and Bags.mode == "key" or
                (id >= 0 and id <= 4 and Bags.mode == tostring(id))) then
            container:Hide()
        end
        Bags.stockCloseBag(id)
    end
    CloseBackpack = function()
        local container = frame()
        if Bags.ready and container and container:IsVisible() and Bags.mode == "0" then
            container:Hide()
        end
        Bags.stockCloseBackpack()
    end
    CloseAllBags = function()
        local container = frame()
        if Bags.ready and container and container:IsVisible() then
            container:Hide()
        end
        Bags.stockCloseAllBags()
    end
    -- Other addons query IsBagOpen to learn the open state; tell the truth.
    if type(IsBagOpen) == "function" then
        IsBagOpen = function(id)
            if Bags.ready and reroutes(id) then
                local container = frame()
                if container and container:IsVisible() then
                    local mode = id == KEYRING_ID and "key" or tostring(id)
                    if Bags.mode == mode then return 1 end
                    if Bags.mode == "all" then return 1 end
                end
                return nil
            end
            if Bags.stockIsBagOpen then return Bags.stockIsBagOpen(id) end
            return nil
        end
    end
    self.hookedStock = true
    return true
end

-- Restoring is per disable cycle: the install path has no once-flag of its
-- own beyond hookedStock, which clears here so a re-enable recaptures fresh
-- originals (the stock functions may have been re-wrapped by others).
function Bags:RestoreStockHooks()
    if not self.hookedStock then return true end
    if self.stockOpenBag then OpenBag = self.stockOpenBag end
    if self.stockOpenBackpack then OpenBackpack = self.stockOpenBackpack end
    if self.stockOpenAllBags then OpenAllBags = self.stockOpenAllBags end
    if self.stockToggleBag then ToggleBag = self.stockToggleBag end
    if self.stockToggleBackpack then ToggleBackpack = self.stockToggleBackpack end
    if self.stockToggleKeyRing then ToggleKeyRing = self.stockToggleKeyRing end
    if self.stockCloseBag then CloseBag = self.stockCloseBag end
    if self.stockCloseBackpack then CloseBackpack = self.stockCloseBackpack end
    if self.stockCloseAllBags then CloseAllBags = self.stockCloseAllBags end
    if self.stockIsBagOpen then IsBagOpen = self.stockIsBagOpen end
    self.hookedStock = false
    return true
end

-- Gray items only, bags 0 through 4 only: the keyring is never scanned.
-- Gray detection goes through the item link's poor-quality color code
-- (|cff9d9d9d), the idiom every working 1.12 sell-junk addon uses — the
-- quality return of GetContainerItemInfo is not dependable on 11200
-- clients (the stock interface itself stopped reading it). The scan runs
-- fresh immediately before every single sale because slots shift after
-- each one, and the merchant visibility check each tick is the
-- authoritative abort; Interface 11200 has no merchant hide event.
function Bags:ScanJunk()
    for bag = 0, 4 do
        local size = GetContainerNumSlots(bag)
        for slot = 1, size do
            local link = GetContainerItemLink(bag, slot)
            if link and string.find(link, "ff9d9d9d", 1, true) then
                local _, _, locked = GetContainerItemInfo(bag, slot)
                if not locked then
                    return bag, slot
                end
            end
        end
    end
    return nil
end

function Bags:SellJunk()
    if self.selling then
        self:FinishSell("junk selling cancelled")
        return
    end
    if not (MerchantFrame and MerchantFrame:IsVisible() and MerchantFrame.selectedTab ~= 2) then
        AndroidPort:Print("Sell Junk needs an open merchant.")
        return
    end
    if not self:ScanJunk() then
        AndroidPort:Print("No gray items to sell.")
        return
    end
    self.selling = true
    self.soldCount = 0
    self.sellMoneyStart = GetMoney()
    self.lastStep = 0
    self.reportAt = 0
    self:UpdateHeader()
end

function Bags:SellStep(now)
    if now - self.lastStep < SELL_TICK then return end
    if not (MerchantFrame and MerchantFrame:IsVisible()) then
        self:FinishSell("junk selling stopped: merchant closed")
        return
    end
    if MerchantFrame.selectedTab == 2 then
        self:FinishSell("junk selling stopped: buyback tab opened")
        return
    end
    local bag, slot = self:ScanJunk()
    if not bag then
        self:FinishSell()
        return
    end
    self.lastStep = now
    -- ClearCursor first is the reference-addon idiom: a stray cursor item
    -- would otherwise void the sale pickup.
    ClearCursor()
    UseContainerItem(bag, slot)
    self.soldCount = self.soldCount + 1
end

function Bags:FinishSell(reason)
    if not self.selling then return end
    self.selling = false
    self.finishReason = reason
    -- Money lags the sale by a frame or two in 1.12, so the earnings line
    -- is read on a deferred tick.
    self.reportAt = GetTime() + REPORT_DELAY
    self:UpdateHeader()
end

function Bags:ReportSell()
    local earned = GetMoney() - self.sellMoneyStart
    local reason = self.finishReason
    self.finishReason = nil
    if reason then
        AndroidPort:Print(reason .. " Sold " .. self.soldCount .. " gray item(s) for " .. MoneyText(earned) .. ".")
    else
        AndroidPort:Print("Sold " .. self.soldCount .. " gray item(s) for " .. MoneyText(earned) .. ".")
    end
end

function Bags:Validate()
    local frame = getglobal("AndroidPortBagsFrame")
    if not frame or not frame.bags then return false end
    return true
end

function Bags:FailSafe()
    -- pcall in this module guards Lua errors only; it is not crash protection.
    if self.selling then self:FinishSell() end
    pcall(function()
        self:RestoreStockHooks()
        local frame = getglobal("AndroidPortBagsFrame")
        if frame then frame:Hide() end
    end)
    self.ready = false
end

function Bags:Initialize()
    if self.ready and self:Validate() then return true end
    local enabled = AndroidPortDB and AndroidPortDB.bags and AndroidPortDB.bags.enabled
    if enabled == false then
        self:RestoreStockHooks()
        local frame = getglobal("AndroidPortBagsFrame")
        if frame then frame:Hide() end
        return true
    end
    local ok = pcall(function()
        self:EnsureFrame()
        self:Layout()
        self:InstallStockHooks()
    end)
    if ok and self:Validate() and self.hookedStock then
        self.ready = true
        return true
    end
    self:FailSafe()
    return false
end

function Bags:SetEnabled(enabled)
    if AndroidPortDB then
        AndroidPortDB.bags = AndroidPortDB.bags or {}
        AndroidPortDB.bags.enabled = enabled
    end
    if enabled then
        self:Initialize()
        if self.ready then AndroidPort:Print("bags enabled") end
    else
        self:FailSafe()
        AndroidPort:Print("bags disabled; the stock bag buttons behaviour is restored")
    end
end

local events = CreateFrame("Frame", "AndroidPortBagsEvents", UIParent)
events:RegisterEvent("BAG_UPDATE")
events:RegisterEvent("MERCHANT_SHOW")
events:RegisterEvent("MERCHANT_CLOSED")
events:RegisterEvent("PLAYER_MONEY")
events:SetScript("OnEvent", function()
    if event == "BAG_UPDATE" then
        -- arg1 is the bag id; an equipment change refreshes that slot icon.
        if arg1 and arg1 >= 1 and arg1 <= 4 then
            Bags:UpdateBagSlotButton(arg1)
        end
        Bags.refreshAt = GetTime() + REFRESH_TICK
    elseif event == "MERCHANT_SHOW" or event == "MERCHANT_CLOSED" then
        Bags:UpdateHeader()
    elseif event == "PLAYER_MONEY" then
        Bags:UpdateMoney()
    end
end)
events:SetScript("OnUpdate", function()
    local now = GetTime()
    if Bags.selling then
        Bags:SellStep(now)
        return
    end
    if Bags.reportAt > 0 and now >= Bags.reportAt then
        Bags.reportAt = 0
        Bags:ReportSell()
        return
    end
    if Bags.refreshAt > 0 and now >= Bags.refreshAt then
        Bags.refreshAt = 0
        Bags:RefreshSlots()
        Bags:UpdateHeader()
    end
end)
-- A hidden frame never receives OnUpdate in WoW, so the driver stays shown;
-- the frame is zero-sized and draws nothing.
events:SetWidth(1)
events:SetHeight(1)
events:Show()
