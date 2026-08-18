-- Android Port frame mover: clean-room Interface 11200 layout journal for
-- curated stock frames plus journaled panels. Bag and bank frames are
-- re-anchored by the stock UI whenever they open, so only their scale is
-- journaled (scaleOnly) and the stock anchor sweep is wrapped so journaled
-- layout survives bag open and close.
AndroidPort.FrameMover = AndroidPort.FrameMover or {}
local Mover = AndroidPort.FrameMover
Mover.ready = Mover.ready or false

Mover.SCALE_MIN = 0.5
Mover.SCALE_MAX = 1.5

local curatedFrames = {
    { name = "PlayerFrame", label = "Player frame" },
    { name = "TargetFrame", label = "Target frame" },
    { name = "PetFrame", label = "Pet frame" },
    { name = "PartyMemberFrame1", label = "Party member 1" },
    { name = "PartyMemberFrame2", label = "Party member 2" },
    { name = "PartyMemberFrame3", label = "Party member 3" },
    { name = "PartyMemberFrame4", label = "Party member 4" },
    { name = "CastingBarFrame", label = "Cast bar" },
    { name = "UIErrorsFrame", label = "Error text" },
    { name = "MinimapCluster", label = "Minimap" },
    { name = "QuestLogFrame", label = "Quest log" },
    { name = "LootFrame", label = "Loot window" },
    { name = "GossipFrame", label = "Gossip" },
    { name = "QuestFrame", label = "Quest" },
    { name = "MerchantFrame", label = "Merchant" },
    { name = "TradeFrame", label = "Trade" },
    { name = "MailFrame", label = "Mail" },
    { name = "CharacterFrame", label = "Character" },
    { name = "SpellBookFrame", label = "Spellbook" },
    { name = "TalentFrame", label = "Talents" },
    { name = "SkillFrame", label = "Skills" },
    { name = "FriendsFrame", label = "Social" },
    { name = "HelpFrame", label = "Help" },
    { name = "GameMenuFrame", label = "Game menu" },
    { name = "WorldMapFrame", label = "World map" },
    { name = "BattlefieldFrame", label = "Battlefield" },
    { name = "DurabilityFrame", label = "Durability" },
    { name = "QuestWatchFrame", label = "Quest watch" },
    { name = "RaidFrame", label = "Raid" },
    { name = "ChatFrame1", label = "Chat" },
    { name = "ChatFrame2", label = "Combat log" },
    { name = "ChatFrame3", label = "Chat 3" },
    { name = "ChatFrame4", label = "Chat 4" },
    { name = "ChatFrame5", label = "Chat 5" },
    { name = "ChatFrame6", label = "Chat 6" },
    { name = "ChatFrame7", label = "Chat 7" },
    { name = "ContainerFrame1", label = "Backpack", scaleOnly = true },
    { name = "ContainerFrame2", label = "Bag 2", scaleOnly = true },
    { name = "ContainerFrame3", label = "Bag 3", scaleOnly = true },
    { name = "ContainerFrame4", label = "Bag 4", scaleOnly = true },
    { name = "BankFrame", label = "Bank", scaleOnly = true },
    -- The console action stars are this addon's own session-lifetime frames;
    -- curating them keeps the crash-safety exemption honest (they can never
    -- be freed under us) while making the stars movable and scalable as units.
    { name = "AndroidPortLeftCluster", label = "Left action cluster" },
    { name = "AndroidPortRightCluster", label = "Right action cluster" },
    -- The all-in-one bag window replaces the stock containers while the Bags
    -- module is ready; the stock sweep never re-anchors it, so it journals a
    -- full anchor like the clusters. Created at file load in Bags.lua.
    { name = "AndroidPortBagsFrame", label = "Bags" },
}

-- Journal anchors may only reference these relatives by name. Applying a
-- saved anchor resolves the name through this whitelist and falls back to
-- UIParent, so a journal entry can never install an anchor into a foreign
-- frame that a third-party addon may later free.
local relativeWhitelist = { UIParent = true, Minimap = true, MainMenuBar = true }
for _, spec in ipairs(curatedFrames) do
    relativeWhitelist[spec.name] = true
end

local function SafeRelative(name)
    if relativeWhitelist[name] then
        local frame = getglobal(name)
        if frame then return frame end
    end
    return UIParent
end

local function HasFrameLayoutMethods(frame)
    return frame and frame.GetPoint and frame.SetPoint and frame.ClearAllPoints and
        frame.GetNumPoints and frame.GetName
end

local function ValidNumber(value, limit)
    if value == nil then return true end
    if type(value) ~= "number" then return false end
    return value >= -limit and value <= limit
end

function Mover:CapturePoints(frame)
    if not HasFrameLayoutMethods(frame) then return nil end
    local points = {}
    local count = frame:GetNumPoints()
    for index = 1, count do
        local point, relativeTo, relativePoint, x, y = frame:GetPoint(index)
        local relativeName = relativeTo and relativeTo.GetName and relativeTo:GetName() or nil
        if point and relativeName and relativeWhitelist[relativeName] then
            tinsert(points, {
                point = point,
                relative = relativeName,
                relativePoint = relativePoint or point,
                x = tonumber(x) or 0,
                y = tonumber(y) or 0,
            })
        end
    end
    if table.getn(points) == 0 then
        -- No whitelisted relative among the points; journal a restorable
        -- absolute centre instead so the frame can always come back.
        if not frame.GetCenter then return nil end
        local centerX, centerY = frame:GetCenter()
        centerX, centerY = tonumber(centerX), tonumber(centerY)
        if not centerX or not centerY then return nil end
        tinsert(points, {
            point = "CENTER", relative = "UIParent", relativePoint = "BOTTOMLEFT",
            x = centerX, y = centerY,
        })
    end
    return points
end

function Mover:IsValidSavedFrame(saved)
    if type(saved) ~= "table" then return false end
    local hasScale = saved.scale ~= nil
    if hasScale and (type(saved.scale) ~= "number" or
        saved.scale < Mover.SCALE_MIN or saved.scale > Mover.SCALE_MAX) then
        return false
    end
    if saved.points == nil then return hasScale end
    if type(saved.points) ~= "table" or table.getn(saved.points) == 0 then return false end
    for _, stored in ipairs(saved.points) do
        if type(stored) ~= "table" or type(stored.point) ~= "string" or
            type(stored.relative) ~= "string" or type(stored.relativePoint) ~= "string" or
            not ValidNumber(stored.x, 10000) or not ValidNumber(stored.y, 10000) then
            return false
        end
    end
    return true
end

function Mover:ApplySavedFrame(frame, saved)
    if not frame or not self:IsValidSavedFrame(saved) then return false end
    if saved.points and table.getn(saved.points) > 0 and frame.ClearAllPoints then
        frame:ClearAllPoints()
        for _, stored in ipairs(saved.points) do
            local relative = SafeRelative(stored.relative)
            frame:SetPoint(stored.point, relative, stored.relativePoint, stored.x, stored.y)
        end
    end
    if saved.scale and frame.SetScale then
        local scale = saved.scale
        if scale < Mover.SCALE_MIN then scale = Mover.SCALE_MIN end
        if scale > Mover.SCALE_MAX then scale = Mover.SCALE_MAX end
        frame:SetScale(scale)
    end
    return true
end

-- A journaled left or center panel must leave the stock panel manager, which
-- re-anchors such panels every time they open. Full-screen panels keep their
-- registration so full-screen bookkeeping stays intact. A de-registered panel
-- still opens normally, but Escape no longer closes it and panels overlap
-- instead of pushing each other, so only journaled panels are released.
function Mover:ReleasePanelManagement(name)
    if type(UIPanelWindows) ~= "table" then return false end
    local info = UIPanelWindows[name]
    if info == nil or info.area == "full" then return false end
    local db = AndroidPortDB
    if type(db) ~= "table" then return false end
    local released = db.panelReleases
    if type(released) ~= "table" then
        released = {}
        db.panelReleases = released
    end
    if released[name] == nil then released[name] = info end
    UIPanelWindows[name] = nil
    return true
end

-- Stock frames can re-anchor themselves when shown; chain our re-apply after
-- the stock OnShow. The original runs synchronously first so the stock
-- handler still sees the correct global this.
function Mover:ChainOnShow(name, frame)
    if not frame or not frame.SetScript then return false end
    if self.chainedOnShow == nil then self.chainedOnShow = {} end
    if self.chainedOnShow[name] then return false end
    self.chainedOnShow[name] = true
    local original = frame.GetScript and frame:GetScript("OnShow") or nil
    frame:SetScript("OnShow", function()
        if original then original() end
        local anchors = AndroidPortDB and AndroidPortDB.frameAnchors
        local saved = type(anchors) == "table" and anchors[name] or nil
        if saved and saved.points then
            local target = getglobal(name)
            if target then pcall(function() Mover:ApplySavedFrame(target, saved) end) end
        end
    end)
    return true
end

-- Remember the stock layout once, before this addon ever changes the frame,
-- so /ap resetui can put it back exactly. A frame already journaled by us
-- never re-captures, so a later session cannot mistake our layout for stock.
function Mover:CaptureBackup(frame)
    local db = AndroidPortDB
    local name = frame and frame.GetName and frame:GetName() or nil
    if not name or type(db) ~= "table" then return false end
    local anchors, backups = db.frameAnchors, db.frameBackups
    if type(anchors) ~= "table" or type(backups) ~= "table" then return false end
    if anchors[name] ~= nil or backups[name] ~= nil then return false end
    local points = self:CapturePoints(frame)
    if not points then return false end
    local backup = { points = points }
    if frame.GetScale then backup.scale = frame:GetScale() end
    backups[name] = backup
    return true
end

function Mover:SaveFrame(frame, label)
    local db = AndroidPortDB
    local name = frame and frame.GetName and frame:GetName() or nil
    if not name or type(db) ~= "table" or type(db.frameAnchors) ~= "table" then return false end
    local points = self:CapturePoints(frame)
    if not points then return false end
    local entry = { points = points, label = label }
    if frame.GetScale then entry.scale = frame:GetScale() end
    if not self:IsValidSavedFrame(entry) then return false end
    db.frameAnchors[name] = entry
    self:ReleasePanelManagement(name)
    self:ChainOnShow(name, frame)
    return true
end

function Mover:SaveScaleOnly(frame)
    local db = AndroidPortDB
    local name = frame and frame.GetName and frame:GetName() or nil
    if not name or type(db) ~= "table" or type(db.frameAnchors) ~= "table" then return false end
    if not frame.GetScale or not frame.SetScale then return false end
    local scale = frame:GetScale()
    if type(scale) ~= "number" or scale < Mover.SCALE_MIN or scale > Mover.SCALE_MAX then
        return false
    end
    local existing = db.frameAnchors[name]
    if existing then
        existing.scale = scale
    else
        db.frameAnchors[name] = { scale = scale }
    end
    return true
end

function Mover:RestoreFrames()
    local anchors = AndroidPortDB and AndroidPortDB.frameAnchors
    if type(anchors) ~= "table" then return false end
    for name, saved in pairs(anchors) do
        local frame = getglobal(name)
        if frame and self:IsValidSavedFrame(saved) then
            -- pcall guards Lua errors only; it is not crash protection.
            pcall(function() Mover:ApplySavedFrame(frame, saved) end)
            Mover:ChainOnShow(name, frame)
        end
    end
    return true
end

-- The stock bag sweep runs from both the open and the close path and also
-- resets container scale, so re-assert journaled layout after it settles.
function Mover:ReassertContainers()
    local anchors = AndroidPortDB and AndroidPortDB.frameAnchors
    if type(anchors) ~= "table" then return end
    for name, saved in pairs(anchors) do
        if string.find(name, "ContainerFrame", 1, true) or name == "BankFrame" then
            local frame = getglobal(name)
            if frame and frame.IsShown and frame:IsShown() then
                pcall(function() Mover:ApplySavedFrame(frame, saved) end)
            end
        end
    end
end

function Mover:InstallStockHooks()
    if self.hooksInstalled then return true end
    self.hooksInstalled = true
    if type(updateContainerFrameAnchors) == "function" then
        Mover.stockContainerAnchors = updateContainerFrameAnchors
        updateContainerFrameAnchors = function()
            Mover.stockContainerAnchors()
            Mover:ReassertContainers()
        end
    end
    return true
end

-- Restore the captured stock layout and retire every layout journal,
-- including the legacy add-on icon anchors, so the interface comes back
-- exactly as this addon never existed.
function Mover:ResetUI()
    local db = AndroidPortDB
    if type(db) ~= "table" then return false end
    local released = db.panelReleases
    if type(released) == "table" and type(UIPanelWindows) == "table" then
        for name, info in pairs(released) do
            UIPanelWindows[name] = info
        end
    end
    db.panelReleases = nil
    local restored = 0
    if type(db.frameBackups) == "table" then
        for name, backup in pairs(db.frameBackups) do
            local frame = getglobal(name)
            if frame and self:IsValidSavedFrame(backup) then
                local ok = pcall(function() Mover:ApplySavedFrame(frame, backup) end)
                if ok then restored = restored + 1 end
            end
        end
    end
    db.frameBackups = {}
    db.frameAnchors = {}
    db.addonIconAnchors = {}
    if AndroidPort and AndroidPort.Print then
        AndroidPort:Print("stock frame layout restored")
    end
    return restored
end

function Mover:Initialize()
    if self.ready then return true end
    local host = AndroidPort
    if not host or type(host.addonIconCandidates) ~= "table" then return false end
    local ok = pcall(function()
        for _, spec in ipairs(curatedFrames) do
            local frame = getglobal(spec.name)
            if HasFrameLayoutMethods(frame) and
                (not frame.IsProtected or not frame:IsProtected()) then
                local key = "frame:" .. spec.name
                local candidate = host.addonIconCandidates[key]
                if not candidate or candidate.frame ~= frame then
                    if candidate and candidate.handle then candidate.handle:Hide() end
                    candidate = {
                        id = key,
                        name = spec.name,
                        frame = frame,
                        label = spec.label or spec.name,
                        registered = true,
                        curated = true,
                        scaleOnly = spec.scaleOnly,
                    }
                    host.addonIconCandidates[key] = candidate
                    Mover:CaptureBackup(frame)
                end
            end
        end
    end)
    if not ok then return false end
    self.ready = true
    self:RestoreFrames()
    self:InstallStockHooks()
    return true
end
