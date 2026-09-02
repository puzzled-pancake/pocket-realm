-- Android Port Talk (installed folder: AndroidPort): the controller's
-- conversation entry (plan E3). Typing a bot's exact name on a controller
-- keyboard is the single biggest friction in reaching the talking bots, so
-- the radial Talk action resolves who to address - the player's current
-- target if it is a player, else the last unit that whispered or spoke
-- nearby (in this realm those are the bots) - and opens the whisper
-- composer pre-filled with "/w <name> ". No name typing, ever.
--
-- Resolution is client-side on purpose: Interface 11200 offers no unit
-- radar for players, so "nearest" is approximated by target-first, then
-- the freshest voice (whisper beats say - a whisper is already a private
-- exchange). Walking up to a bot in town works because greet-first
-- arrivals, crowd chatter and bot2bot exchanges all arrive as whispers
-- or nearby says before the player would want to reply.
AndroidPort.Talk = AndroidPort.Talk or {}
local Talk = AndroidPort.Talk
Talk.ready = Talk.ready or false

local function Print(message)
    AndroidPort:Print(message)
end

-- The composer is the stock shared edit box: ChatFrame_OpenChat applies
-- the whole open/focus lifecycle (the same path the Enter binding uses).
-- Guarded because a future FrameXML reshuffle could rename it; the bare
-- edit-box fallback still works for sending.
local function OpenComposer(name)
    local prefixed = "/w " .. name .. " "
    if type(ChatFrame_OpenChat) == "function" then
        ChatFrame_OpenChat(prefixed, DEFAULT_CHAT_FRAME)
        return true
    end
    local edit = getglobal("ChatFrame1EditBox")
    if edit and edit.SetText and edit.IsShown then
        edit:SetText(prefixed)
        if not edit:IsShown() and type(ChatEdit_ToggleInputFocus) == "function" then
            ChatEdit_ToggleInputFocus()
        end
        return true
    end
    return false
end

-- Resolution order: an explicit player target, the last whisperer (they
-- are mid-conversation with us), the last nearby speaker. The player's
-- own name never qualifies as a whisper target.
function Talk:ResolveTarget()
    if UnitExists("target") and UnitIsPlayer("target") and
        not UnitIsUnit("target", "player") then
        return UnitName("target")
    end
    if self.lastWhisperFrom and self.lastWhisperFrom ~= "" and
        self.lastWhisperFrom ~= UnitName("player") then
        return self.lastWhisperFrom
    end
    if self.lastSayFrom and self.lastSayFrom ~= "" and
        self.lastSayFrom ~= UnitName("player") then
        return self.lastSayFrom
    end
    return nil
end

function Talk:Open()
    -- Bisection switch: "/ap off talk" must disable the module, including
    -- through the radial entry.
    if AndroidPort and not AndroidPort:IsModuleEnabled("talk") then return false end
    local ok, name = pcall(function() return self:ResolveTarget() end)
    if not ok or not name then
        Print("no one to talk to yet: target someone, or answer a voice in town first")
        return false
    end
    local opened = false
    pcall(function() opened = OpenComposer(name) end)
    if opened then
        -- deliberately NOT writing the resolved name back into
        -- lastWhisperFrom: the tracker means "who actually whispered", and
        -- an unsend composer open must not pin resolution forever
        return true
    end
    Print("could not open the whisper composer")
    return false
end

function Talk:Initialize()
    if self.ready then return true end
    local host = AndroidPort
    if not host or type(host.IsModuleEnabled) ~= "function" then return false end
    self.ready = true
    return true
end

function Talk:FailSafe()
    -- The module owns no frames beyond the shared event watcher below,
    -- which gates itself on the module switch; fail-safe only resets state.
    Talk.ready = false
end

local events = CreateFrame("Frame", "AndroidPortTalkEvents", UIParent)
events:RegisterEvent("CHAT_MSG_WHISPER")
events:RegisterEvent("CHAT_MSG_WHISPER_INFORM")
events:RegisterEvent("CHAT_MSG_SAY")
events:SetScript("OnEvent", function()
    -- Crash-bisection switch ("/ap off talk"): stale tracking is harmless,
    -- but a disabled module must not observe chat at all.
    if AndroidPort and not AndroidPort:IsModuleEnabled("talk") then return end
    if not AndroidPort.Talk then return end
    local me = UnitName("player")
    if event == "CHAT_MSG_WHISPER_INFORM" then
        -- our own outgoing whisper: arg2 is the recipient - the player is
        -- already in this exchange, keep it one tap away
        if arg2 and arg2 ~= me then AndroidPort.Talk.lastWhisperFrom = arg2 end
    elseif event == "CHAT_MSG_WHISPER" then
        if arg2 and arg2 ~= me then AndroidPort.Talk.lastWhisperFrom = arg2 end
    elseif event == "CHAT_MSG_SAY" then
        if arg2 and arg2 ~= me then AndroidPort.Talk.lastSayFrom = arg2 end
    end
end)
