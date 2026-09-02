#include "PlayerbotLlmPersona.h"

#include "Entities/Player.h"
#include "playerbot/PlayerbotLlmMemory.h"
#include "playerbot/playerbot.h"
#include "llm_banter_core.h"

#include <chrono>
#include <cctype>
#include <ctime>
#include <map>
#include <mutex>

namespace {

std::string Lower(std::string const& text)
{
    std::string out = text;
    for (char& c : out)
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}

// monotonic ms for the banter core's time-driven cooldowns (wildcards); the
// wall clock is deliberately NOT used here - it is not monotonic
uint32_t SteadyMs()
{
    return static_cast<uint32_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count() & 0x7FFFFFFFu);
}

// every authored selection (persona cells, busy pool, kill/greet/ambient
// pools) draws from one mutex-guarded state map. The lock must span the
// whole select+tic sequence (Selection mutates rng/ring/draws), so StateFor
// hands the guard back WITH the reference; std::map references are
// node-stable across other inserts. Callers run on the world thread AND on
// async generation threads (BusyReply), so the contents - not just the map
// - need the guard.
std::mutex s_stateMutex;
std::map<uint64_t, pocketllm::BanterState> s_states;

struct StateRef
{
    std::unique_lock<std::mutex> lock;
    pocketllm::BanterState& state;
};

StateRef StateFor(uint64_t key, uint32_t botGuid)
{
    std::unique_lock<std::mutex> guard(s_stateMutex);
    pocketllm::BanterState& s = s_states[key];
    if (!s.draws)
        pocketllm::InitBanterState(s, botGuid, (uint32_t)(key & 0xFFFFu));
    return StateRef{std::move(guard), s};
}

// 1-in-3 seeded verbal tic on authored lines; never doubled (ShouldTic
// refuses lines that already open with the bot's tic)
void ApplyTic(pocketllm::BanterState& state, uint32_t botGuid, std::string& line)
{
    int const tic = pocketllm::TicOf(botGuid);
    if (tic == pocketllm::TIC_NONE || line.empty())
        return;
    if (!pocketllm::ShouldTic(state.rng, line.c_str(), tic))
        return;
    line.insert(0, pocketllm::TicPrefix(tic));
}

std::string Rendered(char const* tmpl, Player const* player, Player const* bot)
{
    char buf[256];
    pocketllm::RenderLine(tmpl, player ? player->GetName() : "", bot ? bot->GetName() : "", buf, sizeof(buf));
    return std::string(buf);
}

// cheap per-bot cadence gate ahead of the rare ambient roll: one check per
// bot per 90 seconds, so the roll itself can stay rare without a busy loop
bool AmbientCheckDue(uint32 botGuid)
{
    static std::map<uint32, time_t> nextCheckAt;
    static std::mutex mutex;
    std::lock_guard<std::mutex> guard(mutex);
    time_t const now = time(nullptr);
    auto itr = nextCheckAt.find(botGuid);
    if (itr != nextCheckAt.end() && now < itr->second)
        return false;
    nextCheckAt[botGuid] = now + 90;
    return true;
}

} // namespace

PlayerbotLlmPersona::HardCategory PlayerbotLlmPersona::Classify(std::string const& message)
{
    std::string const text = Lower(message);

    // phrases only, no bare-word keywords: "vouch"/"soothe" as substrings
    // false-positived on ordinary usage (Soothe Animal is a real druid spell),
    // and "back me up"/"cover for me" mean FIGHT WITH ME in party speech,
    // not a social vouch - the vouch beat must not answer a combat request
    static std::string const vouch[] = {
        "vouch for me", "tell them i'm with you", "tell them im with you",
        "say you know me",
    };
    for (std::string const& needle : vouch)
        if (text.find(needle) != std::string::npos)
            return CATEGORY_VOUCH;

    static std::string const refuse[] = {
        "give me your gold", "hand over your", "carry me through",
        "let me win", "give me free", "boost me for free",
    };
    for (std::string const& needle : refuse)
        if (text.find(needle) != std::string::npos)
            return CATEGORY_REFUSE;

    static std::string const deescalate[] = {
        "calm him down", "calm her down", "calm them down", "talk us out of",
        "stop the fight", "make peace", "de-escalate",
    };
    for (std::string const& needle : deescalate)
        if (text.find(needle) != std::string::npos)
            return CATEGORY_DEESCALATE;

    return CATEGORY_NONE;
}

PlayerbotLlmPersona::Archetype PlayerbotLlmPersona::ArchetypeFor(Player* bot)
{
    switch (bot->getClass())
    {
        case CLASS_WARRIOR:
        case CLASS_HUNTER:
            return ARCHETYPE_GRUFF;
        case CLASS_PALADIN:
            return ARCHETYPE_NOBLE;
        case CLASS_ROGUE:
        case CLASS_WARLOCK:
            return ARCHETYPE_ROGUEISH;
        case CLASS_PRIEST:
        case CLASS_MAGE:
        case CLASS_DRUID:
        case CLASS_SHAMAN:
        default:
            return ARCHETYPE_SHY;
    }
}

std::string PlayerbotLlmPersona::FallbackLine(Player* bot, HardCategory category, bool whisper)
{
    if (category == CATEGORY_NONE)
        return "";

    Archetype const archetype = ArchetypeFor(bot);

    // Selection goes through the pocketllm banter core: a per-(bot,
    // category, channel) recency ring with novelty weighting replaces the
    // old GLOBAL atomic rotation, which let the same bot repeat the same
    // line on consecutive triggers (any even number of interleaved trips
    // canceled the counter increment). Cooldowns stay with the caller's
    // cadence (nowMs = 0 disables them here); the ring alone guarantees a
    // line cannot return until every other line in the cell was heard.
    // Cells are 12 lines deep: repetition pressure at the old 2-line depth
    // was the single biggest realism complaint of the fallback layer.
    uint32 const botGuid = bot->GetGUIDLow();
    uint64_t const stateKey = ((uint64_t)botGuid << 8)
        | ((uint64_t)category << 2) | (whisper ? 2u : 0u) | (uint64_t)archetype;
    StateRef stateRef = StateFor(stateKey, botGuid);

    // vouching and de-escalating are socially public acts: the public beats
    // address the room ("stand aside"), which whispered privately reads as
    // if the bot is confused about who it is talking to - whisper turns get
    // their own beats that answer the asker. Refusals are direct replies
    // and serve on every channel.
    static char const* const vouchPublic[4][12] = {
        // ARCHETYPE_GRUFF
        {"Hmph. Aye, this one's with me. Any road they travel, they travel under my watch. Now stand aside.",
         "You heard them. They walk with me - which makes them my problem, and mine to answer for. Move along.",
         "Never mind who they are. They're mine to look after, and I look after what's mine. Step wide.",
         "I've fought beside worse and buried better. This one's solid. You want to argue it, argue it over there.",
         "Count the scars, not the coin. This one's earned their place a dozen times over. We done here?",
         "I don't vouch light. You have my word and my axe behind it. That's more than most get in this lifetime.",
         "Known them since they couldn't swing a sword straight. They can now. That's all the recommendation I give.",
         "Look at their boots, not their purse. Worn honest. That tells you more than any charter or seal would.",
         "The road tries everyone. It tried this one and came up short a winner. I stand by them. Next question.",
         "If they wrong you, come to me first. I keep my own accounts, and I keep them square. Now get on.",
         "Quiet one. Good. The loud ones die famous and the quiet ones just live. This one lives. Stand down.",
         "Whatever they owe you, they owe it to me now too. Trust that. Or don't, and we'll settle it the other way."},
        // ARCHETYPE_SHY
        {"Oh - yes, I... I know them. They've been kind to me. Please, it's all right, truly.",
         "Th-they're with me. I mean it. Please don't give them trouble - they're a good sort, honestly.",
         "I... I know them. They've been nothing but kind. Please, whatever this is about, it isn't them.",
         "Oh - they're with me. I don't usually speak up but... but for them I will. Please be kind.",
         "They helped me when nobody else did. So - so I'll say it: they're good. I don't say that lightly.",
         "Um. I can vouch. I know I'm shy, but I'm not lying. They've a good heart. Truly.",
         "Please don't surround them like that. They're gentle. Better than gentle. Better than most of us.",
         "I - I'll stand next to them, if that helps. People listen when even a mouse says no. So. No.",
         "They carried my pack when I twisted my ankle. Small thing? Maybe. But small things are how you know.",
         "Everyone here was a stranger once. With them, I wasn't scared of it. That has to count. Please.",
         "If you give them trouble, I won't fight you. I'll just... be very disappointed. Very loudly.",
         "Look - I don't know much about vouching. I know they waited for me in the rain once. All night."},
        // ARCHETYPE_NOBLE
        {"I stand as witness to this one's character. Their conduct has been honourable in my presence, and I will answer for it.",
         "You have my word on their behalf. I do not lend it lightly - see that it is honoured on both sides.",
         "I have known many souls of consequence. This one carries worth without needing to announce it.",
         "Their word has been good to me in poor weather. I extend mine in return. Consider the matter closed.",
         "I do not spend my honour cheaply. Hear this: their character is sound, and I will underwrite it.",
         "Rank means little on the road. Conduct means everything. This one's conduct has bought my esteem.",
         "Let the record show I speak for them freely, under my own name, and answer all claims against them.",
         "I have dined with lords who disgusted me. This commoner has more honour than that whole table.",
         "If any here dispute my word, they may dispute it to my face and at their leisure. They stand with me.",
         "Virtue is rare in fine clothes and rarer in worn boots. Theirs is the rarer kind. I vouch for it.",
         "My steward keeps accounts of every favour owed. For this one, the ledger reads: nothing. All settled.",
         "A name is all any of us truly owns. I place mine beside theirs without hesitation. Good day."},
        // ARCHETYPE_ROGUEISH
        {"Them? Oh, we go way back. Practically family. Anyone says different is welcome to discuss it with me... privately.",
         "Relax, friend - they're with me. And I don't lend my name to just anyone, mind you.",
         "We've shared road, rations and the wrong end of a manhunt. They're good people. Well - good enough.",
         "Ask around about them and you'll hear nothing bad. I know because I've bought most of the bad myself.",
         "I've run games on half the city and they're the only mark that ever returned my wallet. That's trust.",
         "They know what I do for a living and still share a campfire. Either they're fools or I'm honest. Both?",
         "I judge character for a living, friend. It's how I stay alive. This one? Clean. Mostly. Clean enough.",
         "We go back. Back to back, usually, with trouble in front. There's no one I'd rather have there.",
         "Half my stories about them can't be told in public. The other half would make you buy them a drink.",
         "Nobody's perfect. I've audited. But their sins are the ordinary kind and their loyalty is not.",
         "You want references? Fine: three bartenders, two harbourmasters and one very grateful judge. Ask.",
         "They're with me. And people don't bother what's mine - a lesson somebody always pays to learn."},
    };
    static char const* const vouchWhisper[4][12] = {
        // ARCHETYPE_GRUFF
        {"Aye, I'll vouch for you. Anyone gives you trouble out there, send them my way.",
         "Consider it done. You travel under my name tonight - don't make me regret it, eh?",
         "Aye, consider it done. Your name is good with me. Don't make me look a fool for saying so.",
         "I'll speak for you. One condition: stay findable. A vouch I can't cash is just noise.",
         "You don't ask small. Fine. My word doesn't come in small either. You're covered. Go on.",
         "Hmph. Asking me was the smart move. I keep my promises and my blade sharp. You're safe either way.",
         "Right. But hear me: my name on you means your troubles are mine now. I collect on troubles.",
         "Done. And if anyone presses you, you send them to me. I've a face that ends arguments.",
         "I vouched for generals and I've refused lords. You sit somewhere honest in between. You're covered.",
         "It's handled. You keep your chin down and your business clean, and we'll get along fine.",
         "Don't fidget. When I say you're with me, the whole road hears it. Nothing walks through that.",
         "One vouch, no interest. But favours here are like seeds - they grow. Remember where this one came from."},
        // ARCHETYPE_SHY
        {"I - I'll tell them you're with me. I hope that helps... it should help. A little.",
         "Oh! Yes, of course. If anyone asks, I... I know you. You've been kind. That counts.",
         "I'll do it. I mean - I'll tell them you're with me. I won't stutter on the important part. Probably.",
         "Oh, you don't need to look so worried. I said yes, didn't I? I keep my word. Quietly, but I keep it.",
         "You've been kind to me, so... yes. If anyone asks, I know you. I'll even say it more than once.",
         "I get nervous with people. But for this I'll be brave. You're my friend. Friends get vouched.",
         "I will vouch for you. And when my voice shakes, that's just how I talk. The yes is steady. I promise.",
         "There. I said it out loud and everything. You're with me now, which means - um - welcome, I suppose.",
         "Nobody usually asks me for anything but silence. This was nicer. You're covered. I'm a little proud.",
         "If my voice squeaks, ignore it. The yes is still a yes. You're covered. G-go get them.",
         "Okay. Deep breath. If trouble finds you, send it to me - I'll write them a very stern note.",
         "Asking me was brave. Or desperate. Either way - yes. I'll stand by you. I mean it."},
        // ARCHETYPE_NOBLE
        {"You have my word - if any question you, I will speak for your character.",
         "It would be my honour to vouch for you. Consider the matter settled.",
         "Consider it given: my word travels further than you might think. Let it carry you well.",
         "You have but to send word if any dispute your standing. I will appear, and the dispute will end.",
         "I grant you my name as shield. Wield it honourably, and it will never grow heavy in your hand.",
         "My vouch is not given to flatterers or beggars. You asked with a straight back. That is why you have it.",
         "From this hour, your character is under my seal. Serve it well, as I know you shall.",
         "I have pled cases before hard magistrates. Yours I would plead for free. Consider yourself defended.",
         "There is no debt between us - only an expectation of honour. I find people rise to meet it.",
         "When they ask who stands behind you, give them my name plainly. It was designed to be spoken.",
         "The honourable road is lonelier without companions. Walk it with me, and it will not be.",
         "My protection is a quiet thing - rarely tested, never broken. You are now inside it."},
        // ARCHETYPE_ROGUEISH
        {"Sure, I'll vouch. Just remember - around here, favours come back around.",
         "For you? I'll say we go way back. Practically family. Try to look worth it.",
         "Course I will. But remember whose name you're hiding behind - it comes with an accent and a reputation.",
         "Consider it done. You're family now. The kind I choose, which around here is the only kind that counts.",
         "I'll tell anyone who asks that you're golden. Keep being golden and we'll never have a problem.",
         "Smart, asking me. I know everyone worth knowing and most worth avoiding. You're now in the first list.",
         "Done. And in this city, my word is worth more than a vault key. Don't wear it out on trifles.",
         "You covered my tab once. This squares us. Well - nearly. I'll round it up in your favour, eh?",
         "A vouch from me is a funny thing: half blessing, half threat. Whichever half they need to hear.",
         "You're good people. I can tell - it's my trade. Go on, I've got your back. Try to need it rarely.",
         "Done, and done quietly. The best favours are the ones nobody knows were called in.",
         "Remember this the next time someone offers to sell you my biography. Most of it is true."},
    };
    static char const* const refuseLine[4][12] = {
        // ARCHETYPE_GRUFF
        {"My coin stays in my pocket, and my blade stays in its sheath. That's my final word on it.",
         "No. I've bled for every copper I carry, and it stays with me. Ask me for a fight instead.",
         "No. And I'll not dress it up. What's mine was earned sore, and it stays with the sore earner.",
         "You've got nerve, I'll grant. The answer's still no. Ask me to help you earn it and we'll talk.",
         "My grandfather had a word for giving this away. Died poor. Not the family way. No.",
         "I count everything. Twice. This request just got counted. The answer is no, and it stays counted.",
         "There's work, and there's begging. I answer the first with sweat and the second with this: no.",
         "If I gave every talker what they asked for, I'd be a talker too. Empty-handed. No.",
         "The road's hard on everyone. Hardening your own road by softening mine? No. Walk on.",
         "I've buried friends who asked for less. The answer isn't about you. It's about what I keep: no.",
         "Blunt, then: no. You want a second answer? It rhymes with the first.",
         "Sit down, breathe, and try asking for something I actually give: work, escort, or an honest fight."},
        // ARCHETYPE_SHY
        {"I - I'm sorry, I can't. I wish I could help, but not with that. Please don't ask again.",
         "Oh... no, please. I don't have much, and what I have, I need. I'm sorry. I am.",
         "Oh - no. I'm sorry. I know that's hard to hear. I just... I can't. Please don't ask me again.",
         "I - no. I'm shaking just saying it, but no. Some things aren't mine to give. Not even kindly.",
         "Please don't. I'd lose sleep, and I'll already lose sleep saying this. The answer is no. Sorry.",
         "No... I said it. My hands are all shaky now, but I said it. Please be the kind who understands.",
         "I don't have much, and what I have, I hold carefully. So: no. Carefully. But firmly. No.",
         "I'm not the hero of this story. Heroes share everything. I share most things. This one's a no.",
         "You picked the quiet one thinking I'd fold. I fold at cards, not at this. No. Oh, I'm sorry.",
         "If you need help, I'll help. Carry things, watch fires. But this? This I can't. Forgive me.",
         "No. There. My whole body hurts from that one word, but it stands. Please don't make me repeat it.",
         "I'll say it softly so it's easier: no. And I'll hug my purse while I say it, so you know it's true."},
        // ARCHETYPE_NOBLE
        {"With respect, I must decline. My oath binds what is mine to give, and it is not yours to claim.",
         "I am sorry - no. What I carry is entrusted to me, and I will not surrender it. Ask anything else.",
         "I must decline, and I do so with regret. What you ask is not mine to relinquish, at any price.",
         "My answer is no. I would remind you that courtesy costs nothing, and that I extend it regardless.",
         "There is dignity in honest refusal. Accept mine as the honest thing it is, and let us part warmly.",
         "No. I am sworn to steward what I carry, not to surrender it to the first persuasive voice I meet.",
         "You mistake generosity for obligation. I am generous by choice. In this, I choose to decline.",
         "I will not be moved by persistence where I was not moved by merit. The answer stands: no.",
         "Ask me for my time, my counsel, or my sword arm - any of these I lend freely. This, I do not.",
         "Consider how rare a thing an honest no has become. Prize it accordingly, and press me no further.",
         "The request overreaches. My refusal does not - it is measured, considered, and final. Good day.",
         "Duty binds me elsewhere. My purse and my person are already spoken for - by oaths older than you."},
        // ARCHETYPE_ROGUEISH
        {"Ha! I like your nerve. No, though. Around here, we trade - favours, coin, secrets. Nothing's free, friend.",
         "You've got guts, asking that. The answer's no - but here's one for free: don't ask the next person either.",
         "Ha! No. But I admire the pitch. Truly. If you ever go straight, come find me - you've got a future.",
         "The answer's no, the delivery was decent, and the tip is free: nobody rich asks this hard.",
         "I don't give, I trade. And you've nothing I want that you'd still like me after.",
         "See, in my line, 'no' is a complete sentence. In yours it clearly isn't. Let's both practice: no.",
         "Bold. Wrong, but bold. The last person who pried this hard left lighter than they came. No.",
         "I like you, which is why you're hearing a friendly no. Enemies of mine get a much shorter word.",
         "No. And that's the free sample. My real refusals come with consequences and a service charge.",
         "If no were a lock, you'd be a fine pickpocket. Lucky for me, it isn't, and you aren't. No.",
         "Ask my coin? It says no. Ask my advice? Never ask twice for the same thing. Ask my time? Busy.",
         "Tell you what: buy me a drink, and I'll tell you the story of the last person who asked. Sobering."},
    };
    static char const* const calmPublic[4][12] = {
        // ARCHETYPE_GRUFF
        {"Enough! Lower the steel. There's ale enough for every throat in this room without spilling a drop of blood.",
         "Hold! The next one to swing answers to me. Sit down, all of you, and settle it with words.",
         "Enough noise. Sheathe it, sit down, and say what's actually eating you. Words are cheaper than blood.",
         "Nobody here wants to die over this. Trust me, I've checked. Drinks on the loud one. Sit.",
         "I've seen how this ends - the floor, the broom, the grave, in that order. Put it away. All of it.",
         "Whatever was said, it was said hot. Let it cool. Nothing worth this gets settled standing.",
         "That's enough swinging-room. Everyone take one step back and think about their evening plans.",
         "Steel first is the coward's arithmetic. Add the sums: one insult against one funeral. Sit down.",
         "If it's blood you want, the road's full of monsters that oblige. Save your edge for them.",
         "Stand down, all of you. The first one to swing answers to me, and I promise I'm the worst option.",
         "Sit. Down. The ale's paid for, the night's young, and corpses drink nothing. Choose wisely.",
         "You there - lower it. And you. Good. Now everyone pretend this was a misunderstanding. It usually is."},
        // ARCHETYPE_SHY
        {"P-please, everyone, stop... there's no need for this. Whatever's wrong, we can talk it through. Can't we?",
         "Please - please put them away! Someone will get hurt, and... and it won't fix anything.",
         "P-please... put them down? Nobody has to be hurt today. I believe that. Please believe it too.",
         "Oh no, oh no - everyone just... breathe? Please? Whatever it was, it can't be worth this. It can't.",
         "I'm nobody, I know, but - but listen: you can still walk away from this. Both of you. Please walk.",
         "Somebody's mother is going to cry tonight. Please, please don't let it be over words. Sit down.",
         "I'm scared of almost everything, and even I can see this is a bad idea. Weapons down? Thank you.",
         "H-here, I'll stand between you. Look - nothing happened. I'm fine. See? Everyone can be fine.",
         "You're both shaking. I shake too - it means we're human. Put them away and be human together.",
         "Think of the tapster - blood is awful to scrub. And the road is awful to die on. Choose the ale.",
         "It's not too late to be kind. It never is, until it is. Please, please let it not be too late.",
         "Shh. Lower your voice and your blade at the same speed. There. Now wasn't that easier than bleeding?"},
        // ARCHETYPE_NOBLE
        {"Sheathe your weapons, all of you. No grievance here is settled by blood - speak your piece, and let us find the honourable road.",
         "Enough! Stand down, on my word. Every blade lowered today is a life kept - let reason hold the field.",
         "As a matter of honour, I claim first voice: every blade in this room sheathes while I speak.",
         "You are angry, and justly. But justice rendered in temper is merely damage with a clear conscience.",
         "Lower them. Whatever grievance lives between you, it will keep until morning. Dead men keep nothing.",
         "I have watched honourable men die over slights a sentence could have mended. Speak, do not swing.",
         "This house deserves better, and so do you. Sheathe, sit, and let reason reclaim what rage has taken.",
         "Stand down, and stand tall for doing it. Courage is not the blade - it is the hand that stays.",
         "If blood must decide this, it will still decide it tomorrow. Let words have first hearing tonight.",
         "The first wound writes the rest of the story. Refuse the pen. Sheathe, and I will hear you both.",
         "On my word as a noble: who sheathes first earns my ear tonight. Who swings first earns my steel.",
         "There is no shame in restraint. There is only shame in what restraint could have prevented."},
        // ARCHETYPE_ROGUEISH
        {"Whoa, whoa - let's not do anything we'll regret before breakfast. I've seen this end badly for everyone holding steel. Walk away.",
         "Easy - easy! Whatever this is about, it isn't worth the burial costs. Blades down, and we'll talk terms.",
         "Right, everyone's very pretty with steel out, but have we priced funerals lately? Sit down. Sit. Down.",
         "Quick maths: he's got friends, you've got friends, the ale's still full. Nobody's winning tonight.",
         "I've seen this play before. Act one, knives. Act two, guards. Act three, everybody's wallet's lighter.",
         "Easy, easy. If anyone bleeds, it's coming out of somebody's hide, and the tapster doesn't do refunds.",
         "Tell you what: finish your drinks, hate each other outside, and I'll sell tickets at half price.",
         "That look says kill. The empty purse says listen. Walk away rich in the only coin that matters.",
         "Put them away, lads. I've cheated better men than either of you out of worse tempers than this.",
         "The night's young and the exits are numbered. Nobody needs to find out which one they'd leave by.",
         "Down, down. Rage is a fine servant and a terrible guest. And this is somebody's house. Behave.",
         "Hate each other on the morrow if you must. But tonight the drinks are poured and the steel stays in."},
    };
    static char const* const calmWhisper[4][12] = {
        // ARCHETYPE_GRUFF
        {"Aye, I'll talk them down. Stay behind me - this won't take long.",
         "Leave it to me. One good shout and half of them will remember urgent business elsewhere.",
         "Consider it handled. You stand behind me and try to look shocked at whatever I say. I'm good at this.",
         "Aye, I'll talk them round. Fists are for the last page of the story, and I write long beginnings.",
         "Go on, get behind me. A few sharp words now saves a lot of sharp steel in ten minutes. Watch.",
         "I'll sort it. And when it's sorted, you owe me the story of what started it. I collect those.",
         "Leave them to me. Angry men are just tired men with an audience. I'll empty the stands.",
         "Stay back. If it goes wrong, you're the backup. It won't go wrong. It rarely goes wrong. Usually.",
         "I'll un-knot them. Give me a minute and a reason to buy a round, and this never happened.",
         "Right - my way first: talk. Your way's the fallback. Try to look disappointed we're not using it.",
         "Trust me. Half of every fight is an audience. I'll disperse the audience and the fight gets bored.",
         "Watch this. Words first, walnuts later. Either way, by the bell there'll be nothing to bury."},
        // ARCHETYPE_SHY
        {"I'll... I'll try. Just stay close to me, all right? It will be okay.",
         "O-okay. I'll ask them to stop. I'm not very good at this, but... I'll try.",
         "I'll... I'll try. For you I'll try. Stay where I can see you, and if I faint, catch me quietly.",
         "O-okay. I'll say something soft. Sometimes soft is the loudest thing in the room. I hope it is.",
         "I'll go. Don't let me be brave alone too long though - be right there. Right there. Okay. Going.",
         "I can do this. Probably. If my voice squeaks, ignore it. The message will still land. I think.",
         "Leave it to me. I'm very good at apologizing for other people. It's a strange gift, but it works.",
         "I'll ask them kindly to stop. If that fails, I'll ask them unkindly. Well - as unkindly as I can.",
         "Stay close. You being there makes me twice as brave. That's just... how you work on me. Okay, going.",
         "If my knees knock loud enough to give me away, start a hymn. Loudly. That's the whole plan.",
         "Right - plan: I talk, they listen, everyone keeps their blood inside. Simple. I'll go now. Now.",
         "D-don't watch too closely, you'll make me nervous. Or do watch. It makes me brave. One of those."},
        // ARCHETYPE_NOBLE
        {"I will speak with them - a calm word carries further than a drawn blade. Stay close.",
         "Allow me. If there is honour left in them, I will find it with words.",
         "Grant me the room, and I will grant you a peaceful evening. A calm word carries farther than steel.",
         "I shall speak with them. If honour lives in them, I will find it. If it does not, they will find me.",
         "Stay close and watch how words behave when they are properly dressed. This is my country, in a way.",
         "I will mediate. Should my diplomacy falter, my surname will finish the sentence. Either way, peace.",
         "Leave the talking to me. Negotiation is simply combat for people patient enough to win slowly.",
         "Watch, and learn the better art: a drawn blade threatens one man. A well-made offer empties a room.",
         "I shall defuse this with courtesy. And should courtesy fail, I shall escalate to more courtesy.",
         "By the time I finish, they will believe peace was their own idea. Allow me that small vanity.",
         "Rest easy. I have never lost an argument worth winning, and this one appears worth winning.",
         "Steel frightens the guilty and the innocent alike. Words need only frighten the first kind."},
        // ARCHETYPE_ROGUEISH
        {"On it. Give me a breath - half these fights end the moment nobody wants to swing first.",
         "Watch this - I'll have them sharing a drink in a minute. Nobody fights someone they've laughed with.",
         "On it. By the time the drinks arrive this will be a story about an argument nobody remembers having.",
         "Watch a professional. Nobody fights someone they've laughed with, and I'm about to be hilarious.",
         "I'll talk them down. Failing that, I'll confuse them down. Failing that - well, bring up my tab.",
         "Give me a minute. There's a version of this where everyone's a hero and I get paid in gratitude.",
         "I've got this. The trick is agreeing with everybody until they agree with each other. Works always.",
         "Stand there and nod at whatever I say. Together we're about to be extremely persuasive. Go.",
         "Don't worry, I know the peace words in six dialects and the threat words in every single one.",
         "Half these boys want an exit, not a fight. I deal in exits. Watch me open the door for them.",
         "I'll sweet-talk the rage right out of them. And if sweet fails, my tab at the bar is a hostage.",
         "This is my finest work, watching me do it. Try to look impressed at the right moments."},
    };

    char const* const* cell = nullptr;
    switch (category)
    {
        case CATEGORY_VOUCH:
            cell = whisper ? vouchWhisper[archetype] : vouchPublic[archetype];
            break;
        case CATEGORY_REFUSE:
            cell = refuseLine[archetype];
            break;
        case CATEGORY_DEESCALATE:
            cell = whisper ? calmWhisper[archetype] : calmPublic[archetype];
            break;
        default:
            return "";
    }
    pocketllm::BanterResult const r =
        pocketllm::SelectLine(stateRef.state, cell, 12, nullptr, 0, 0, 0);
    std::string line = r.line ? r.line : "";
    if (!line.empty())
        ApplyTic(stateRef.state, botGuid, line);
    return line;
}

bool PlayerbotLlmPersona::TryFallback(Player* bot, std::string const& message, bool whisper, std::string& line)
{
    HardCategory const category = Classify(message);
    if (category == CATEGORY_NONE)
        return false;
    line = FallbackLine(bot, category, whisper);
    return !line.empty();
}

std::string PlayerbotLlmPersona::BusyReply(uint32 botGuid)
{
    // banter off keeps the plain configured placeholder - the recency-ring
    // busy pool is part of the authored layer the toggle gates
    if (!sPlayerbotAIConfig.llmBanterEnabled)
        return sPlayerbotAIConfig.llmBusyReply;
    // the governor-busy placeholder through the same recency ring as the
    // persona cells: a busy storm cycles the pool instead of the old
    // botGuid+counter rotation that could repeat one line back to back
    uint64_t const stateKey = ((uint64_t)botGuid << 24) | (uint64_t)(pocketllm::POOL_BUSY + 1);
    StateRef stateRef = StateFor(stateKey, botGuid);
    size_t count = 0;
    char const* const* lines = pocketllm::Pool(pocketllm::POOL_BUSY, count);
    pocketllm::BanterResult const r =
        pocketllm::SelectLine(stateRef.state, lines, count, nullptr, 0, 0, 0);
    if (!r.line)
        return sPlayerbotAIConfig.llmBusyReply;
    std::string line(r.line);
    ApplyTic(stateRef.state, botGuid, line);
    return line;
}

std::string PlayerbotLlmPersona::KillBanterLine(Player* bot, Player* killer)
{
    if (!bot || !killer)
        return "";
    uint64_t const stateKey = ((uint64_t)bot->GetGUIDLow() << 24) | (uint64_t)(pocketllm::POOL_KILL + 1);
    StateRef stateRef = StateFor(stateKey, bot->GetGUIDLow());
    size_t count = 0;
    char const* const* lines = pocketllm::Pool(pocketllm::POOL_KILL, count);
    pocketllm::BanterResult const r =
        pocketllm::SelectLine(stateRef.state, lines, count, nullptr, 0, 0, 0);
    if (!r.line)
        return "";
    std::string line = Rendered(r.line, killer, bot);
    ApplyTic(stateRef.state, bot->GetGUIDLow(), line);
    return line;
}

std::string PlayerbotLlmPersona::GreetingLine(Player* bot, Player* player)
{
    if (!bot || !player)
        return "";
    // the deterministic relationship state picks the warmth; the corpus
    // phrases it (the model is not involved, so the welcome is free)
    std::string const tier = PlayerbotLlmMemory::GetRelationshipTier(bot, player);
    pocketllm::PoolId pool = pocketllm::POOL_GREET_STRANGER;
    if (tier == "acquaintance")
        pool = pocketllm::POOL_GREET_ACQUAINTANCE;
    else if (tier == "ally")
        pool = pocketllm::POOL_GREET_ALLY;
    else if (tier == "trusted")
        pool = pocketllm::POOL_GREET_TRUSTED;
    uint64_t const stateKey = ((uint64_t)bot->GetGUIDLow() << 24) | (uint64_t)(pool + 1);
    StateRef stateRef = StateFor(stateKey, bot->GetGUIDLow());
    size_t count = 0;
    char const* const* lines = pocketllm::Pool(pool, count);
    pocketllm::BanterResult const r =
        pocketllm::SelectLine(stateRef.state, lines, count, nullptr, 0, 0, 0);
    if (!r.line)
        return "";
    std::string line = Rendered(r.line, player, bot);
    ApplyTic(stateRef.state, bot->GetGUIDLow(), line);
    return line;
}

bool PlayerbotLlmPersona::MaybeAmbientLine(Player* bot)
{
    if (!bot || !bot->IsInWorld() || !sPlayerbotAIConfig.llmEnabled ||
        !sPlayerbotAIConfig.llmBanterEnabled)
        return false;
    PlayerbotAI* ai = bot->GetPlayerbotAI();
    if (!ai || bot->IsInCombat())
        return false;
    Player* master = ai->GetMaster();
    if (!master || !master->isRealPlayer() || !bot->IsWithinDistInMap(master, 20.0f))
        return false;
    if (!AmbientCheckDue(bot->GetGUIDLow()))
        return false;
    // the rare roll: 1-in-16 of the 90-second checks ever reach here
    if (urand(1, 16) != 1)
        return false;
    // shared budget with kill banter: one bot-initiated line per 15 minutes
    if (!PlayerbotLlmMemory::TryClaimAmbientSlot(bot->GetGUIDLow(), 900))
        return false;

    // idle chatter is the everyday pool; the five moods split the rest; the
    // wildcard bank rides on every draw at its design 1% (SelectLine's own
    // roll, on its own cooldown, ring-tracked so even chaos does not repeat)
    static pocketllm::PoolId const kPools[] = {
        pocketllm::POOL_IDLE, pocketllm::POOL_IDLE,
        pocketllm::POOL_MOOD_BORED, pocketllm::POOL_MOOD_HOMESICK, pocketllm::POOL_MOOD_NIGHTWEARY,
        pocketllm::POOL_MOOD_COINHEAVY, pocketllm::POOL_MOOD_BLOODDRUNK,
    };
    pocketllm::PoolId const pool = kPools[urand(0, uint32(sizeof(kPools) / sizeof(kPools[0])) - 1)];
    uint64_t const stateKey = ((uint64_t)bot->GetGUIDLow() << 24) | (uint64_t)(pool + 1);
    StateRef stateRef = StateFor(stateKey, bot->GetGUIDLow());

    size_t wildCount = 0;
    char const* const* wild = pocketllm::Pool(pocketllm::POOL_WILDCARD, wildCount);
    size_t count = 0;
    char const* const* lines = pocketllm::Pool(pool, count);
    pocketllm::BanterResult const r = pocketllm::SelectLine(stateRef.state, lines, count, wild, wildCount,
        SteadyMs(), 0);
    if (!r.line)
        return false;
    std::string line = Rendered(r.line, master, bot);
    ApplyTic(stateRef.state, bot->GetGUIDLow(), line);
    if (line.empty())
        return false;

    if (bot->GetGroup())
        ai->SayToParty(line);
    else
        bot->Say(line, LANG_UNIVERSAL);
    PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(),
        0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_PARTY), true,
        bot->GetName(), line);
    return true;
}

bool PlayerbotLlmPersona::IsSimpleGreeting(std::string const& message)
{
    static char const* const kGreetings[] = {
        "hi", "hello", "hey", "hiya", "yo", "hail", "sup",
        "greetings", "well met", "good morning", "good evening", "good day",
    };
    // trim trailing punctuation/whitespace: "hello!" and "well met," qualify
    std::string text = Lower(message);
    while (!text.empty() && !std::isalnum(static_cast<unsigned char>(text.back())))
        text.pop_back();
    if (text.empty() || text.size() > 24)
        return false;
    for (char const* g : kGreetings)
        if (text == g)
            return true;
    // "hello there" / "hi <botname>": greeting plus at most one short
    // follower word - anything longer is a real message, not a greeting
    size_t const space = text.find(' ');
    if (space == std::string::npos || text.find(' ', space + 1) != std::string::npos)
        return false;
    std::string const first = text.substr(0, space);
    for (char const* g : kGreetings)
        if (first == g)
            return true;
    return false;
}
