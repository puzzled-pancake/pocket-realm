-- Android Port frame mover: clean-room Interface 11200 layout journal for
-- curated stock frames. Bag and bank frames are re-anchored by the stock UI
-- whenever they open, so only their scale is journaled (scaleOnly).
VanillaConsolePort.FrameMover = VanillaConsolePort.FrameMover or {}
local Mover = VanillaConsolePort.FrameMover
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
    { name = "MiniMapCluster", label = "Minimap" },
    { name = "QuestLogFrame", label = "Quest log" },
    { name = "LootFrame", label = "Loot window" },
    { name = "ContainerFrame1", label = "Backpack", scaleOnly = true },
    { name = "ContainerFrame2", label = "Bag 2", scaleOnly = true },
    { name = "ContainerFrame3", label = "Bag 3", scaleOnly = true },
    { name = "ContainerFrame4", label = "Bag 4", scaleOnly = true },
    { name = "BankFrame", label = "Bank", scaleOnly = true },
}

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
        if point and relativeName then
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
        -- No named relative among the points; journal a restorable
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
            local relative = getglobal(stored.relative) or UIParent
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

-- Remember the stock layout once, before this addon ever changes the frame,
-- so /vcp resetui can put it back exactly. A frame already journaled by us
-- never re-captures, so a later session cannot mistake our layout for stock.
function Mover:CaptureBackup(frame)
    local db = VanillaConsolePortDB
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
    local db = VanillaConsolePortDB
    local name = frame and frame.GetName and frame:GetName() or nil
    if not name or type(db) ~= "table" or type(db.frameAnchors) ~= "table" then return false end
    local points = self:CapturePoints(frame)
    if not points then return false end
    local entry = { points = points, label = label }
    if frame.GetScale then entry.scale = frame:GetScale() end
    if not self:IsValidSavedFrame(entry) then return false end
    db.frameAnchors[name] = entry
    return true
end

function Mover:SaveScaleOnly(frame)
    local db = VanillaConsolePortDB
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
    local anchors = VanillaConsolePortDB and VanillaConsolePortDB.frameAnchors
    if type(anchors) ~= "table" then return false end
    for name, saved in pairs(anchors) do
        local frame = getglobal(name)
        if frame and self:IsValidSavedFrame(saved) then
            pcall(function() Mover:ApplySavedFrame(frame, saved) end)
        end
    end
    return true
end

-- Restore the captured stock layout and retire every layout journal,
-- including the legacy add-on icon anchors, so the interface comes back
-- exactly as this addon never existed.
function Mover:ResetUI()
    local db = VanillaConsolePortDB
    if type(db) ~= "table" then return false end
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
    if VanillaConsolePort and VanillaConsolePort.Print then
        VanillaConsolePort:Print("stock frame layout restored")
    end
    return restored
end

function Mover:Initialize()
    if self.ready then return true end
    local host = VanillaConsolePort
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
    return true
end
