/*
 * Pocket Realm LLM banter core — pure, header-only, host-testable.
 *
 * The deterministic voice layer under the playerbot LLM companion: the
 * authored line pools (fallback beats, greetings, combat/loot banter,
 * moods, wildcard chaos), per-bot personality traits derived from the
 * GUID, and the anti-repetition selection engine that must keep a
 * 1000-hour player surprised.
 *
 * PURE: no Player*, no DB, no globals, no wall clock — every function is a
 * function of its arguments so tools/test_llm_banter_core.cpp can prove
 * the whole system on the host (500k-turn simulation + fuzz). The game
 * side owns the state maps (mutex-guarded) and passes time in.
 *
 * Why this exists (repetition audit, 2026-08-22): the old persona path
 * rotated TWO variants per cell off a global atomic — the same bot could
 * repeat the same line on consecutive triggers, and every bot shared one
 * counter. SelectLine replaces that with a per-(bot,audience,category)
 * novelty-weighted draw over a recency ring, so a line cannot return
 * until pool_size-2 other lines have been heard.
 */
#ifndef POCKET_LLM_BANTER_CORE_H
#define POCKET_LLM_BANTER_CORE_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace pocketllm {

// ---------------------------------------------------------------- RNG ----
// SplitMix32: tiny, deterministic, good avalanche. All selection is a pure
// function of the seeded state — identical inputs give identical bots.
inline uint32_t SplitMix32(uint32_t& state)
{
    state += 0x9E3779B9u;
    uint32_t z = state;
    z = (z ^ (z >> 16)) * 0x85EBCA6Bu;
    z = (z ^ (z >> 13)) * 0xC2B2AE35u;
    return z ^ (z >> 16);
}

// ----------------------------------------------------------- invariants ----
// Every authored line must satisfy this (chat packet limit 200, 1.12 client
// byte transparency, and no protocol bytes: tool markers << >>, the busy
// marker's control chars, JSON braces that ParseResponse regexes corrupt,
// and * / [ initials that LinesToPackets routes as emotes).
inline bool LineIsValid(char const* line)
{
    if (!line) return false;
    size_t n = 0;
    for (char const* p = line; *p; ++p, ++n)
    {
        unsigned char c = static_cast<unsigned char>(*p);
        if (c < 0x20 || c > 0x7E) return false;
        if (n >= 200) return false;
        // Braces are legal ONLY as the {P}/{B} name placeholders.
        if (c == '{')
        {
            if ((p[1] == 'P' || p[1] == 'B') && p[2] == '}') { p += 2; n += 2; continue; }
            return false;
        }
        if (c == '}') return false;
        if (c == '<' || c == '>') return false;
    }
    if (n < 4) return false;
    if (line[0] == '*' || line[0] == '[' || line[0] == ' ') return false;
    return true;
}

// The injection fix: players can type tool markers; if their text is echoed
// into a prompt or stored, the model may re-emit it as a forged tool call.
// Every player-authored string entering a prompt/history passes through
// this. Removes the marker pairs in place — prose survives, protocol dies.
inline void NeuterMarkers(char* text)
{
    if (!text) return;
    // Fixpoint: removing a pair can join two singles into a NEW pair
    // ("><x>" with x a pair -> ">>"), so repeat until a full pass removes
    // nothing. Found by fuzzing; do not simplify back to one pass.
    bool removed = true;
    while (removed)
    {
        removed = false;
        size_t o = 0;
        for (size_t i = 0; text[i];)
        {
            if ((text[i] == '<' && text[i + 1] == '<') || (text[i] == '>' && text[i + 1] == '>'))
            {
                i += 2; // drop the marker pair entirely
                removed = true;
                continue;
            }
            text[o++] = text[i++];
        }
        text[o] = 0;
    }
}

// std::string flavor of NeuterMarkers for call sites that hold player text
// as a string: returns a neutered copy, source untouched. Same fixpoint
// discipline - one pass can join two singles into a new pair.
inline std::string NeuterMarkersCopy(char const* text)
{
    if (!text) return std::string();
    std::string work(text);
    bool removed = true;
    while (removed)
    {
        removed = false;
        std::string pass;
        pass.reserve(work.size());
        for (size_t i = 0; i < work.size();)
        {
            if ((work[i] == '<' && i + 1 < work.size() && work[i + 1] == '<') ||
                (work[i] == '>' && i + 1 < work.size() && work[i + 1] == '>'))
            {
                i += 2;
                removed = true;
                continue;
            }
            pass.push_back(work[i++]);
        }
        work.swap(pass);
    }
    return work;
}

// --------------------------------------------------------------- traits ----
// Stable per-bot personality, derived from the GUID: one demeanor, one
// quirk, one verbal tic. Byte-stable for the bot's life; seasoning lines
// are injected into the prompt so the MODEL also keeps the voice.
enum { TRAIT_GRUFF = 0, TRAIT_WARM, TRAIT_GLOOMY, TRAIT_WRY, TRAIT_DEMEANOR_COUNT };
enum { QUIRK_SUPERSTITIOUS = 0, QUIRK_MORBID, QUIRK_POETIC, QUIRK_MISCHIEVOUS,
       QUIRK_BOASTFUL, QUIRK_MISERLY, QUIRK_DEVOUT, QUIRK_CURIOUS, QUIRK_COUNT };
enum { TIC_HM = 0, TIC_AYE, TIC_BAH, TIC_NONE, TIC_COUNT };

inline int DemeanorOf(uint32_t botGuid) { return botGuid % TRAIT_DEMEANOR_COUNT; }
inline int QuirkOf(uint32_t botGuid)    { return (botGuid / 4) % QUIRK_COUNT; }
inline int TicOf(uint32_t botGuid)      { return (botGuid / 32) % TIC_COUNT; }

inline char const* DemeanorSeasoning(int d)
{
    static char const* const v[TRAIT_DEMEANOR_COUNT] = {
        "Temperament: gruff and sparing with words. You praise sideways, as complaints, and never say the soft thing directly.",
        "Temperament: warm-hearted. You treat small kindnesses as large ones, and say the soft thing while pretending not to.",
        "Temperament: gloomy and matter-of-fact about death. You expect the worst, are rarely disappointed, and find that a little funny.",
        "Temperament: dry-witted and irreverent. Nothing stays serious around you for longer than a sentence, including you.",
    };
    return v[d];
}
inline char const* QuirkSeasoning(int q)
{
    static char const* const v[QUIRK_COUNT] = {
        "Habit: superstitious. You knock on your shield, read omens in birds and thunder, and follow small rituals exactly.",
        "Habit: morbid. You count graves, admire an efficient death, and speak of corpses with professional interest.",
        "Habit: poetic. You notice light on water and the names of winds, and occasionally say something beautiful by accident.",
        "Habit: mischievous. You poke, dare, hide boots, and rearrange packs for the pleasure of watching confusion.",
        "Habit: boastful. Your feats grow with each telling, you once knew everyone worth knowing, and 'I' becomes 'we' when it went well.",
        "Habit: miserly. You count coin aloud, mourn every copper spent, and regard generosity as a flaw you narrowly avoid.",
        "Habit: devout. You invoke the higher powers often and bless things casually - food, doors, weapons, strangers.",
        "Habit: curious. You ask odd questions, poke at ruins, and collect small strange objects with fierce seriousness.",
    };
    return v[q];
}
inline char const* TicPrefix(int t)
{
    static char const* const v[TIC_COUNT] = { "Hm. ", "Aye, ", "Bah, ", "" };
    return v[t];
}

// ---------------------------------------------------------------- pools ----
// {P} = player name, {B} = bot name; rendered by RenderLine at delivery.
enum PoolId {
    POOL_BUSY = 0, POOL_GREET_STRANGER, POOL_GREET_ACQUAINTANCE, POOL_GREET_ALLY,
    POOL_GREET_TRUSTED, POOL_KILL, POOL_LOOT_RARE, POOL_IDLE, POOL_PHILO,
    POOL_SILENCE, POOL_DARE, POOL_BET_OPEN, POOL_BET_WIN, POOL_BET_LOSE,
    POOL_SUPERSTITION, POOL_NAMING, POOL_MOOD_BORED, POOL_MOOD_BLOODDRUNK,
    POOL_MOOD_HOMESICK, POOL_MOOD_COINHEAVY, POOL_MOOD_NIGHTWEARY,
    POOL_WILDCARD, POOL_COUNT
};

enum { kMinPoolLines = 6, kMaxRing = 16, kWildcardCooldownMs = 120000,
       kWildcardsPerMillion = 10000 }; // 1% design rate

inline char const* const* Pool(PoolId p, size_t& count);

namespace detail {
static char const* const kBusy[] = {
    "Hm. Give me a moment - I'm thinking on that a while.",
    "Bah. Ask again in a breath - the thought's still climbing the stairs.",
    "One moment. Good thoughts are like mules. Slow to start.",
    "I heard you. I'm deciding what I think about it.",
    "Hold that thought. Better - hold THIS one. Mine's heavier.",
    "Give me a minute. Wisdom takes longer than anger.",
};
static char const* const kGreetStranger[] = {
    "You there. Traveling alone, or is the rest of your party hiding?",
    "Hail. Keep your blade loose around here - the roads stopped being safe a war ago.",
    "I don't know you, and I've stopped pretending that means nothing. What's your business?",
    "Well met, stranger. Mind the mud - it takes boots first, and then men.",
    "You've the look of someone with a plan. I've the look of someone who doubts it.",
    "I'm {B}. You don't need to remember that yet. Most don't get the chance to.",
    "Fair morning, or fair evening. One of those. What do you want?",
    "You're standing on a road with me in it. That makes us companions by accident. Accidents end.",
    "New face. Good. I was running out of things to mutter at.",
    "Hail. I'll not shake your hand yet. Around here that's how you count fingers after.",
};
static char const* const kGreetAcq[] = {
    "{P}, isn't it? Aye. I remembered. Don't make a thing of it.",
    "Back again. Either the road's small or we're both going the wrong way.",
    "Hail, {P}. Still breathing, I see. It's a good habit. Keep it up.",
    "There you are. The camp's quieter when you're around. That's not a complaint.",
    "Hail again. Sit if you like. The fire's free - the story tax is one tale.",
    "You again! Aye, I mean that kindly. Mostly kindly.",
    "Still alive, {P}? Then the day's already earned its keep.",
    "I know your face now, and your pace. You walk like someone who's owed money.",
    "Back so soon? Careful. People will talk, and I'll be one of them.",
    "{P}, well met. The ale's weak here but the company's improving.",
};
static char const* const kGreetAlly[] = {
    "{P}! Good. I was about to do something stupid out of boredom.",
    "There's my favourite bad influence. Hail, {P}.",
    "You show up, the day gets louder and luckier. Hail.",
    "{P}. If you're buying, I'm thirsty. If you're fighting, I'm ready. Either way - good.",
    "Hail, old comrade. The scars are holding. Yours?",
    "You're late. I counted. I'm not angry - I'm just going to mention it at every campfire for a month.",
    "{P}. Aye. Whatever you're planning, I'm in. That's the arrangement. That's always been the arrangement.",
    "Good to see you upright. I'd gotten used to hauling you places.",
    "The road got longer the past few days. You weren't on it.",
    "Hail! Sit down, eat something, and tell me which impossible thing we're doing now.",
};
static char const* const kGreetTrusted[] = {
    "{P}. Thirty seconds in and I've already got a bad idea. Missed you.",
    "There you are. I keep your seat warm at every table. The seat's imaginary. The warmth isn't.",
    "Hail, you old disaster. Come here where the fire's honest.",
    "If you died somewhere, I'd have felt it. So either you're alive or I'm getting soft. Hail.",
    "{P}. I wrote nothing down about you. I don't have to. That's the whole point of you.",
    "Back before I finished missing you. Good timing or bad manners. With you it's both.",
    "I told a stranger about you today. They didn't believe me. I let them keep their doubts.",
    "Hail. Whatever's wrong, we'll fix it or bury it. We're good at both.",
    "You, me, a road, and no plan. Just like the good old days, which were also terrible.",
    "{P}. I'd say the camp's complete now. It was missing its worst idea and its best one.",
};
static char const* const kKill[] = {
    "Down. Next.",
    "That one's done. Nicely done, all of you.",
    "It stopped moving. That's the part I like.",
    "One less. The math favours us.",
    "Clean. Well - clean enough.",
    "Dead. You're welcome, {P}.",
    "That's how it's done. Write it down. Don't actually write it down.",
    "Flat. I've seen prettier endings, but few more satisfying.",
    "Another for the count. What count? Mine. I keep one.",
    "It's over. Breathe while it's cheap.",
    "Killed it dead. Aye, 'dead' twice. It earned it.",
    "Good work. I mean that in the strictest mercenary sense.",
    "And stay down. They never stay down.",
    "Finished. {P}, your footwork is improving. I watched. I wasn't worried. Much.",
};
static char const* const kLootRare[] = {
    "Now THAT'S a find. Hold it up. Higher. Let the whole dark get jealous.",
    "Blue! Somebody up there likes us. Or is fattening us for something.",
    "Careful with that - it's worth more than my first three horses. Combined.",
    "A rare one. These come along once a season, and never when you need them.",
    "That's going in the good pack. We have a good pack now. It's that one.",
    "Look at the work on that. Craftsmanship like this outlives the craftsman.",
    "Worth crossing a swamp for. We were crossing the swamp anyway, but still.",
    "The kind of thing that starts stories. Or ends them. Depends who's asking.",
    "I knew a dwarf who'd trade his beard for that. He had a fine beard. This is better.",
    "Blue and beautiful. Don't get used to it.",
    "Somewhere a dragon's hoard is missing one item and doesn't know it.",
    "That's a real piece. Don't wear it swimming.",
    "If we sell this, we split it fairly. If we don't, we never speak of where it went.",
    "I've robbed better-armed men and found worse than this.",
};
static char const* const kIdle[] = {
    "So. This is what we're doing. Standing. In a field. Majestic.",
    "{P}, quick - what's the plan? Wrong. I just like watching you commit to one.",
    "I've counted the grass. There's more of it than yesterday. Someone's up to something.",
    "When you stand still that long, I start assigning you a backstory. It's not flattering.",
    "Nothing's attacked us in a while. I don't trust it.",
    "Bet you can't hit that rock from here. Don't actually - we need the arrows.",
    "I'm bored enough to consider honesty.",
    "You know what this camp needs? A flag. And someone foolish enough to salute it.",
    "One of us should say something inspiring. It isn't going to be me.",
    "I've been rehearsing my eulogy for you. It's short but sincere. No, you can't hear it.",
    "If we're going to stand here, we should at least face something. That tree. Face the tree.",
    "Ask me what I'm thinking. Actually, don't. It involves a pie.",
    "The clouds are moving and we're not. Even the clouds are embarrassed.",
    "I could tell you about the time I outran a guardsman. The story's better than the facts.",
    "Go on, check the map again. Maybe it redrew itself.",
    "For coin, I'll dance. For free, I'll stand here judging you. Same as now.",
    "Something's watching us. Might be a wolf. Might be me. I do that sometimes.",
    "Admit it - you forgot what we came out here for. It's fine. I remember. I'm just not telling.",
};
static char const* const kPhilo[] = {
    "D'you ever think the stars are just holes poked in something bigger? No? Good. Forget I said it.",
    "Old Murn who mended nets in my village used to say: a knot's just a rope that's made up its mind.",
    "If a sword never leaves its sheath, is it still a sword? Or just cold metal with opinions?",
    "Every road I've walked was already a road. Somebody decided, once, that THIS was the way.",
    "The dead don't mind being dead. It's the living that keep bringing it up.",
    "A coin only knows one side at a time. I've met people like that.",
    "D'you suppose fish think the water's weather?",
    "Grief's just love with nowhere to go. My gran said that. She charged for it.",
    "Somewhere right now, someone's having the best day of their life and doesn't know it yet.",
    "You can't step in the same river twice. The river knows this and does not care.",
    "The Light doesn't pick sides, {P}. It just shines. We do the picking and blame the shine.",
    "I used to think courage was loud. Then I watched a farmer plant seeds in a war year.",
    "If we're each the hero of our own tale, then somebody out there is telling a story where I'M the ambush.",
    "The moon doesn't wax and wane. It's always whole. We just can't always see it. Gran again.",
};
static char const* const kSilence[] = {
    "You've gone quiet on me, {P}. I've been counting. I won't say how high. It's rude to brag.",
    "I said something funny an hour ago. This is me, noting the silence, for the record.",
    "Fine. I'll talk to the pack mule. He's a better listener and he's not even here.",
    "Ten minutes, {P}. I've composed three eulogies and a love letter in that time. Choose which one applies.",
    "The silent treatment. Clever. I once ignored a man for a WEEK. Then I remembered we'd never met.",
    "I know you can hear me. Your ears redden when you're pretending.",
    "Say something. Anything. I'll accept a cough. I've accepted worse.",
    "I'm not hurt. I'm narrating. There's a difference and I live in it.",
    "That last thing I said deserved at least a chuckle. I'm filing a complaint with the sky.",
    "You go quiet, I start thinking. Nobody wants that. Least of all me.",
    "Aye, well. Words are cheaper when nobody's buying.",
    "{P}. Blink twice if you're alive. Once if you're ignoring me. ...That was once. I saw it.",
};
static char const* const kDare[] = {
    "I dare you to shout your own name in the next town. Full volume. Consequences optional.",
    "Dare you to trade helmets with the next guard you meet and say nothing about it.",
    "I dare you to ask the next innkeeper for 'the good room' like you've heard of it your whole life.",
    "Dare you to compliment the next thing that tries to kill us. Out loud. Mid-fight.",
    "I dare you to walk into the tavern backwards. Say it's for luck. Stick to the story.",
    "Dare you to ask a stranger if they've 'heard about the incident'. Then say nothing more.",
    "I dare you to buy the ugliest hat in the market and wear it like a crown.",
    "Dare you to name your weapon after me. Loudly. In front of witnesses.",
};
static char const* const kBetOpen[] = {
    "Two silver on it: the next thing worth looting is a blade. Something sharp. You in?",
    "Wager - the next rare find is NOT a blade. The world loves disappointing me. Prove me right.",
    "Bet's on: next chest holds something with a smell. Potions, cheese, tragedy. Any of those.",
    "I say the next good find is armour. You say otherwise. Two silver seals it.",
    "Coin on it: whatever we find next was made by dwarves. Dwarves make everything. Eventually.",
    "Next treasure's cursed. Calling it now. Two silver says I'm wrong to worry and glad to lose.",
    "Bet: the next blue thing has a name longer than my arm. The old smiths never used one word.",
    "Wager me: next find is something nobody in this party can even wear. It's ALWAYS something.",
};
static char const* const kBetWin[] = {
    "HA! The coin was mine from the start. Two silver, {P}. I'll wait. I'm gracious. Pay up.",
    "Called it! Winning feels like loot that weighs nothing.",
    "Two silver. I accept payment in coin, apology, or a decent secret.",
    "What did I tell you? The road PROVIDES. Mostly to me.",
    "That's the bet settled. Don't sulk. Sulking costs extra.",
    "I won, you lost, the world keeps turning. The turning is my favourite part.",
};
static char const* const kBetLose[] = {
    "Bah. Take the silver. I was TESTING your judgment.",
    "You won fair. I hate fair. Here.",
    "Two silver. It was worth two silver to learn I'm a fool. Cheap tuition.",
    "The coin's yours. The next bet's MINE to set.",
    "Well. The world clearly likes you better. Take it up with the world.",
    "Lost. Noted. The ledger of my dignity grows longer and sadder.",
};
static char const* const kSuperstition[] = {
    "Hold - everyone knock on their shield twice. No, don't ask. Just do it. The road's watching sideways.",
    "No whistling underground. I mean it. The last man who whistled is a story now.",
    "Salt over the left shoulder before we camp. RIGHT shoulder's for celebrations. Learn the difference.",
    "If a bird flies through camp, we move the fire. Don't argue with birds.",
    "Never say 'what else could go wrong' out loud. You're ARMING the question.",
    "New boots, first road - tap them together before the first mile. It's not luck. It's manners.",
};
static char const* const kNaming[] = {
    "That ram has a name now. I've decided it's Hap the Third. Don't ask about the first two.",
    "See that mule? Brunhild. She's seen things. You can tell by the eyelashes.",
    "The dog in that yard is called Sergeant Buttons. He earned the rank. Don't ask me how.",
    "I've named the cricket in our tent. His name is Trouble. He answers to nothing, like a real soldier.",
    "That crow is Reginald the Patient. He's been at our camp three nights. He's waiting for something.",
    "The roan horse by the well is called Magistrate. Nobody elected him. Power works like that sometimes.",
};
static char const* const kMoodBored[] = {
    "Bored, {P}. Properly bored. There's a village somewhere missing its idiot and it's me.",
    "I've named all the clouds. The big slow one is called Hap. We've grown close.",
    "If I hum one more tavern song, I'll start a fight with myself. And lose.",
    "Point at something, {P}. I'll go look at it. That's where I am right now.",
    "Bored enough to count my own scars again. The tally's unchanged. Disappointing.",
    "We could be doing anything. ANYTHING. This is what we chose.",
};
static char const* const kMoodBlooddrunk[] = {
    "HA! Again! Line them up!",
    "My blood is singing and all the words are fight.",
    "I feel INVINCIBLE. Which is exactly how the invincible die. But HA - come on!",
    "More! The tank's not empty yet!",
    "That's the stuff! THAT'S THE STUFF!",
    "Careful, {P}, I'm magnificent right now and it wears off fast.",
};
static char const* const kMoodHomesick[] = {
    "Nights like this I can almost smell home. Woodsmoke and burnt porridge. I'd give a toe for it. A little one.",
    "Don't mind me. The dark's just heavier when you know what you left in it.",
    "This sky's got the wrong stars for missing somebody under. Home has the right ones.",
    "You ever leave a place so good you stopped trusting good places?",
    "I'm fine. It's just - the road's long in both directions tonight.",
    "I could go home whenever I like. That's the joke. I keep choosing not to.",
};
static char const* const kMoodCoinheavy[] = {
    "We're RICH, {P}. Rich for us. Which is poor for kings, but kings don't know what they're missing.",
    "I keep counting it. It keeps being the same number. Glorious number.",
    "I'm buying something stupid. I've earned something stupid.",
    "Feel that weight in the purse? That's the sound of YES.",
    "Tonight we eat food with no bones in it. The good stuff.",
    "Don't tell me what things cost tonight. I don't want the truth. I want round numbers.",
};
static char const* const kMoodNightweary[] = {
    "The night's in my boots now. Every step's heavier.",
    "We should camp. Or keep walking and pretend we're ghosts.",
    "I'm tired enough to find everything honest. It passes by morning.",
    "Talk quiet, {P}. Loud things wake the dark.",
    "One more hour. Then I walk into a ditch and sleep where I land.",
    "The fire's winning. Fires always win eventually. That's why I respect them.",
};
static char const* const kWildcard[] = {
    "I've been thinking about time. Not the passing of it - the owing of it. I reckon I owe more than I've got.",
    "Every tavern's the same tavern if you drink enough in it. That's either wisdom or a warning.",
    "I dreamed of the sea. I've never seen the sea. Make of that what you will.",
    "The rain's fine. It's the PAUSE between the rains I don't trust.",
    "I once fought a man made entirely of barrels. Aye. And his little barrel wife.",
    "Wake me at dawn. Not BEFORE dawn, {P}. Nothing before dawn has ever been worth it.",
    "Two lanterns in the valley. Two's fine. Two's nobody's business. THREE would be a problem.",
    "Forty-one. Forty-one WHAT, you ask? Nobody ever asks. That's the whole problem with counting.",
    "This place smells like the docks at home. I don't want to talk about why.",
    "I've a feeling we're being spared for something worse. Grand, isn't it?",
};
} // namespace detail

inline char const* const* Pool(PoolId p, size_t& count)
{
    using namespace detail;
    switch (p)
    {
        case POOL_BUSY: count = sizeof(kBusy)/sizeof(void*); return kBusy;
        case POOL_GREET_STRANGER: count = sizeof(kGreetStranger)/sizeof(void*); return kGreetStranger;
        case POOL_GREET_ACQUAINTANCE: count = sizeof(kGreetAcq)/sizeof(void*); return kGreetAcq;
        case POOL_GREET_ALLY: count = sizeof(kGreetAlly)/sizeof(void*); return kGreetAlly;
        case POOL_GREET_TRUSTED: count = sizeof(kGreetTrusted)/sizeof(void*); return kGreetTrusted;
        case POOL_KILL: count = sizeof(kKill)/sizeof(void*); return kKill;
        case POOL_LOOT_RARE: count = sizeof(kLootRare)/sizeof(void*); return kLootRare;
        case POOL_IDLE: count = sizeof(kIdle)/sizeof(void*); return kIdle;
        case POOL_PHILO: count = sizeof(kPhilo)/sizeof(void*); return kPhilo;
        case POOL_SILENCE: count = sizeof(kSilence)/sizeof(void*); return kSilence;
        case POOL_DARE: count = sizeof(kDare)/sizeof(void*); return kDare;
        case POOL_BET_OPEN: count = sizeof(kBetOpen)/sizeof(void*); return kBetOpen;
        case POOL_BET_WIN: count = sizeof(kBetWin)/sizeof(void*); return kBetWin;
        case POOL_BET_LOSE: count = sizeof(kBetLose)/sizeof(void*); return kBetLose;
        case POOL_SUPERSTITION: count = sizeof(kSuperstition)/sizeof(void*); return kSuperstition;
        case POOL_NAMING: count = sizeof(kNaming)/sizeof(void*); return kNaming;
        case POOL_MOOD_BORED: count = sizeof(kMoodBored)/sizeof(void*); return kMoodBored;
        case POOL_MOOD_BLOODDRUNK: count = sizeof(kMoodBlooddrunk)/sizeof(void*); return kMoodBlooddrunk;
        case POOL_MOOD_HOMESICK: count = sizeof(kMoodHomesick)/sizeof(void*); return kMoodHomesick;
        case POOL_MOOD_COINHEAVY: count = sizeof(kMoodCoinheavy)/sizeof(void*); return kMoodCoinheavy;
        case POOL_MOOD_NIGHTWEARY: count = sizeof(kMoodNightweary)/sizeof(void*); return kMoodNightweary;
        case POOL_WILDCARD: count = sizeof(kWildcard)/sizeof(void*); return kWildcard;
        default: count = 0; return 0;
    }
}

// ------------------------------------------------------------- selection ----
// Per-(bot, audience, category) state: caller owns it (the game keeps a
// mutex-guarded map; the host test keeps arrays). Ring records recently
// used pool indices; novelty weighting starves them instead of banning.
struct BanterState
{
    uint32_t rng;
    uint16_t ring[kMaxRing];
    uint8_t ringLen, ringPos;
    uint64_t cooldownUntilMs, lastWildcardMs;
    uint32_t draws;
};

inline void InitBanterState(BanterState& s, uint32_t botGuid, uint32_t audienceKey)
{
    s.rng = botGuid * 0x9E3779B9u ^ audienceKey * 0x85EBCA6Bu ^ 0xA5A5A5A5u;
    if (!s.rng) s.rng = 0x1234567u;
    s.ringLen = s.ringPos = 0;
    s.cooldownUntilMs = s.lastWildcardMs = 0;
    s.draws = 0;
    SplitMix32(s.rng); SplitMix32(s.rng);
}

inline bool RingHas(BanterState const& s, uint16_t idx)
{
    for (uint8_t i = 0; i < s.ringLen; ++i)
        if (s.ring[i] == idx) return true;
    return false;
}

struct BanterResult
{
    char const* line;      // never null on success
    uint16_t index;        // index into the chosen pool
    bool suppressed;       // in cooldown: caller falls through (LLM path)
    bool wildcard;         // drawn from the wildcard bank
};

// The single selection entry point. Pure: time is passed in.
inline BanterResult SelectLine(BanterState& s, char const* const* pool, size_t poolSize,
                               char const* const* wildPool, size_t wildSize,
                               uint32_t nowMs, uint32_t cooldownMs)
{
    BanterResult r = { 0, 0, false, false };
    if (!pool || poolSize == 0) return r;
    if (nowMs && s.cooldownUntilMs && nowMs < s.cooldownUntilMs) { r.suppressed = true; return r; }

    // Wildcard roll: rare sanctioned chaos from the shared bank, on its own
    // longer cooldown. still ring-tracked so even chaos does not repeat.
    if (wildPool && wildSize &&
        (s.lastWildcardMs == 0 || nowMs - s.lastWildcardMs >= kWildcardCooldownMs) &&
        (SplitMix32(s.rng) % 1000000u) < kWildcardsPerMillion)
    {
        uint32_t pick = SplitMix32(s.rng) % wildSize;
        for (uint32_t attempt = 0; attempt < wildSize && RingHas(s, (uint16_t)(poolSize + pick)); ++attempt)
            pick = (pick + 1) % wildSize;
        s.lastWildcardMs = nowMs;
        r.line = wildPool[pick]; r.index = (uint16_t)pick; r.wildcard = true;
        s.ring[s.ringPos] = (uint16_t)(poolSize + pick); // offset tags wildcard ids
        s.ringPos = (uint8_t)((s.ringPos + 1) % kMaxRing);
        if (s.ringLen < kMaxRing) ++s.ringLen;
        // stamp only a REAL cooldown: a zero cooldownMs must not leave a
        // nonzero stamp, or a wrapped clock (nowMs < stamp, raw compare
        // above) suppresses every later draw until the clock climbs back
        s.cooldownUntilMs = (nowMs && cooldownMs) ? nowMs + cooldownMs : 0;
        ++s.draws;
        return r;
    }

    // Novelty-weighted draw: candidates = pool entries not in the ring.
    size_t fresh = 0;
    for (size_t i = 0; i < poolSize; ++i)
        if (!RingHas(s, (uint16_t)i)) ++fresh;
    if (fresh == 0)
    {
        // Ring covers the whole pool: drop the oldest half of the ring so
        // randomness resumes instead of degenerating into a fixed cycle.
        uint8_t const keep = s.ringLen / 2;
        uint16_t tmp[kMaxRing];
        for (uint8_t i = 0; i < keep; ++i)
        {
            uint8_t src = (uint8_t)((s.ringPos + kMaxRing - keep + i) % kMaxRing);
            tmp[i] = s.ring[src];
        }
        for (uint8_t i = 0; i < keep; ++i) s.ring[i] = tmp[i];
        s.ringLen = keep;
        s.ringPos = (uint8_t)(keep % kMaxRing);
        for (size_t i = 0; i < poolSize; ++i)
            if (!RingHas(s, (uint16_t)i)) ++fresh;
        if (fresh == 0) { s.ringLen = s.ringPos = 0; fresh = poolSize; }
    }
    uint32_t roll = SplitMix32(s.rng) % (uint32_t)fresh;
    size_t chosen = poolSize;
    for (size_t i = 0; i < poolSize; ++i)
    {
        if (RingHas(s, (uint16_t)i)) continue;
        if (roll == 0) { chosen = i; break; }
        --roll;
    }
    if (chosen >= poolSize) chosen = 0; // defensive: never OOB

    s.ring[s.ringPos] = (uint16_t)chosen;
    s.ringPos = (uint8_t)((s.ringPos + 1) % kMaxRing);
    if (s.ringLen < kMaxRing) ++s.ringLen;
    s.cooldownUntilMs = (nowMs && cooldownMs) ? nowMs + cooldownMs : 0;
    ++s.draws;
    r.line = pool[chosen];
    r.index = (uint16_t)chosen;
    return r;
}

// ------------------------------------------------------------- rendering ----
// {P} -> player name, {B} -> bot name. Bounded: never writes past cap;
// always null-terminated; an over-long render is truncated (the packet
// layer re-splits at 200 anyway).
inline void RenderLine(char const* tmpl, char const* player, char const* bot,
                       char* out, size_t cap)
{
    if (!out || cap == 0) return;
    out[0] = 0;
    if (!tmpl) return;
    size_t o = 0;
    for (char const* p = tmpl; *p && o + 1 < cap; ++p)
    {
        if (p[0] == '{' && (p[1] == 'P' || p[1] == 'B') && p[2] == '}')
        {
            char const* sub = (p[1] == 'P') ? (player ? player : "") : (bot ? bot : "");
            for (char const* q = sub; *q && o + 1 < cap; ++q) out[o++] = *q;
            p += 2;
        }
        else
            out[o++] = *p;
    }
    out[o] = 0;
}

// Verbal tic: applied to authored lines on a 1-in-3 seeded coin. Never on
// lines that already start with the tic, never twice in a row.
inline bool ShouldTic(uint32_t& rng, char const* line, int tic)
{
    if (tic == TIC_NONE) return false;
    char const* prefix = TicPrefix(tic);
    size_t n = std::strlen(prefix);
    if (std::strncmp(line, prefix, n) == 0) return false;
    return (SplitMix32(rng) % 3u) == 0;
}

} // namespace pocketllm

#endif
