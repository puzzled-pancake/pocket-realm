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
#include <deque>
#include <string>
#include <atomic>

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

// ------------------------------------------------------------ mood state ----
// Phase-3 moods: the five existing mood pools plus smitten / grudge /
// grief, as prompt seasoning (one line in the system prompt's instruction
// span) with pool-weight shifts on the ambient draw. Moods are
// GUID-stable per bot with slow rotation + event nudges; volatility
// (the llmRpVolatility dial, 0-100, 50 = default) scales how fast the
// weather changes. Pure and host-testable: the mood index derives from
// (botGuid, tickBucket, nudgeCounter) with no game state.
// (Placed after the pools enum: MoodPoolOf maps onto POOL_MOOD_*.)
enum { MOOD_BORED = 0, MOOD_BLOODDRUNK, MOOD_HOMESICK, MOOD_COINHEAVY,
       MOOD_NIGHTWEARY, MOOD_SMITTEN, MOOD_GRUDGE, MOOD_GRIEF, MOOD_COUNT };

// ---------------------------------------------------------------- pools ----
// {P} = player name, {B} = bot name; rendered by RenderLine at delivery.
enum PoolId {
    POOL_BUSY = 0, POOL_GREET_STRANGER, POOL_GREET_ACQUAINTANCE, POOL_GREET_ALLY,
    POOL_GREET_TRUSTED, POOL_KILL, POOL_LOOT_RARE, POOL_IDLE, POOL_PHILO,
    POOL_SILENCE, POOL_DARE, POOL_BET_OPEN, POOL_BET_WIN, POOL_BET_LOSE,
    POOL_SUPERSTITION, POOL_NAMING, POOL_MOOD_BORED, POOL_MOOD_BLOODDRUNK,
    POOL_MOOD_HOMESICK, POOL_MOOD_COINHEAVY, POOL_MOOD_NIGHTWEARY,
    // plan v5 H1/W1/W4/W8: the three aliased moods get their own pools (the
    // prompt said smitten while the fallback line sounded homesick), plus
    // the authored grudge act-refusal bank (W4) and the /notice nudge bank
    // (W8) - all 12/8-deep like the rest
    POOL_MOOD_SMITTEN, POOL_MOOD_GRUDGE, POOL_MOOD_GRIEF,
    POOL_GRUDGE_REFUSE,
    POOL_SCENE_NUDGE,
    POOL_WILDCARD,
    // plan RP E0: two 4x12 archetype banks, appended AFTER POOL_WILDCARD so
    // every pre-existing pool keeps its number. Street short-reactions are
    // the A6 fallback for a player's unaddressed /say landing near a street
    // bot (data now - the interim street behavior stays emote-only; A6
    // wires it). The security refusals voice the .whisper invite-family
    // gates (invite/leader/full-group denials - never the beg refusals,
    // which are the persona refuseLine cells' ground) and are drawn by
    // PlayerbotLlmPersona::SecurityRefusalLine (E3 wires the call site).
    // Pool() serves the flattened 48 for the content invariants; the game
    // draws 12-line per-archetype cells (StreetShortCell/SecurityRefuseCell).
    // Neither bank joins the seeded path: InitBanterState and the existing
    // pools are untouched, and their state keys live on the
    // guid<<24 | (pool+1) lane (the guid<<8 persona lane is full).
    POOL_STREET_SHORT, POOL_SECURITY_REFUSE,
    // plan RP E1: the level-up cheer pool (the authored leg of the
    // beat; appended so every pre-existing pool keeps its number)
    POOL_CHEER,
    POOL_COUNT
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
    "Thinking. The gears turn slow but they grind fine.",
    "A breath, {P}. Even the forge needs a moment to heat.",
    "Patience. I'm chewing on it and it tastes complicated.",
    "Almost got it. The thought's shy - cornered it twice already.",
    "Hold on. Good answer's coming. Bad ones are faster, I know.",
    "Thinking twice. It's a habit. Mostly it just takes twice as long.",
    "Wait. The good answer is hiding behind the quick one.",
    "Let me turn that over. Some things land differently upside down.",
    "One breath, {P}. Even owls blink before they decide.",
    "Hm. That has edges. Let me count them before I grab it.",
    "Don't rush me. Rushed answers are how tavern fights start.",
    "Still chewing. It's a stubborn one.",
    "Give it a moment. Wine needs air. So do thoughts.",
    "I'm weighing it. The scales are old and moody.",
    "Hold on - I almost had it and it wriggled free.",
    "A second. The words are in there. They're just not in order yet.",
    "Let me think. Shouting at my head never speeds it up.",
    "You'd want the true answer, not the fast one. They're rarely neighbours.",
    "Quiet, {P}. Something wise is forming. Or gas. One of the two.",
    "Half a moment. My thoughts dock slower than the ferry.",
    "I heard the question. I'm interviewing the answers now.",
    "Thinking. Loudly. You just can't hear it.",
    "A moment, {P}. Even bread needs time to rise.",
    "It's a fair question. It deserves better than my first impulse.",
    "Wait for it. The clever part arrives last, on foot.",
    "Careful. If I answer too fast, I'll believe it.",
    "Let the pot stir a while longer, {P}. Then we taste it.",
    "One moment. I'm arguing with myself and I'm losing.",
    "Give me a beat. My wits came home from the war slower than the rest of me.",
    "Hold. There's a right answer in here somewhere, hiding under a rude one.",
    "Ah. Now that's a question with roots. Let me dig.",
    "Not yet. The thought is still in its nightclothes.",
    "Patience, {P}. Haste is just regret wearing running boots.",
    "I'm thinking. You'll know because my face will disagree with something.",
    "Wait - I want to answer that honestly, and honesty takes longer to load.",
    "A breath. The clever answer rides the slow horse.",
    "Mm. Turn it over once more. There. Now I can answer.",
    "Don't fill the silence, {P}. That's where I keep my thinking.",
    "Still working. It's like herding cats in a fog.",
    "Hold a moment - I'm choosing between the honest answer and the kind one.",
    "That deserves a real answer. Real ones take a minute to saddle.",
    "Let me consult the little council in my head. They're arguing.",
    "One moment, {P}. I'm chasing the point - it keeps ducking.",
    "Thinking it through twice. Once for you, once for me.",
    "Wait. The first answer was mine. The second will be better.",
    "Give me a moment - wisdom's on its way, but it wouldn't pay for a horse.",
    "Hm. I want to say that carefully. Words are sharp in this town.",
    "Nearly there. The thought came in through the back door, dripping.",
    "A moment. Even the moon takes its time crossing the sky.",
    "Slow down, {P}. You'll have my answer before the ale goes flat.",
    "I'm still deciding whether to be clever or useful.",
    "Hold that. It's important enough to think standing up.",
    "Let me see. There's a right way to say that and I haven't found the door yet.",
    "The wheels are turning, {P}. Squeaky, but turning.",
    "One second. The obvious answer just walked past - waiting for the polite one.",
    "Give me a breath. My thoughts set sail slower than a fat merchant.",
    "Mm. That one goes deep. Let me find the bottom before I answer.",
    "Hold on. I'm between two answers and they don't like each other.",
    "Careful, {P}. This is my thinking face. Treasure it.",
    "Not ignoring you - assembling you. There's a difference.",
    "Patience. Good ideas are like truffles. Buried and unimpressed.",
    "Wait for the second thought. The first one only plays the fiddle.",
    "Let me roll that around. Sometimes the answer falls out the other side.",
    "A moment, {P}. The hamster's awake but the wheel is stiff.",
    "Hm. You've asked something that needs unlacing first.",
    "Give it a breath. Sharp answers cut the asker too.",
    "Still here, still thinking. The two are related.",
    "Hold. If I answer now, we'll both regret it by supper.",
    "That's a bigger question than it's dressed as, {P}. Let me unwrap it.",
    "One moment. I like to let answers settle, like good stew.",
    "Thinking, {P}. It's not my best skill, but it's load-bearing.",
    "Wait. I had it a second ago. It went behind the barrel.",
    "The answer's coming. It stopped to argue with a toll collector.",
    "Patience, {P}. Even the tide takes turns.",
    "Hm. Let me think about what you meant, not what you said.",
    "A moment. I want to be precise. Precision walks with a cane.",
    "Give me a minute - my thoughts are still at the market.",
    "Hold that thought and I'll pay it proper attention.",
    "Slow questions get slow answers, {P}. Yours was excellent.",
    "One breath. There's a fine line between wisdom and napping.",
    "Still chewing on it. It tastes like a mistake from years ago.",
    "Wait, wait. Almost. There - no. It slipped again.",
    "Let me think on that while pretending to study the horizon.",
    "Careful now. Good answers are heavy. Let me brace.",
    "Give it a moment, {P}. Even cheese needs to breathe.",
    "Hm. First thoughts are like first drafts - only good for starting fires.",
    "A second, {P}. My brain is mid-stride and hates being touched.",
    "Don't help me think. It only encourages the wrong half.",
    "Thinking. If you smell smoke, that's normal.",
    "Hold on. I'm translating my answer into words people survive.",
    "Nearly ready. The thought needed oiling.",
    "One moment. Great wisdom is just slow panic, well-dressed.",
    "Let the silence do some of the work, {P}. It's cheaper.",
    "That's worth a proper answer. The cheap ones wear out.",
    "Mm. Ask me again in a breath, but softer. It helps.",
    "Wait - the answer's here, it just doesn't know which door to knock on.",
    "I'm thinking of several answers, {P}. Eliminating the ones that start fights.",
    "Give me a moment. I want to say something I won't have to unsay.",
    "Hold. This deserves thought, not reflex. Reflex is for snakes.",
    "A breath, {P}. Even anvils cool eventually.",
    "Still turning it over. It has more sides than a die.",
    "Patience. The good answer is coming - it's old and it walks.",
    "One moment, {P}. I'm deciding how honest to be. It's a range.",
    "Hm. You know, some questions are just fences. Let me find the gate.",
    "Let me fetch the right words. The wrong ones are always closer.",
    "Give it a second. My mind's a cluttered attic and the lamp's dim.",
    "Wait for it. Genius is mostly showing up after the pause.",
    "Thinking, {P}. Please - no sudden movements.",
    "A moment. I like an answer that can stand up in wind.",
    "Still with you. The thought's circling the field before it lands.",
    "Hold that. My opinion is in the back room and won't come out for strangers.",
    "Nearly. It's like picking a lock with a wish.",
    "Give me a breath, {P}. Even the bishop pauses before the blessing.",
    "Mm. That needs a slow answer. Quick ones rattle.",
    "One moment - I'm asking my better judgement and it's hard of hearing.",
    "Wait. There's a wise way to say that and I'm still carving it.",
    "Let me sit with that a moment. It's pointy in places.",
    "Hm. Thinking. Old habit from before the war.",
    "Almost, {P}. The answer kept changing its mind and now I have to choose.",
    "Patience. Great walls are built one stone at a time, and so are thoughts.",
    "A second. The thought is nearly dressed.",
    "Hold on - I want the answer that survives till morning.",
    "Give me a moment. Reflection is free and I'm frugal.",
    "Still brewing, {P}. You'll smell it when it's ready.",
    "That's a thought with thorns, that one. Gloves on.",
    "Wait. I'm weighing whether to be wise or just right.",
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
    "Hail, traveler. The road behind you - was it quiet? It never stays quiet.",
    "Stranger. You're a long walk from anywhere that owes you a meal.",
    "I know every face on this road, and yours owes me an introduction.",
    "You walk like the road hasn't beaten you yet. Give it time.",
    "Well met. Keep your pack close and your opinions closer.",
    "New in these parts? Aye, it shows. That's not an insult. It's a warning.",
    "Hail. If you're lost, the town's that way. If you're not lost, I'm impressed and suspicious.",
    "Don't mind me. I greet everyone. The ones who wave back are usually worth knowing.",
    "You there. The mud on your boots says south. The look on you says trouble found you already.",
    "A stranger on the road is one of three things. I'm still deciding which you are.",
    "Hail. We've met exactly never, and this is how that gets fixed.",
    "I don't shake hands on a first meeting. Too many people count fingers afterwards.",
    "Fair day. The wolves agree - I can hear them planning something.",
    "{B} is my name. Keep it dry and it'll keep you honest.",
    "You've the look of someone the road is still making up its mind about.",
    "Stranger. Rest if you need it. My fire's small, but it's mine to share.",
    "Hail. We'll be friends by the second meeting or enemies by the third. The road works like that.",
    "I've seen your face somewhere. Or I've seen its trouble. One of the two.",
    "Slow down, traveler. Nothing behind you is worth the running.",
    "Well met, or near enough. Around here we take what we can get.",
    "A new face. The old ones wear out so quickly out here.",
    "Hail. If you're selling, I'm poor. If you're buying, I'm honest. Rare arrangement.",
    "You stand like a fighter, but you blink like a thinker. The road will cure one of those.",
    "Traveler. The bridge ahead holds. The bridge after that - hold your own breath.",
    "I ask nothing on a first meeting except this - are you the trouble, or just traveling with it?",
    "Hail. It's a hard country for strangers. Soft ones get sorted by the end of the week.",
    "Mind the ditch, stranger. It's deeper than my patience.",
    "Well met. You'll forgive the squint - the sun's low and trust is lower.",
    "I'm nobody you need to know yet. Ask again when we've shared a road.",
    "Hail, stranger. The inn's full of liars but the ale's honest.",
    "Your boots are newer than your luck. That's usually how it goes.",
    "A stranger with a straight back. This road bends everyone eventually.",
    "Hail. We can stand here politely or walk the same direction. Both work.",
    "Don't take the north path after dark. That's my greeting and my whole advice.",
    "You there - the road east is closed. Not by law. By something with teeth.",
    "Well met, stranger. I'd offer bread, but I'm between bakers and between blessings.",
    "I watch the road for a living, which is to say the road watches back. Hail.",
    "A new shadow on the road. Walk near the fire - shadows behave better in company.",
    "Hail. You're either brave or lost to be walking that quietly.",
    "Stranger, the river's high. If you mean to cross, mean it early.",
    "I forget faces I don't trust. Yours I'll keep for now.",
    "Hail. The town's a day's walk. The stories are free. The silence costs more.",
    "You carry your troubles well. Most people drag them.",
    "Well met. I'd say pick a side of the fire, but you look like you carry warmth already.",
    "I don't know your name and already I owe you - you warned me about nothing and that's rare.",
    "Traveler. If you hear whistling in the trees, it isn't birds.",
    "Hail, stranger. I've a good eye for people and no talent for either hope or fear.",
    "The last stranger I met stole my knife. You have the courtesy to greet first. Improvement.",
    "You there. Weather's turning. Shared walls keep better tempers than shared roads.",
    "{B}. That's all you get for now. Names are for the second meeting.",
    "Stranger, your horse - it isn't yours, is it? No. Didn't think so. Hail anyway.",
    "Hail. This road has three rules. You'll learn them by the third night.",
    "I've greeted a hundred strangers this season. You're the first to slow down.",
    "Well met. The chapel's fallen, the mill's for sale, and the ale's warm. Welcome to everything.",
    "You've dust on you from the coast. That's a long way to still be polite.",
    "Hail. I mean you no harm, which around here counts as hospitality.",
    "A stranger who nods first. The road hasn't ruined you yet.",
    "Traveler. Walk loud near the crossing. The quiet ones get followed.",
    "Well met, stranger. Whatever you're headed toward, may it be worth the boots.",
    "I don't greet often. Consider yourself singled out.",
    "You there. Nothing personal, but I've counted your weapons twice now. Habit.",
    "Hail. The road takes a toll. Yours looks mostly paid.",
    "New face, old boots. That's either a story or a lie. Both keep a campfire warm.",
    "Stranger. Rest your feet. The worrying can wait until morning, when it's fresher.",
    "Fair travels. Mind that the town dogs remember faces better than the guards do.",
    "Hail. If we meet again, drinks are on the one of us still standing.",
    "You walk like the war's still behind you. It is. That's the trouble with wars.",
    "Well met. I've a bad memory for names and a good one for debts. Yours is currently blank.",
    "I'm nobody's guide, but the signposts are honest and the innkeeper isn't. Plan around both.",
    "Hail, stranger. The stars are out and the wolves are quiet. Both are rare enough to mention.",
    "You there - a word. The road's fine. The people on it aren't. That's the whole of it.",
    "A stranger at dusk. Either you're confident or you're out of options. Sit either way.",
    "Hail. I judge by pace, and yours says you've somewhere to be that matters.",
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
    "{P}, back again. The road keeps folding you back this way, and I keep noticing.",
    "Hail. I've learned your stride by now. It arrives before you do.",
    "There's the face I half-know. Sit - the half I know seems decent.",
    "{P}. We're past the stage where I wonder if you're trouble. Mostly past it.",
    "Hail again. You're becoming a habit. I collect worse ones.",
    "Back on the road, {P}? The innkeeper asked after you. That's either favor or debt.",
    "Well met. I know your name now without pausing to fetch it. That took a season.",
    "{P}, good timing. The fire's tall and the stories are short.",
    "You again. My day improves by a small, measurable amount.",
    "Hail, {P}. I've started remembering which ale you favor. Worrying, that.",
    "The road's long, but it keeps spitting us at each other. I've stopped minding.",
    "{P}. Your face got sunburned on the left. Eastbound, then. Good.",
    "Back already? The camp grew quieter and duller in your absence. Fix the second part.",
    "Hail. We're acquainted enough now that I'll share the bench, not just the fire.",
    "{P}, well met. I'd offer news, but you probably brought better.",
    "There you are. I told the mule you'd come back. The mule owes me a carrot now.",
    "Hail, {P}. Still upright, still moving. Both count.",
    "You know, I almost greeted a stranger wrong yesterday because they walked like you. That's your fault.",
    "{P}. The soup's thin but the company's tolerable. Sit.",
    "Back on this stretch again? Between us, this stretch doesn't deserve the traffic.",
    "Hail. I've moved from forgetting your name to forgetting my own instead. Fair trade.",
    "{P}, you look like the road took its tax. Sit down. Pay nothing.",
    "Good to see the boots still holding. I notice boots now. That's what acquaintances do.",
    "Hail, {P}. The wolf whistles stopped an hour before you arrived. Make of that what you will.",
    "Twice in a season. People will say we're friends. Let them - it keeps them from saying worse.",
    "{P}, well met. I've stopped keeping count of who owes the drinks. The ledger drowned.",
    "There you are. I saved you the last flat stone by the fire. That's acquaintance currency.",
    "Hail. I heard your name in town - said well, which surprised everyone, mostly me.",
    "{P}. Sit. The fire and I divided your share of the heat evenly.",
    "Back again, {P}. The road must be short or the world must be. I've decided it's the world.",
    "Hail, {P}. Your timing's improving. Last time you missed the stew entirely.",
    "Well met. I can now predict which direction you'll slump when you sit. Acquaintance science.",
    "{P}. The boots are wearing even. You've stopped favoring the old wound. Good.",
    "You again. I'd gotten used to the quiet, which is exactly why I'm glad.",
    "Hail. The merchant asked if you were trustworthy. I said probably. It's the best I had.",
    "{P}, you've the look of someone carrying news. Set it down gently either way.",
    "There's the one who walks like the road owes them an apology. Hail, {P}.",
    "Hail, {P}. The inn's standing, the well's wet, and you're here. Small mercies stack.",
    "I remembered your face before your name this time. Progress runs backward with me.",
    "{P}. Rest your feet. I'll ask my questions in the morning, when you can lie better.",
    "Back so soon? Careful - I nearly set a place for you. Nearly.",
    "Hail, {P}. The road's been dull and you're the first interesting thing all week.",
    "Well met. I know three things about you now. Two of them are flattering. Sit and earn the third.",
    "{P}, the fire remembered you. That's more than the fire does for most.",
    "You know my mule now, and my mule knows you. That's binding in some counties.",
    "Hail again. I've started telling people we know each other. They believe me. Strange.",
    "{P}. You arrive with weather. Every time. I've begun planning around it.",
    "There you are. I keep half a thought spare for when you turn up. It's been useful.",
    "Hail, {P}. The stew's new, the bread's old, and the company's steady. Choose two.",
    "{P}, well met. I'd say sit anywhere, but the anywhere by me has the best view of the road.",
    "Back on the trail. Between us, the trail talks about you when you're gone. Kindly, mostly.",
    "Hail. You've crossed from stranger to acquaintance, which means I'll lend you things now. Fear that.",
    "{P}. My guard drops a notch when you're in camp. Notch management is my whole craft.",
    "You again! The dice missed you. So did the arguing. Mostly the dice.",
    "Hail, {P}. I've learned your silences mean thinking, not offense. That took a while.",
    "Well met, {P}. The season turns and you still turn up. There's a word for that.",
    "{P}, you look half-fed and fully stubborn. The usual, then.",
    "There's a face I've decided to trust as far as the well and back. Hail.",
    "Hail, {P}. I've gotten better at guessing your mood by your hat. Today's hopeful.",
    "{P}. The road taught me one rule about you - save a share. I saved a share.",
    "Back. Good. The campfire arguments keep score, and you owe the winning side a point.",
    "Hail. We're not friends yet. But I've stopped checking my purse when you stand behind me.",
    "{P}, well met. News travels and none of it was about you this fortnight. Keep it up.",
    "You walk in like you've been here before, because you have. That's the whole difference.",
    "Hail, {P}. The bench creaks the same for you as for anyone. I find that comforting.",
    "{P}. I was just about to do nothing in particular. You've improved the plan.",
    "There you are. I know your pace, your noise, and your appetite. Acquaintance is a ledger.",
    "Hail. Sit - the fire's at that honest hour where it stops flinching at the wind.",
    "{P}, back again. The well water tasted of iron this morning. You brought the usual weather with you.",
    "Good to see you, and I say that with the usual half-stranger reservations. They're shrinking.",
    "Hail, {P}. Between us - the road's been strange this month. Strange likes company. Hence you.",
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
    "{P}! The campfire was just complaining about the quiet. You fixed it by arriving.",
    "Hail, comrade. The road's lighter with your weight on the other end of it.",
    "There's the blade I'd pick first. Hail, {P}.",
    "{P}, good - you're upright. The day can start misbehaving now.",
    "Hail! I saved you the good seat, which is to say the dry one.",
    "{P}. Whatever you're carrying, set half of it down. That's what the rest of us are for.",
    "You show up and suddenly there's a plan. You're a plan in boots, {P}.",
    "Hail, old comrade. The road remembered you - it left your ruts and everything.",
    "{P}! We were just measuring who'd miss you most. I won. Modestly.",
    "There you are. The fire burns straighter when you're in camp. Don't ask me how.",
    "Hail, {P}. The last stretch of road was dull as dishwater. You're the wine.",
    "Good - {P}'s here. Now the arguments will at least be worth having.",
    "{P}, hail. I've gotten lazy about danger. Cure me of it.",
    "You again, and the timing's yours as always. We were one bad idea short of leaving without you.",
    "Hail, comrade. The watch split itself - I took the cold half out of habit.",
    "{P}! The stew actually worked this time. That's the kind of omen your arrival makes.",
    "There's my sharpest argument for staying alive. Hail, {P}.",
    "{P}, you make the miles shorter and the stories taller. Hail.",
    "Hail! The camp counted itself incomplete an hour ago. Stand still and be counted.",
    "{P}. Sit - the fire's generous and so am I, briefly, on days you arrive.",
    "Old comrade! The scars healed crooked on both of us. Evenly matched, then.",
    "{P}, hail. I've had your back so long it faces the same direction mine does.",
    "You're back. Good. The road was making noises it shouldn't when you weren't around.",
    "Hail, {P}. Between us, the plan improved the moment you walked in. It was a low bar.",
    "{P}! Whatever's in the wind, we'll weather it the usual way - loudly and barely.",
    "There's the one who shares the watch without being asked twice. Hail.",
    "{P}, well met again. My knife stayed sharp waiting for you. Sentimental of it.",
    "Hail, comrade. The road east has teeth - you'll want company with a reach like yours.",
    "{P}. The old rhythm - you walk point, I grumble. Positions assumed.",
    "You arrived. The bets on your arrival are settled. I bet on sober, I lost.",
    "Hail, {P}! The ale's poor, the bread's creative, and the company's the best in two counties.",
    "{P}, there you are. The camp kept your drafty corner warm. It was a team effort.",
    "Old friend of the road - the boots are holding, the luck is learning. Hail.",
    "{P}! Sit down before the fire takes it personally.",
    "Hail, comrade. I'd march into a marsh for you, and one day I probably will.",
    "There's the face that makes the sentries relax. Hail, {P}. Deceptive, that.",
    "{P}, you're back before the rumors of you faded. Good - saves the retelling.",
    "Hail! The road's long memory kept your crossing stones dry. It favors you. So do I.",
    "{P}. The camp split the good blanket in two. Yours is the half without the holes. Mostly.",
    "Comrade. The trail's been humming your name for a day. It does that. Rarely lies.",
    "Hail, {P}. If you're here, the work's about to get either easier or famous. I'll take either.",
    "{P}! My mood just improved, which the whole camp will regret by nightfall.",
    "There you are. The dice went cold without you. They only cheat for an audience.",
    "Hail, old comrade. The years have been fair to you - fair being generous, you being you.",
    "{P}, hail. I stopped counting the miles we've shared. The number got emotional.",
    "You walk in and the wolves stop singing. Coincidence twice is habit, comrade.",
    "Hail, {P}. Sit - your share of the fire's been burning a hole in the night all evening.",
    "{P}. We've hauled each other out of enough holes to skip the pleasantries. But hail anyway.",
    "There's my favorite reason the road's worth walking. Hail, {P}.",
    "{P}, good. I had a thought worth sharing. You're the only one who'd survive hearing it.",
    "Hail, comrade! The stew's yours, the watch is mine, and the argument can be both.",
    "{P}! You're a sight like sunrise - predictable, welcome, and it turns out I'd miss you otherwise.",
    "The old alliance - fire, road, and the two of us arguing at it. Hail.",
    "{P}, hail. The river ran shallow all week, then you arrive and it fills its boots. Even nature plays favorites.",
    "There he is - the other half of every good decision I've made out here. Hail, {P}.",
    "{P}. I ration my welcomes. Yours come from the full jar.",
    "Hail, comrade. We'll get into something. It's tradition now - the getting, not the trouble.",
    "{P}, you're back! The map missed your thumbprints. So did the arguing.",
    "Old road-friend. The boots, the fire, the debt of drinks - all accounted. Hail.",
    "Hail, {P}. The plan was missing a reckless streak. You're wearing it.",
    "There you are, {P}. The camp drafted a welcome speech. It was too long. The short version - good.",
    "{P}! Between you and the fire, I can't tell which keeps me warmer, and I won't insult either.",
    "Comrade. Sit, eat, grumble - the trinity of the trail, in order of importance.",
    "Hail, {P}. You've the timing of a hawk and the appetite of a siege.",
    "{P}, well met. The road east got honest, the road west got strange. We'll take the strange, as usual.",
    "You're here. The camp settles like a good stew - everything stops rattling. Hail.",
    "Hail, {P}. My knife counts your footsteps. It said you'd be early. The knife's never wrong.",
    "{P}! The night just got shorter and the stories longer. That's your doing, always.",
    "Old comrade - the road bends, the fire gutters, the ale sours, and you're still on time. Hail.",
    "{P}, hail. Whatever the road's planning, it plans quieter when you're near.",
    "There's the shoulder the road's weight shifts onto. Welcome back, {P}.",
    "{P}. The stars wheel and you walk under the same ones as me. The arithmetic of old friends.",
    "Hail, comrade. The watch is warm, the bread is honest, and you're neither. Perfect.",
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
    "{P}. The fire knows your weight on the log by now. It shifts the flames to greet you.",
    "There you are. I've stopped counting the days between visits. The counting was just missing with a ledger.",
    "Hail, old disaster. The years keep losing to you. I've bet on you every time.",
    "{P}, you're back. The camp breathed in when you cleared the trees. I heard it.",
    "My oldest argument for staying soft in a hard country. Hail, {P}.",
    "{P}. I kept your cup dry through the wet season. Kept it out of the rain, kept it waiting. Both.",
    "There's the one the road couldn't keep. Hail, {P} - the road and I have words later.",
    "{P}! You're late by my count and early by the trail's. I trust the trail. It knows what you're worth.",
    "Hail. The seat's yours - the real one, the warm one, the one I defend from strangers and weather alike.",
    "{P}, back again. I've stopped pretending the camp doesn't tilt toward you when you arrive.",
    "You know what's rare, {P}? A name I say without testing it first. Yours. That's years, that is.",
    "There you are. The mule stood up. The mule remembers you better than most people remember anything.",
    "{P}. Whatever you're carrying - I know the weight by your shoulders now. Set it down. That's what this fire is for.",
    "Hail, old friend. The story of us gets longer every season, and I've stopped editing it.",
    "{P}, you're home. Well - camp. It's the word we use when you're in it.",
    "Back before the fire died down. You always did time it to the coals, {P}.",
    "{P}! I told the stars about you again. They're bored of it. I'm not.",
    "There's my witness. Half my life needs you alive to vouch for it. Hail, {P}.",
    "Hail, {P}. The ring you left by the fire - I kept it polished. Sentiment's cheaper than a smith.",
    "{P}. You walk in and thirty winters walk in with you. Sit. We'll let them thaw.",
    "Old friend. The road's been asking after you in its way - washed out where you used to steady it.",
    "{P}, there you are. The camp kept count of your footsteps till they faded east. It's been listening for them since.",
    "Hail. I've a whole speech saved up. It boils down to - the fire's warm and so is the welcome. The rest is decoration.",
    "{P}. The years between us don't count themselves anymore. They just stand in a pile and call it trust.",
    "You're back. The kettle's on, the knife's sharp, and the mule pretends not to care. Two out of three, {P}.",
    "{P}, hail. I dreamt the road went quiet. Woke up and knew it was just you, far away and loud somewhere else.",
    "There's the hand I'd grip crossing a river in flood. Hail, {P} - and I'd know the grip blind.",
    "{P}. The bench has your shape worn in it. I defend that shape from all comers. It's my only territory.",
    "Hail, old friend. The seasons keep their accounts - and you're the one debt I've never minded owing.",
    "{P}, you're back. The camp straightens when you're here. Even the smoke stands up straighter.",
    "You again. Good. I'd grown tired of being the only one who remembers the words to the old songs.",
    "{P}! Sit by me. The other side of the fire has better light and worse company. Choose accordingly. Choose me.",
    "Hail, {P}. The years gave you lines and me gray. Between us we make one honest map.",
    "{P}, there you are. I rationed my worrying and still ran out before you arrived.",
    "Old friend. The kettle, the blanket, the good knife - all yours. You know the inventory better than I do.",
    "{P}. The road and I have an arrangement - it sends you back, I don't curse it for a month. It always collects.",
    "Hail. You're the reason half my stories start with luckily. Luck's just you, arriving.",
    "{P}, back again. The fire's burned through three logs and one patience waiting. Logs are cheaper.",
    "There's my oldest surviving mistake. Made properly, though - I made you a friend on purpose. Hail.",
    "{P}. The winter you didn't come, I burned good oak and bad judgment. Both kept the cold off. Only one warmed me.",
    "Hail, {P}. The boots by the fire are yours, the cup by the boots is yours, the place by both is yours.",
    "{P}, you're here. The whole camp pretends not to watch you arrive. We all fail. Every time.",
    "Old comrade - the deep kind, the kind that survives distance and silence. Hail, and mean it.",
    "{P}! I've gotten old enough to say it - you're the best thing this road ever did. Don't repeat it. Repeat it.",
    "There you are. I know your cough, your sigh, your camp-walk. Home is a set of sounds, {P}.",
    "Hail, {P}. The quarrels we've buried would fertilize a field. The friendship's the harvest.",
    "{P}. The stars wheeled, the river turned, the map yellowed - and you still walk in on time. Hail.",
    "Back. The word's too small for it, {P}, but it's the one we've got. Back.",
    "{P}, hail. The last time you left, the fire spat sparks for a week. It holds grudges. So does the mule.",
    "There's the face I've argued with, marched with, and starved beside - and chosen beside, every time. Hail.",
    "{P}. Sit down. Let the years sit down too. They've walked as far as we have.",
    "Hail, old friend. The fire's at its best hour - honest light for an honest face.",
    "{P}, you're back. I practiced being casual about it. As you can hear - failed.",
    "The camp's compass. That's you, {P}. Every road out of here points back toward wherever you are.",
    "{P}! The pot's been on a slow boil since dawn. The dawn knew. Dawns usually do, about you.",
    "Hail. There are maybe three people whose footsteps I know through a boot sole. You're the first of them.",
    "{P}, there you are. The worrying shelf is empty. Restock me next time with a longer warning.",
    "Old friend - the kind of old that isn't years, it's miles. Hail, {P}.",
    "{P}. The blanket's yours, the shoulder's yours, the last of the wine is yours. The stories we'll split.",
    "Hail, {P}. Between your last visit and this one, nothing happened worth the telling. You're the telling.",
    "{P}, back again. The road's a fool - it keeps lending you out and taking you back. I'd keep you.",
    "There you are. The campfire's been rehearsing. It does a poor impression of your laugh. We all try.",
    "{P}! Some people arrive. You return. There's a difference, and the fire knows it.",
    "Hail, old friend. The debt of years sits easy between us - neither counts, both remember.",
    "{P}. You cross the trees and the birds change their song. Even the woods have your name in their mouths.",
    "The good hour of the fire, the good side of the road, the good end of the story - all yours, {P}. Hail.",
    "{P}, you're back. I kept everything warm - the stones, the stew, the welcome. The welcome was hardest.",
    "There's the one I'd cross a war for and have. Hail, {P}. The war lost.",
    "Hail, {P}. The mule brayed. The kettle sang. The old signs. You're home, then, for whatever home means out here.",
    "{P}. Sit where the light lands fair. I've spent years learning where that is. For you.",
    "Old friend. The fire remembers you the way I do - warm, steady, and glad you came back. Hail.",
    "{P}, hail. The years shortened the road between us. The road never stood a chance.",
    "You're here. Every grudge in camp took the night off. That's your doing, {P}. It's most nights.",
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
    "Down it goes, like all the rest.",
    "Somebody dig a hole. I've done my part.",
    "That's the last noise it makes on my watch.",
    "It chose the wrong road. Roads have consequences.",
    "Sleep well, ugly.",
    "The birds will eat well tonight.",
    "One swing less than I'd budgeted. {P}, you're learning.",
    "Flat and finished.",
    "It had a face only a mother could mourn. Nobody will.",
    "That's for the boot leather, friend.",
    "Still. Finally.",
    "Wasn't pretty. Wasn't meant to be.",
    "Count it and move on.",
    "The flies can take it from here.",
    "It stopped arguing. They always lose that argument.",
    "Bones for the hill, meat for the birds, trouble for nobody.",
    "Next!",
    "Done. Someone check my sword for nicks.",
    "The ground takes another one.",
    "Bled quiet. The quiet ones worry me more.",
    "That one nearly earned respect. Nearly.",
    "You swung first, friend. You swung last, too.",
    "Eyes closed. Feet up. Permanent.",
    "I'd say a prayer, but it wouldn't hear it.",
    "The list gets shorter. My blade gets lighter.",
    "There. The world's marginally improved.",
    "It fought like it owed money. Now it owes nothing.",
    "No more biting from that one.",
    "Keep the teeth. Trade them in town.",
    "That's how it's done. Quietly, anyway.",
    "It ran well. Died better.",
    "Wasn't the plan to kill it first. Plans are gossip.",
    "The buzzards circle lower. Good sign for us.",
    "One less snarl in the brush.",
    "Its mother would weep. Its dinner would cheer.",
    "Done and done.",
    "Buried or burned, I care not. It's done moving.",
    "Rest now, monster. You've earned it the hard way.",
    "The crows are already arguing over it.",
    "Score one for the ones with boots.",
    "The last thing it saw was us. Poor taste in scenery.",
    "That's a weight off the road.",
    "Flat as the argument that started this.",
    "The pack eats tonight, {B} says.",
    "Snapped like a dry twig. Sounds about right.",
    "It fell the way it lived. Badly.",
    "Another notch, another day.",
    "The scavengers can fight over the scraps.",
    "It hissed, it bit, it lost.",
    "The soil drinks deep tonight.",
    "There's a quiet now. I like that quiet.",
    "The fur will make a fine rug.",
    "Some things only understand a blade. This understood too late.",
    "It died doing what it loved. Attacking us.",
    "The night's one shade lighter.",
    "No more howling from that one.",
    "That's for the sheep. All of them.",
    "One less reason to walk armed. Just the one, though.",
    "The worms have their invitations.",
    "It dropped like a bad habit.",
    "Down. And there it stays.",
    "The blade sings, the monster hums. Done.",
    "It never stood a chance and never knew it.",
    "One clean strike. That's the whole sermon.",
    "The flies can start their work.",
    "Shiny scales, dull brain. It's over.",
    "The forest is one terror lighter.",
    "It kicked twice. Kicking's not living.",
    "Done, {P}. Your blade or mine, who keeps count.",
    "That's the biggest thing I've dropped all month.",
    "Dust to dust, fang to dirt.",
    "It won't be stealing from carts again.",
    "Dinner walks to the fire. It doesn't know it's walking on us.",
    "The stars saw that one. Even they're impressed. Probably.",
    "And the road is ours again.",
    "It gnashed. We won. Simple sums.",
    "Another for the tally, {P}.",
    "The birds went quiet, then the beast did.",
    "That one fought dirty and died clean. Irony.",
    "Stillness suits it better.",
    "Down like a hammer, done like a deal.",
    "The wolf that chases two rabbits eats none. It chased us.",
    "The pelt's ruined, the fight's won.",
    "It broke first. Everything breaks.",
    "We fed the ground today.",
    "The crows caw the news already.",
    "It'll trouble no more caravans.",
    "There's the sound of nobody dying. Ours.",
    "The last of its kind to try that trick.",
    "A good strike. I'll allow myself a nod.",
    "The vultures circle their gratitude.",
    "Hush now, beast. Permanent hush.",
    "It's done, {P}. Wipe your blade.",
    "Even the flies pause for that one.",
    "Short fight, long nap.",
    "The ground's a little crowded now.",
    "Done. My arms will complain tomorrow.",
    "One less shadow in the brush.",
    "The trick to killing is letting the other one die first.",
    "It's over, ugly. Sleep.",
    "The deed is done and the day rolls on.",
    "That's a tale for the tavern. Short tale.",
    "It went down arguing with the sky.",
    "The pack's fed, the road's clear, the blade's warm.",
    "Nobody's mourning but the flies.",
    "Down. We keep walking.",
    "That's the end of that song.",
    "The worms get a warrior's supper.",
    "It fought like ten and died like one.",
    "Another shadow off the trail.",
    "The birds already sing over it.",
    "No ceremony. It wouldn't appreciate one anyway.",
    "Done. Breathe. Next hill.",
    "It stopped. Everything about it stopped.",
    "That's one debt the road collected early.",
    "It lept, it snapped, it settled. Permanently settled.",
    "The earth takes what the blade gives.",
    "And the night goes quiet again, {P}.",
    "That's what comes of baring teeth at us.",
    "Finished. My blade's done its arguing.",
    "The last of the snarling, for tonight at least.",
    "It ran at us with everything. Everything wasn't enough.",
    "One less grave to dig later, one more to dig now.",
    "There. Stillness at last.",
    "The beast is down and the boots are up.",
    "Clean work, all of you.",
    "The scavengers can take their seats.",
    "It died as it lived. Loudly, then not at all.",
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
    "The fire and I have an understanding. It burns, I stare. Neither of us asks why.",
    "I've named every rock in this camp. The big one is Gerald. Gerald has seen things.",
    "If I stare at the map long enough, maybe the map will admit it's wrong.",
    "Somewhere out there, someone is eating a pie. I choose to believe this.",
    "I've been practicing my scowl. The mule remains unimpressed. The mule has high standards.",
    "You know what this camp needs? A rooster. Something to blame the mornings on.",
    "The clouds are doing that thing again. Nothing. The clouds are doing nothing again.",
    "I inventoried my worries. There were nine. One was a moth. It's down to eight.",
    "A good blade deserves a good whetstone. A great blade deserves a better one. Mine deserves patience.",
    "That tree hasn't moved in hours. I'm keeping an eye on it. It knows what it did.",
    "I've composed a ballad about this camp. It has one note. The note is boredom.",
    "We should get a dog. Or a duck. Something that approves of us.",
    "Counted my arrows again. Still the same number. I don't trust it.",
    "If you listen closely, the river is gossiping about us. It's not flattering.",
    "I've decided the sunset is late. Someone should say something to somebody.",
    "My boots have opinions about tomorrow. They're split. The left one's an optimist.",
    "I found a smooth stone. It's mine now. These things matter when nothing else does.",
    "The stars are out in force tonight. Somebody's showing off up there.",
    "I've been thinking about growing a beard. Then I remembered my face's position on beards.",
    "This is the part of the journey we'll lie about later. The quiet part. We'll add bandits.",
    "A crow flew over with my exact expression. I want it back.",
    "The kettle and I have reached an agreement. It whistles, I pretend that's news.",
    "I've started rating the clouds. That one's a four. Generous.",
    "I know three ways to fold a blanket and four ways to resent each of them.",
    "Every camp has a smell. This one's is 'regret and beans'. We work with what we have.",
    "The wind just changed direction out of, I can only assume, personal spite.",
    "If I had a copper for every quiet moment out here, I'd have exactly the same amount of quiet moments and some copper.",
    "I've been holding a staring contest with the horizon. The horizon blinked. It's official.",
    "Somebody's sock is drying by the fire. Not mine. I want that on the record.",
    "I dreamed the road moved and we stayed still. Woke up. We stayed still.",
    "The ant that lives in my boot leather has a name now. We're negotiating territory.",
    "This is a fine hour for doing nothing, and by fine I mean I've run out of ways to describe it.",
    "I've drawn a map of the camp. It has one feature. It's labeled 'us'.",
    "The moon is doing half its job tonight. Half credit, moon.",
    "I've thought about it and I'm officially in favor of supper.",
    "If boredom were a beast, it would be grazing on us right now. Gently. Politely.",
    "I've begun narrating the fire's adventures. The fire just cracked a log. Tension is rising.",
    "This stretch of road is so quiet I can hear my own opinions forming. They're not better for the silence.",
    "The mule stood up, looked around, and lay back down. That's the whole news from the mule beat.",
    "I've counted the stars in one patch of sky twice. Different numbers. One of the counts is lying.",
    "A watched pot never boils. A watched camp never anything. It just sits there. Like us.",
    "I've invented a game. The rules are a secret. The score is zero. I'm winning.",
    "The trees are conspiring to look exactly the same. It's working. I can't tell them apart anymore.",
    "If I squint, that hill looks like my uncle. My uncle was a difficult hill of a man.",
    "I've been letting my thoughts wander. They wandered off. If you see them, tell them dinner's at dark.",
    "The fire popped in a rhythm. I'm choosing to interpret it as applause.",
    "Somewhere a bell is ringing. Not for us. Nothing's ever for us. That's the road's whole joke.",
    "I've tried being still and thinking of nothing. Turns out nothing looks suspiciously like a boar.",
    "The grass here leans east. Either the wind has habits or the grass has plans.",
    "I've resolved to stop counting the quiet hours. This is hour I've-stopped-counting.",
    "A bird just judged me. I accept its verdict. It flew off, which means I passed. Or failed quickly.",
    "The shadows are getting long. Soon they'll be someone else's problem. That's how dark works, I'm told.",
    "I've categorized the camp's silences. This one's the 'before-something' kind. My favorite and least favorite.",
    "This rock I'm sitting on has been here longer than every kingdom I can name. It's not impressed by either of us.",
    "I've mentally traded the mule for a boat. The boat is winning. The mule can't swim anyway.",
    "The evening smells like woodsmoke and ambition. The woodsmoke is carrying the pair of us.",
    "I've composed my third ballad. It's about the mule. The mule remains unmoved. Critics.",
    "If the road had a face, this would be the part where it yawns at us.",
    "I've taken up cloud law. That one trespassed. Case dismissed. It rained.",
    "The crickets have opinions about our camp. They sing them every night, at length, at volume.",
    "I've decided tomorrow we walk somewhere. Anywhere. Specifically, away.",
    "The pot is clean, the blade is sharp, the boots are dry. The gods of small mercies are generous today.",
    "I've been talking to the fire. The fire listens. That's why we get along.",
    "This is the hour when the camp invents sounds so we have something to do.",
    "I've thought hard about it, and the stew was better yesterday. Yesterday's stew is a golden age now.",
    "The last leaf on that tree is holding on out of pure stubbornness. I respect it. I relate to it.",
    "I've made a study of the local mosquitoes. Conclusion - they're professionals and we're amateurs.",
    "There's a smell of rain coming. Either that or the river's finally making its move.",
    "I've flipped a coin thrice for watch order. It landed on edge once. I'm taking that as an omen of nothing.",
    "The ground here is exactly ground-shaped. I've come to expect that from ground, and it never disappoints.",
    "I've worn a path pacing. If we stay another day, it becomes a road. If we stay a week, a trade route.",
    "The fire's down to the honest coals. The part of the fire that tells the truth.",
    "I've considered taking up fishing. Then I remembered my patience is a rumor told by other, calmer men.",
    "Somewhere a wolf is practicing. We're the recital. It knows. We know. We're all professionals here.",
    "I've sorted the firewood by mood. This pile is 'brooding'. It's the biggest pile.",
    "The dusk is doing that slow trick of its. I've seen it a hundred times. Still falls for it.",
    "I've named the constellations wrong on purpose. It's the only rebellion I can afford.",
    "The last time I was this still, I was hiding from something. Old habits. The nothing I'm hiding from now is the quiet.",
    "I've bet myself a copper I could stay quiet an hour. I lost in the first minute. To myself. It's a rigged game.",
    "The kettle's warm, the night's cool, the road's asleep. One of us should be. It won't be me.",
    "I've drawn a line in the dirt. It's a boundary. Everything north of it is my opinion.",
    "That star has moved a whole hand-width. It's been at this all night. No breaks. Union rules up there, I suppose.",
    "I've recounted the supplies. The beans are winning. The beans are always winning. The beans have a strategy.",
    "The wind smells like snow, or distance, or both. Distance is just snow that hasn't happened yet.",
    "I've taught myself to whistle through a blade of grass. The grass blade gave its life for my art.",
    "The night's so clear I can see next week. Next week looks a lot like tonight, but with more walking.",
    "I've finished the small tasks. Now there's just the large one - being patient, which I've left to the professionals. The trees.",
    "I've stared at the embers so long they've arranged themselves into a map. It leads nowhere. Fitting.",
    "The mule dreams. You can tell - the ears flick. Probably dreaming of a world with no packs. Same dream.",
    "I've decided the crow that watches us each dusk is an omen. Of what, I don't know. It refuses to elaborate.",
    "This log I sit on was a tree once. Now it's furniture. There's a lesson in that. I refuse to learn it.",
    "The moon rose without asking anyone. That's the confidence I aspire to.",
    "I've arranged my socks by dampness. It's a gradient. It tells a story. The story is 'walk less'.",
    "Somewhere a tavern has a fire, a fiddle, and someone else's stories. I've chosen not to think about it. I'm thinking about it.",
    "I've counted the sparks from the fire. Lost count. The fire wins again. It's always been the better mathematician.",
    "The night birds have a song with one verse. They like the verse. I've made peace with the verse.",
    "I've rehearsed tomorrow's marching complaint. It's strong work. Opening with blisters, closing with destiny.",
    "There's a moth going for the flame the polite way. Slowly. Tastefully. Even the bugs here have manners.",
    "I've weighed my pack and my patience. The pack is lighter. The patience should eat more.",
    "The river's been going past all night. Never stops to talk. That's why the river gets so far.",
    "I've invented a sixth meal. It's called 'whenever'. It's eaten standing up, facing east.",
    "The trees drop a leaf now and then, just to keep an eye on the wind. The trees run a whole quiet network.",
    "I've thought about my father once tonight. Twice now. The fire does that - it keeps the good ghosts warm.",
    "There's a scuffle in the brush. It's mice. It's always mice. The drama of the entire world is mice, mostly.",
    "I've drawn a duck in the dirt. The duck has opinions. The duck is keeping morale up.",
    "The fire has reached the stage where it talks in colors. Blue at the bottom, gold up top. Show-off.",
    "I've named the hills. That one's Ugly. That one's Uglier. The far one is Gerald's Cousin.",
    "The night moves in ticks - cricket, breeze, ember, owl. It's a clock made of beasts. It says it's late.",
    "I've considered the stars. They've considered nothing. We're not in dialogue. It's a one-sided correspondence.",
    "This stretch of night is the color of an old bruise. The sky scrapes its knee every dusk and never learns.",
    "I've done the arithmetic - one fire, five socks, half a song, no complaints worth the name. Rich evening.",
    "The moth made it to the flame. A moment of silence for a professional.",
    "I've been practicing my fire-tending face. Solemn. Committed. Slightly warm. It needs work.",
    "Somewhere a bell rang again. Still not for us. I'm starting to take it personally.",
    "I've measured the night by the firewood. Three logs in. Two out. The mathematics of patience.",
    "The grass rustles its one page. It's a short story. It's about the wind. It's a masterpiece.",
    "I've said goodnight to the mule. The mule said nothing back. It's holding the friendship hostage for oats.",
    "The dark has settled like snow. Quiet and total. There's a comfort in a thing that's exactly itself.",
    "I've thought up a scheme. It's a small one. It involves tomorrow's bread and my pockets. That's all you get.",
    "The smoke drifts east, same as the grass leans, same as my thoughts. Everything out here has a habit but me.",
    "I've sorted tomorrow into three parts - walking, more walking, and complaining about the walking. A full docket.",
    "The last ember popped like a cork. Someone somewhere is celebrating. It's not us. But it's someone.",
    "I've watched the owl make its rounds. The owl has a system. The owl has no idea we exist. Humbling.",
    "There's a chill with teeth in it now. Autumn's way of clearing the room.",
    "I've composed my fourth ballad. It's about the sock. The sock endures. The sock is all of us.",
    "The night's dark enough to see forever. The stars are the only honest map and even they move.",
    "I've done nothing for so long that nothing has begun to feel like an achievement. Wait. It is one.",
    "The wind moved through the trees like a rumor. The leaves believed it.",
    "I've considered the road ahead. It considered me back. We're at an impasse until morning.",
    "There's a peace in the camp that only arrives after the fire settles. The fire's a bouncer. The peace got in.",
    "I've tried to hear the world turn. Heard the river instead. Close enough. The river turns too.",
    "The moon is higher and smug about it. I'd be smug too if I never had to walk anywhere.",
    "I've laid out the boots, the blade, and the bread, in order of tomorrow's importance. The bread disagrees. It's right.",
    "Somewhere a wolf sighed. Same, friend. Same.",
    "I've kept the fire fed and the dark at arm's length. That's the whole job. Some nights, it's enough job.",
    "The quiet has a texture tonight. Thick, like wool. You could cut it and make a blanket of it.",
    "I've thought about home. It's smaller every time. The fire's bigger. Make of that what you will.",
    "There's an owl overhead with a rhythm of four hoots. It's the only one out here keeping time.",
    "I've let the fire burn down to teaching temperature. It's teaching patience. It's a patient teacher. It's a flame.",
    "The dark before dawn is the oldest dark. It's seen every camp like ours. It'll see the next ones too.",
    "I've made my peace with the rock. Gerald. We've been through a lot, Gerald and I. Mostly sitting.",
    "The night owl trades shifts with the morning bird. Somewhere in between, us. The between is where we live.",
    "I've counted breaths instead of sheep. The breaths are winning. That's the correct outcome.",
    "The fire's last flame stood up, looked around, and lay down as an ember. A whole career in one night.",
    "There's a promise in the east that isn't light yet. It's the idea of light. The idea is enough to pack by.",
    "I've held my blade to the firelight. It winked. Blades do that. It's why we get along.",
    "The last sound of the night is always the river. First sound of morning too. The river works shifts.",
    "I've thought of something to say tomorrow. If I forget it, it wasn't worth the night I gave it.",
    "The moths have gone. The fire's gone half-heart. The night's gone soft. I'm the last one awake with opinions.",
    "I've watched the coals make their slow argument for morning. They always win. They're never in a hurry.",
    "There's a space in the sky where a star used to be, or where I remember one. Memory's a campfire that eats its own logs.",
    "I've made peace with the pace. The pace doesn't know. That's the best kind of peace - the kind that doesn't need the other party.",
    "The kettle clicks as it cools. Even the kettle has something to say. It's mostly 'goodnight'. I'll take it.",
    "I've sat with my back against the world's oldest wall - a hill - and told it nothing. It told me nothing back. We're even.",
    "The east is a rumor of gray. The fire's an ember of red. I'm a crust of awake. Morning's an agreement away.",
    "I've taken the night's measure. Deep, dark, generous with stars, stingy with news. Same as the last one. Worth it anyway.",
    "There's a moment before dawn when even the river hushes. It's the world holding its breath. I hold mine with it.",
    "I've folded the night up like a blanket. The creases are the hours. They'll unfold tomorrow on the road.",
    "The birds start their arguments early. They have territories. We have a fire. Everyone defends what they love.",
    "I've let the fire down gently. It did its work. That's all any of us are asked.",
    "The camp stirs before the light does. That's the order of things - the boots know before the birds.",
    "I've said nothing for an hour and it was the finest conversation I've had all week.",
    "There's a first bird, always one, always early, always wrong about the hour. Brave, though. I side with it.",
    "I've banked the fire for the day's dying and the morning's waking. It's a bridge. Fire's the only bridge we carry.",
    "The morning comes on like a slow apology from the night. Accepted. Both of us did our jobs.",
    "I've watched the stars hand the sky to the gray. A quiet change of watch. No ceremony. The best kind.",
    "There's dew on everything, including my resolve. The sun will fix one of those.",
    "I've greeted the dawn more than any lord or lady in any hall. It's never once greeted me back by name. Still shows up, though. Punctual thing.",
    "The hill's shadow runs west as the sun climbs east. Even the shadows are leaving camp. It's time.",
    "I've taken the last warm stone from the fire ring for my pocket. A souvenir of a night that asked nothing and gave warmth.",
    "The morning smells like cold iron and possibility. Mostly cold iron. The possibility needs cooking.",
    "I've tied the last lace, doused the last ember, and hung the last thought on the day. It holds. We go.",
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
    "The quiet suits you, {P}. I say that as a complaint.",
    "I've started finishing your sentences. They're all 'leave me alone'. I'm hurt.",
    "If you're sulking, the fire and I respect it. If you're scheming, include me.",
    "{P}, your silence has layers. I'm through the first two. They were all silence.",
    "Fine. I'll narrate. Chapter one - the quiet one brooded. Chapter two is the same.",
    "Two boots by the fire and one of them won't talk. I'll let you guess.",
    "You've gone quiet again. Last time this happened, I got stabbed. Related? I ask.",
    "There's a difference between listening and hiding, {P}. You're doing the one with more guilt.",
    "I'd ask what you're thinking, but I've learned to fear the answer's length.",
    "The last word you said was 'hmm'. That was an epoch ago. The 'hmm' has aged.",
    "Fine weather for a silence. Cold, with a chance of me filling it.",
    "I know that look, {P}. That's a look with a plan in it. Plans that quiet are rarely legal.",
    "Your silence is loud, friend. It drowns the crickets. They've filed a complaint.",
    "I've considered taking up your quiet as a craft. Then I remembered I'm loud by trade.",
    "If you're waiting for me to go first - I can do this for days. I'm from a large family.",
    "The quiet game. I know it. I lose it every time, on purpose, out of spite.",
    "{P}, blink if something's wrong. Don't blink if something's very wrong. That's how we got here.",
    "The fire's doing all the talking tonight and even it's run out of material.",
    "I'd trade a boot for one syllable, {P}. The good boot.",
    "You brood like it pays wages. If it does, I want in on the guild.",
    "There's thinking, and there's whatever you're doing, {P}. Thinking moves the eyebrows occasionally.",
    "The silence between us has a texture now. Gritty. Like the road, but indoors.",
    "I've counted the quiet minutes. The number is rude. I'll keep it to myself.",
    "The quiet has gone on long enough that whatever you say next will be a speech. No pressure.",
    "{P}, the moon has moved a hand since you spoke. The moon's embarrassed for you.",
    "If it's the road that's got you quiet, the road's not worth the hush. It's mostly gravel and lies.",
    "I've been quiet too. For me, that's an achievement. For you, it's a Tuesday.",
    "Say something wrong. I don't care. I've missed being corrected.",
    "The crickets asked about you, {P}. I told them you were on retreat. They respected it. I didn't.",
    "This is the longest anyone's been quiet near me without being asleep or dead. Check in, would you?",
    "You're doing the stare. The one that means either wisdom or wind. I've bet the mule on wind.",
    "Whatever you're not saying, {P}, it's gotten heavy. Set it down. My shoulders are empty.",
    "The quiet's so complete I can hear my own reputation for chatter restoring itself.",
    "I'd poke you, but the last time I poked a quiet one, I learned things about their childhood.",
    "Your pause has outlived the stew, the story, and my patience. The stew lasted longest. The stew was good.",
    "{P}, silence is a garment. You're wearing it like a cloak. Take it off before it suits you.",
    "Fine - I'll tell a story. It's about someone who wouldn't talk. It ends the same as this night. Quietly.",
    "The night asks for a little hush, {P}, not the whole inventory.",
    "I've watched you not-talk for so long I've begun to admire the craftsmanship.",
    "If you're composing your last words, save some ink. We've miles to go and I need the entertainment.",
    "Even the owl gave up and moved on. You've out-quieted an owl, {P}. There's a trophy somewhere for that.",
    "I could fill this silence, but I've decided to weaponize it. How do you like it? Loud, yes?",
    "You know what's rare out here? A secret. You're obviously holding one. It's glowing out of your ears.",
    "The quiet has a mind of its own now. It's begun answering me. That's the trouble with silence - it echoes.",
    "{P}, whatever you swallowed, either spit it out or let it go down proper. Silence is a meal nobody cooks on purpose.",
    "The stars are chatty tonight compared to you. The stars, {P}.",
    "If you're deciding something, decide louder. I'm on the edge of my seat and it's a log.",
    "I've tried silence as a companion. It doesn't laugh. It doesn't split the watch. It just sits there, like you.",
    "Your last sentence died of loneliness, {P}. Say a few words at the burial.",
    "Some silence is peace. This silence has paperwork in it. I can hear the shuffling.",
    "The fire asked me - and I quote - what's their deal. I said I'd ask.",
    "Two kinds of quiet, friend. The well-fed kind and the haunted kind. You're the kind that worries the cook.",
    "The mule's quieter than you tonight. The mule is ASLEEP. The mule still has more to say on most days.",
    "I've given up waiting. I'm now narrating your next words in advance. So far I've written 'hmm' and 'fine'.",
    "If this is a test of my patience, {P}, you've failed. It passed the test hours ago and you kept going.",
    "The silence between comrades is sacred. The silence between you and everyone is a drought. Rain, {P}.",
    "You've been quiet so long I've begun to miss your complaints. There - I said it. Complain, would you?",
    "The dark's loud with bugs, the fire's loud with pops, the world's loud with everything, and you - you're the hole in it.",
    "{P}, I respect a moody silence. I do. But even the best bread goes stale. Speak while you're fresh.",
    "I've chalked a mark on the log for every hour of your silence. The log looks like a fence now.",
    "Whatever it is - say it in three words. I'll take two. I'll take a firm nod at this point.",
    "The quiet ones either save everyone or lose everyone. I've stopped guessing which. I just keep the bandages close.",
    "You've gone so still the birds have considered you. The birds, {P}. They considered you furniture.",
    "That's the same face from the river crossing. That time it worked out. I remind you only because I'm sitting closer.",
    "The silence has a character arc now. It started mysterious. It's developed a twitch. I'm invested.",
    "{P}, if it's grief, share it. If it's rage, aim it. If it's arithmetic, I never could abide that kind of quiet.",
    "I've begun saying your lines for you in a silly voice. It's unflattering. Speak and I'll stop. That's the deal on the table.",
    "The night's not that deep yet, friend. There's plenty of dark left to fill with words. Waste not.",
    "Fine - I'll tell the one about the fish. You remember the fish. Even the fish would've spoken by now.",
    "Your silence is a wall. I've built a little gate in it. I come and go as I please. There's a garden. It's nice.",
    "{P}, the last quiet night we had, we were ambushed at dawn. I'm not saying the silence caused it. The silence absolutely caused it.",
    "The fire's burning low and so is my restraint. One of us will speak soon, and you know my record.",
    "If you're waiting for the right words, {P}, the first ones are usually it. Usually 'I'm fine'. A lie, but a start.",
    "You've mastered the art of the unsaid, friend. I've mastered the art of saying it anyway. Together we're one whole conversation.",
    "The quiet's so thick I could slice it and sell it to monks. Rich monks. Silent ones. They'd love it here.",
    "Even your shadow's gone still. Your shadow, {P}. It usually fidgets when you do. It's like the whole world's holding a note.",
    "I'd say the silence is golden, but I've never once seen gold shut anybody up. Gold starts fights. Silence hoards them.",
    "{P}, you've been quiet so long the camp's started treating it as law. The mule's taken a vow. Thanks for that.",
    "There - you almost spoke. The jaw moved. The jaw's on my side. The rest of you needs to follow.",
    "I've told the fire everything about you. It agrees. It says the quiet ones burn hottest. It flickered. Sinister.",
    "Whatever you're carrying in that hush, {P}, it's heavier with no witness. Hand me an end of it.",
    "The night's long enough to talk and still sleep. Arithmetic. I'll start. No - you. No - me. Always this.",
    "The quiet has gone from companion to architecture. We're living in it now. I've hung a coat on it. It holds the coat.",
    "{P}, speak or snore - those are the options the road allows. Anything in between is just weather with a face.",
    "You know I'll be up whichever hour you choose to break this. That's not a threat. That's my curse. I'm always up.",
    "The silence has won three rounds tonight. I want a fourth. I've got nothing left. It's a strong silence. Trained. Probably foreign.",
    "I've counted your breaths instead of sheep, {P}. You're calm as a lake. I'm the idiot waiting for the lake to speak.",
    "The quiet ones always have the best stories. That's the cruelty of it. The loud ones tell them, wrong, forever.",
    "If nothing's wrong, say nothing's wrong. If nothing's wrong and you say nothing, that's a different nothing and we both know it.",
    "I've stopped filling the silence. This is me, not filling it. Appreciate the craftsmanship. It costs me.",
    "The night's last hour's on us, {P}. Whatever the hush is holding, dawn takes no prisoners. Say it or bury it proper.",
    "The morning bell's an hour off. When it rings, the silence loses. It's still winning. A good silence. I hate it.",
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
    "I've started ranking the pebbles by personality. The flat one's winning. Barely.",
    "Standing here is a skill now. I've mastered it. Ask for lessons later.",
    "Yawned so wide I saw yesterday. Nothing much happened there either.",
    "If boredom were coin I'd buy the tavern. Then sell it for something to do.",
    "I've memorized your walk, {P}. Left foot doubts, right foot commits.",
    "Somewhere there's a battle with my name on it. This field is not that somewhere.",
};
static char const* const kMoodBlooddrunk[] = {
    "HA! Again! Line them up!",
    "My blood is singing and all the words are fight.",
    "I feel INVINCIBLE. Which is exactly how the invincible die. But HA - come on!",
    "More! The tank's not empty yet!",
    "That's the stuff! THAT'S THE STUFF!",
    "Careful, {P}, I'm magnificent right now and it wears off fast.",
    "Still standing! Count the ones that aren't. I'll wait.",
    "The fight's in my teeth now. Tastes like winning.",
    "Who's next? The ground's already full, find a spot.",
    "Adrenaline's a liar and I believe every word.",
    "My axe is laughing, {P}. Hear it? No? Your loss.",
    "Victory makes me generous. Ask for anything but my share.",
};
static char const* const kMoodHomesick[] = {
    "Nights like this I can almost smell home. Woodsmoke and burnt porridge. I'd give a toe for it. A little one.",
    "Don't mind me. The dark's just heavier when you know what you left in it.",
    "This sky's got the wrong stars for missing somebody under. Home has the right ones.",
    "You ever leave a place so good you stopped trusting good places?",
    "I'm fine. It's just - the road's long in both directions tonight.",
    "I could go home whenever I like. That's the joke. I keep choosing not to.",
    "Home's bread never tasted this good when I lived there. Distance seasons everything.",
    "I hum the old songs now. Used to mock them. The road corrects opinions.",
    "Somebody's sitting in my chair back home, {P}. I hope they appreciate the view.",
    "Letters take months. By the time they answer I've become someone else.",
    "The smell of rain here is wrong. Home rain smells of pine and iron.",
    "One day I'll walk back up that lane. Until then, this fire's home enough.",
};
static char const* const kMoodCoinheavy[] = {
    "We're RICH, {P}. Rich for us. Which is poor for kings, but kings don't know what they're missing.",
    "I keep counting it. It keeps being the same number. Glorious number.",
    "I'm buying something stupid. I've earned something stupid.",
    "Feel that weight in the purse? That's the sound of YES.",
    "Tonight we eat food with no bones in it. The good stuff.",
    "Don't tell me what things cost tonight. I don't want the truth. I want round numbers.",
    "Jingle it once more, {P}. That's the sound of options.",
    "I walk taller with a full purse. Physics. Don't question it.",
    "Some day I'll be sensible with coin. Today is not that day.",
    "The merchant's eyes went wide. Mine went wider. We understood each other.",
    "Gold makes philosophers of us all. Silver makes comedians.",
    "I'm lending you nothing. But I'll buy the round while I'm rich.",
};
static char const* const kMoodNightweary[] = {
    "The night's in my boots now. Every step's heavier.",
    "We should camp. Or keep walking and pretend we're ghosts.",
    "I'm tired enough to find everything honest. It passes by morning.",
    "Talk quiet, {P}. Loud things wake the dark.",
    "One more hour. Then I walk into a ditch and sleep where I land.",
    "The fire's winning. Fires always win eventually. That's why I respect them.",
    "My eyelids are conspiring. Traitors, both of them.",
    "Dawn feels theoretical right now. I'll believe it when it arrives.",
    "Even my shadow's dragging. Poor thing carries all of me.",
    "Yawning at the moon. It yawns back. We understand each other.",
    "Bed's a memory and the ground's a promise, {P}.",
    "One eye open. That's the whole plan. It's worked so far.",
};
// plan v5 H1: dedicated pools for the three moods that previously aliased
// homesick/blooddrunk/nightweary - the deterministic layer must agree with
// the MoodSeasoningLine the prompt carries, not contradict it
static char const* const kMoodSmitten[] = {
    "If {P} asks for the last ration again I am giving it over. Nobody else. Don't ask why.",
    "I keep noticing where {P} stands in a room. Not on purpose. It's just where my eyes go.",
    "Somebody laughed at camp last night and the whole night got better. It was {P}. Obviously it was {P}.",
    "I've started saving the good stories. The ones that land. Waste them on strangers? Not anymore.",
    "Don't look at me like that. I sharpen blades near them because the light's better here. That's all.",
    "When the road splits, I find I want our boots going the same way. Just noting it. As a fact.",
    "Aye, I saved a seat. There's always a seat. It's a seat-shaped coincidence, {P}.",
    "I'm not singing. There was humming. Humming is not singing, and you can't prove otherwise.",
    "Funny thing - bad news lands softer when {P}'s nearby. Mathematics of the road, I expect.",
    "If something happened to them I'd - well. Nothing's going to happen. I'll see to it personally.",
    "I taught them the card game wrong on purpose. Two weeks of easy winnings. Worth every guilty copper.",
    "The fire's warmer on their side. It's a known fact of fires. I've studied it. Extensively.",
};
static char const* const kMoodGrudge[] = {
    "I'm not angry. I'm keeping a ledger. Angry burns off. Ledgers don't.",
    "Fine morning. Would be finer if some people remembered what they owe. Not naming mornings. Or people.",
    "Forgive and forget, my grandmother said. She also kept a club under the counter. Wisdom is a spectrum.",
    "I counted to ten. Then I counted to the number of coppers I'm owed. Ran out of numbers.",
    "There's a conversation waiting to happen. It can wait. I'm patient. I'm VERY patient.",
    "Everyone's allowed one mistake. The second one's a choice. I keep track of choices, {P}.",
    "The silence isn't nothing. The silence is me being polite at great personal expense.",
    "I smile at them and everything. You'd almost think it was sincere. Almost. It's a whole craft.",
    "Some debts are coin. Some are words said in the wrong tone at the wrong fire. Both get collected.",
    "No, no, everything's fine. Fine like a pot with the lid on. Mind the lid.",
    "I've decided to be the bigger person. The bigger person still remembers, mind. Just quieter about it.",
    "You'll know when it's settled. There'll be a short speech. I've drafted it. There are revisions.",
};
static char const* const kMoodGrief[] = {
    "Quieter roads this week. I keep leaving a space in the line where nobody walks anymore.",
    "I poured the second cup out of habit this morning. Left it. The ground can have it.",
    "Don't mind me. Some days the sky's just heavier than the armor.",
    "I laughed at something yesterday and felt like a traitor. Grief keeps its own accounts.",
    "They'd have laughed at me moping. That's the worst of it. That's exactly the worst of it.",
    "I keep their whetstone. Someone has to keep the small things. Might as well be me.",
    "The fire's too loud tonight. Or I'm too quiet. One of the two. Maybe both.",
    "I'm all right. I'm just - walking a little slower so the road has time to make sense.",
    "You want to know the trick of it? There isn't one. You just carry it and keep your hands busy.",
    "First frost of the season. They'd have complained beautifully about it. I'm complaining for two now.",
    "I said I'd tell the stories so they'd stay loud. Working on it. Some of them still catch in the throat.",
    "Grief's just love with nowhere to go. Somebody told me that once. I understand it on Tuesdays.",
};
// plan v5 W4: the authored act-refusal bank - while an unresolved grudge
// stands, follow/party_invite execute an authored refusal instead (the
// tone ledger clears it; a paid debt settles it outright)
static char const* const kGrudgeRefuse[] = {
    "No. Not while that business between us stands unsettled, {P}. Make it right, then ask me again.",
    "Ask me tomorrow. Or ask me after you've squared what you owe. One of those might work.",
    "I don't march beside an unsettled ledger. Settle it, and my answer changes with the weather.",
    "You know what you did. I know what you did. Ask again when the air's clear.",
    "My feet don't move for folk who owe me an apology. Coins or words, {P} - either settles.",
    "There it is. The ask. Bold, considering. Square things first, then we'll talk of roads.",
    "No. And it isn't the ask - it's the asker. Not today. You know why.",
    "When you're ready to make that right, I'm ready to walk with you. Not one step before.",
    "I hold grudges the way dwarves hold ale - long, and with both hands. Settle up.",
    "The answer's no until the debt is. Nothing personal. Well. A little personal.",
    "Clear the air with me first, {P}. Then we'll see about following you anywhere.",
    "No. Ask the others if you like - but between us two, the count isn't settled.",
};
// plan v5 W8: the /notice in-character nudge bank - one hint rides the
// scene read, pointing the player at the moment (never at a mechanic)
static char const* const kSceneNudge[] = {
    "{B} keeps watching the road behind you. Maybe ask what they have seen.",
    "{B} has gone quiet in that way that means thinking. It might be worth pulling on.",
    "{B} keeps checking the sky. Old habit, or something eating at them?",
    "You keep catching {B} looking at you like a sentence they have not started yet.",
    "{B}'s hand has not left their weapon in a while. Could be nothing. Could be worth asking.",
    "{B} muttered something about this place earlier and did not finish the thought.",
    "{B} counted the party twice just now. Counting is a habit of the worried.",
    "{B} smiled at nothing in particular. Those are usually the good stories.",
};
// plan v5 C4: the authored drama set pieces - one exchange (opener,
// reply) per variant, three kinds: reunion (an old bond surfaces),
// rivalry (a sharp working argument), debt-collection (an old favor
// called in). Delivered as a staggered two-voice exchange on the party
// channel; the player merely witnesses. {P} renders as the OTHER bot's
// name (the caller passes the partner); the speakers tag themselves by
// speaking - a name prefix would read as a script, not a quarrel.
static char const* const kDramaReunion[][2] = {
    {"You still carry that dented flask? After everything?",
     "You noticed. After everything, you noticed a flask."},
    {"Last time we stood in a place like this, you nearly got us killed.",
     "Nearly! You remember the nearly. Never the part where I saved you."},
    {"I thought you were dead at Sentinel Hill, you know.",
     "I thought YOU were dead at Sentinel Hill. We are both terrible at it."},
    {"The road got long. I looked for you at the crossings.",
     "I was at the crossings. Wrong crossings. The road is a liar."},
    {"You still owe me a story from that winter.",
     "I owe you three. Come to the fire and I will pay in full."},
    {"You old wreck. You are still alive.",
     "Alive and listening. Say the rest of it. I am waiting."},
};
static char const* const kDramaRivalry[][2] = {
    {"That was MY kill and you know it.",
     "Your kill? Your intentions were nowhere near it."},
    {"You take the left flank every time. Every single time.",
     "Because you hog the middle like a troll hogs a bridge!"},
    {"I counted. I have looted more than you this week.",
     "You count. That is the whole problem with you."},
    {"If you sing that verse again I am walking into the river.",
     "Then I will sing it louder, and the fish will know your shame."},
    {"Admit it - I found the trail first.",
     "You found a trail. I found the trail. Sit down."},
    {"One day I will beat you at cards and you will finally be humble.",
     "The day you win at cards, check me for a fever first."},
};
static char const* const kDramaDebt[][2] = {
    {"Three silver. From the ferry. You remember the ferry.",
     "I remember the ferry, the rain, and your mysterious arithmetic."},
    {"You still have my whetstone.",
     "My whetstone? It sharpened my blade for two winters!"},
    {"I pulled you out of that river. That has a price.",
     "You pulled me? You dragged me by the collar like a wet cat!"},
    {"The boots. We agreed on the boots.",
     "We agreed nothing about the boots. Witnesses, anyone? No? Convenient."},
    {"I covered your tab in Lakeshire. The whole tab.",
     "And you have dined on the telling of it ever since. Paid in full."},
    {"One day I will collect everything you owe me.",
     "One day I will itemize everything you owe. Bring a wagon."},
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
    "I've a feeling we're being spared for something worse. Grand, isn't it.",
    "The crows follow us, {P}. Either we're interesting or we're dinner. Possibly both.",
    "I collect small strange stones. This one's my favourite. Don't ask why. I don't know.",
    "Somewhere a bard is singing about braver folk. Good. Let them.",
    "My boots have opinions about this road. I share most of them.",
    "If the wind had a face I'd punch it. It knows what it did.",
    "Quiet now. The trees are listening and they gossip worse than townsfolk.",
};
// plan RP E0: street short-reactions - what a bot nearby mutters when a
// player's unaddressed /say lands on a street (A6's fallback when the
// cloud street lane is off or its quota is spent; the interim street
// behavior stays emote-only, so this bank is data now and A6 wires it).
// Four speaker archetypes x 12 short reactive lines, all placeholder-free.
static char const* const kStreetShort[4][12] = {
    // speaker 0: the street guard - gruff, watchful, mildly threatening
    {"Loud talk draws eyes. Keep it down.",
     "Heard worse on this street. Not by much.",
     "Say it again and the watch will mind.",
     "Mind your tongue. This corner is watched.",
     "The curfew bell rings soon. Talk faster.",
     "Every fool with a mouth finds this street.",
     "Aye. And I've a post to stand.",
     "You shout like the walls owe you coin.",
     "The watch heard you. So did the alley.",
     "Save the speeches. Petitions go to the magistrate at dawn.",
     "Bold words for a street this fond of knives.",
     "Move along. This corner is taken."},
    // speaker 1: the market vendor - hawker's ear, commerce on the mind
    {"Heard that. Now, care for an apple?",
     "Talk is cheap at this stall. Pie is not.",
     "Strangest pitch I've heard all market day, that.",
     "Buy something or mutter elsewhere, friend.",
     "Every day a new prophet. None of them buy fish.",
     "Aye aye. The cabbages heard you too.",
     "You'd bargain better with fewer speeches.",
     "Half the market agrees. The other half haggles.",
     "Words never filled a stew pot, stranger.",
     "My prices hold firm, whatever you just said.",
     "Spices from the south! Forgive me. Habit.",
     "Loud ones are good for trade, bad for naps."},
    // speaker 2: the nervous commoner - skittish, eager to be elsewhere
    {"Oh! I wasn't listening. I mean, I was.",
     "Is that true? Please say it isn't.",
     "Don't drag me into anything. My bread is rising.",
     "The last loud stranger brought the guards running.",
     "My mother said never answer strangers. Sorry.",
     "You people and your proclamations. I'm just sweeping here.",
     "Nothing to see here. Well. Clearly something.",
     "I agree? Please don't ask me with what.",
     "Strangers keep saying things at me this week.",
     "If trouble is coming, I'll be indoors.",
     "That's brave talk. Brave talk gets remembered.",
     "I nod at everyone. It's cheaper than talking."},
    // speaker 3: the worldly traveler - wry, has seen it all twice
    {"Heard stranger in Booty Bay, and that is saying something.",
     "Every port has a corner like this one.",
     "The road teaches you to talk less, mostly.",
     "Aye, well. The desert says that differently.",
     "I once crossed a mountain range for a shorter argument.",
     "You talk like the far provinces. Good for you.",
     "There's a song in that. A bad one.",
     "The sea taught me patience. Streets teach me caution.",
     "Caravans run on gossip heavier than cargo.",
     "I've been shouted at in six dialects. Yours is polite.",
     "Somewhere east of here, that would be a compliment.",
     "Travel light, talk light. That's the trick."},
};
// plan RP E0: gate security refusals - the .whisper "invite me" family
// (invite/leader/full-group denials), voiced as diegetic distrust of
// strangers. Never the beg-refusal triggers (those are the persona
// refuseLine cells' ground) and never an actionable number - the
// numbered denials keep their facts elsewhere; these are vibe-only.
// Rows are indexed by PlayerbotLlmPersona::Archetype (gruff/shy/noble/
// rogueish, in order) and the bank is placeholder-free so the no-player
// export can draw lines verbatim.
static char const* const kSecurityRefuse[4][12] = {
    // ARCHETYPE_GRUFF
    {"No. I don't know you from a bandit.",
     "I don't march with strangers.",
     "Not while the watch has eyes on me.",
     "My blade is already sworn to this company.",
     "You talk like a recruiting sergeant. No.",
     "A full pack and a full party. Both stay shut.",
     "Lead your own road. I'll walk mine.",
     "The answer is no, and it isn't personal.",
     "I've buried enough strangers to be picky.",
     "No strangers in my line. The rule keeps us breathing.",
     "Come back when your face means something to me.",
     "Busy. And wary. Mostly wary."},
    // ARCHETYPE_SHY
    {"I - I don't join groups. Sorry.",
     "Oh. No, thank you. I'm safer alone. I think.",
     "Strangers make me nervous. It isn't you. Well.",
     "I can't. My master would worry where I went.",
     "Please don't be hurt. The answer is still no.",
     "I barely know you. Sorry. Truly.",
     "Small groups. Very small. This one is full.",
     "Oh! No. I'd only slow you down. Really.",
     "Someone I trust said never follow strangers.",
     "I'm waiting for someone. I can't leave.",
     "No. Um. That's my whole answer, I'm afraid.",
     "It sounds nice. It still sounds like trouble."},
    // ARCHETYPE_NOBLE
    {"I must decline. I do not know your character.",
     "My company is by bond, not by chance.",
     "Your invitation does you credit. The answer is no.",
     "I do not hand my banner to strangers.",
     "The company I keep is already sworn full.",
     "Ask again when we have shared a road.",
     "Honour forbids me following an unproven name.",
     "I am obliged elsewhere, and by older vows.",
     "Trust is earned in leagues, not asked in doorways.",
     "Were we acquainted, my answer might differ. We are not.",
     "A place in my company is not mine to give.",
     "I decline with respect, and finally."},
    // ARCHETYPE_ROGUEISH
    {"Tempting. No. Well. No.",
     "I work alone. Ask around. It's safer for everyone.",
     "You don't want me in your group. Trust me.",
     "My price for trust is higher than your purse.",
     "A stranger asking to join? My, the nerve.",
     "Not today. Not tomorrow. Ask the day after.",
     "Full house. Card sharp's honor.",
     "I don't follow leaders I can't out-drink.",
     "First rule: never join a group you can't leave loudly.",
     "Busy. There's a complicated errand. Don't ask.",
     "Lead? Friend, I don't even lead myself.",
     "You seem trustworthy. That's exactly what worries me."},
};

// plan RP E1: the authored cheer pool - the level-up beat's authored leg.
// The generated event note keeps its cadence (generation + emote marker);
// ONE grouped bot also voices a cheer from this ring-deduped pool at the
// event drain (the E0 condolence delivery pattern - QueueAuthoredReaction,
// 2-5 s notBefore). 24 lines: small enough to stay punchy, deep enough that
// a season of level-ups never repeats verbatim at one fire.
static char const* const kCheer[] = {
    "HAI {P}! Now THAT deserved a shout!",
    "One more step up the mountain, {P}. Well climbed.",
    "Yes! The whole camp felt that one.",
    "Drink it in, {P}. Moments like that pay for the road.",
    "That's the sound of getting stronger. I heard it. We all heard it.",
    "Look at you, {P}. Growing like a weed. The good kind of weed.",
    "The birds sing louder today. I choose to believe it's for you, {P}.",
    "That's one for the story, {P}. The story's getting good.",
    "You shine a little brighter, {P}. Even the fire noticed.",
    "HA! Take it, {P} - you earned it twice over.",
    "Stronger again, {P}? The road's going to regret teaching you.",
    "A toast! Water in the cups, celebration in the hearts, {P} at the center.",
    "There it is. That's why we haul you around, {P}.",
    "The stars did their work tonight, and so did you, {P}.",
    "Louder than the wolves, prouder than the peaks - that's you, {P}.",
    "One day they'll tell this about you, {P}. They'd better - I'm telling everyone.",
    "Feel that warmth, {P}? That's not the fire. That's you, getting brighter.",
    "Another door opens for you, {P}. March through it.",
    "The trees should bow. I'll do for them, {P}.",
    "That's the good kind of thunder, {P} - the kind you make yourself.",
    "Every step of the road made you, {P}. Now you're making the road look easy.",
    "I'd sing, {P}, but last time I sang a village fled. Take the applause instead.",
    "Well now - the road taught you that, {P}, and you taught it right back.",
    "Raise the waters, {P} - the strongest toast we'll pour all season.",
};

// plan RP E1: the archetype seasoning bank - one spoken phrase composed
// onto the drawn greet tier line (GreetingLine draws the tier pool, then
// seasons it here; the phrase draw is seeded per (bot, archetype lane)
// with RACE mixed in per the plan, so same-class bots of different races
// vary their phrase - the additive layer, never a replacement draw, which
// would strand half the population in SHY since ArchetypeFor is class-only).
static char const* const kArchetypePhrase[4][12] = {
    // ARCHETYPE_GRUFF
    {
        "Mind the blade.",
        "Steel remembers.",
        "Keep your chin down in a scrap.",
        "Axe needs a whetstone tonight.",
        "Blood and iron, friend.",
        "Don't start what I finish.",
        "My shield's yours if it comes to that.",
        "I've eaten worse breakfasts than this road.",
        "Growl back at it. Works for me.",
        "Rest the arm. Swing later.",
        "The strong carry the tired. Today I'm strong.",
        "First round's on whoever wins the argument.",
    },
    // ARCHETYPE_SHY
    {
        "Softly, if you can manage it.",
        "I - apologies. Go on.",
        "I prefer the quiet corners, truly.",
        "Forgive me. Watching, not talking.",
        "I'll be behind you. Far enough to think.",
        "The fire's nicer than the fighting, isn't it?",
        "You speak well. I'll listen.",
        "It's a lot, all this. Goodly.",
        "Books before blades, if anyone asks me.",
        "I'm mending something. It helps me think.",
        "The stars are out early. I counted.",
        "Save me a place at the light's edge.",
    },
    // ARCHETYPE_NOBLE
    {
        "Honor demands it. So do I.",
        "The Light keeps this road, I trust.",
        "Walk upright. It matters.",
        "My word, once given, holds.",
        "Have courage. Right is on our side.",
        "Vigilance is a virtue. Sleep is a reward.",
        "Charity first. Steel second.",
        "Blessings on this camp and its fools.",
        "The old oaths still carry weight.",
        "Patience is also a weapon. The sharp kind.",
        "Courage, and a care for the small folk.",
        "The crown, the Light, then the ledger.",
    },
    // ARCHETYPE_ROGUEISH
    {
        "Keep your purse quiet and your feet quicker.",
        "Don't ask where the knife's been.",
        "Doors open for the quiet and the quick.",
        "I count exits. Habit. Good habit.",
        "Small favors, small knives, same rules.",
        "Trust the plan. I wrote the plan.",
        "Sharp eyes, light fingers, no witnesses.",
        "That's a fine purse. Well. Observed, only observed.",
        "Everyone's honest. At the right price.",
        "Locks are just doors with opinions.",
        "Coin first, questions later.",
        "I know a shortcut. It's legal. Mostly.",
    },
};
} // namespace detail

// plan RP E1: the archetype seasoning rows (4 archetypes x 12 spoken
// phrases; row order = the persona Archetype enum, GRUFF..ROGUEISH).
// Served separately from Pool(): the seasoning is COMPOSED onto a tier
// draw, never drawn as a greeting of its own.
inline char const* const* ArchetypePhraseRow(int archetype, size_t& count)
{
    count = sizeof(detail::kArchetypePhrase[0]) / sizeof(void*);
    if (archetype < 0 || archetype > 3)
        return nullptr;
    return detail::kArchetypePhrase[archetype];
}


// plan v5 C4: the drama exchange accessor - kind 0 reunion, 1 rivalry,
// 2 debt-collection; six variants each; the pair is (opener, reply) and
// {P} renders as the OTHER bot's name
inline char const* const* DramaPairTable(int kind, size_t variant)
{
    if (variant >= 6)
        variant = 0;
    switch (kind)
    {
        case 1: return detail::kDramaRivalry[variant];
        case 2: return detail::kDramaDebt[variant];
        default: return detail::kDramaReunion[variant];
    }
}

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
        case POOL_MOOD_SMITTEN: count = sizeof(kMoodSmitten)/sizeof(void*); return kMoodSmitten;
        case POOL_MOOD_GRUDGE: count = sizeof(kMoodGrudge)/sizeof(void*); return kMoodGrudge;
        case POOL_MOOD_GRIEF: count = sizeof(kMoodGrief)/sizeof(void*); return kMoodGrief;
        case POOL_GRUDGE_REFUSE: count = sizeof(kGrudgeRefuse)/sizeof(void*); return kGrudgeRefuse;
        case POOL_SCENE_NUDGE: count = sizeof(kSceneNudge)/sizeof(void*); return kSceneNudge;
        case POOL_WILDCARD: count = sizeof(kWildcard)/sizeof(void*); return kWildcard;
        // E0: the flattened 4x12 banks - row-major, so the 48 pointers are
        // contiguous and valid to walk as one pool (the content-invariants
        // leg does); the game draws per-archetype 12-line cells instead
        case POOL_STREET_SHORT: count = sizeof(kStreetShort)/sizeof(void*); return kStreetShort[0];
        case POOL_SECURITY_REFUSE: count = sizeof(kSecurityRefuse)/sizeof(void*); return kSecurityRefuse[0];
        case POOL_CHEER: count = sizeof(kCheer)/sizeof(void*); return kCheer;
        default: count = 0; return 0;
    }
}

// plan RP E0: the 4x12 archetype cell accessors. The caller picks the row
// (the persona's Archetype enum orders the security bank's rows; A6 picks
// the street speaker group) and the recency ring picks the line. An
// out-of-range group folds to row 0 - the caller never gets a short cell.
inline char const* const* StreetShortCell(size_t group, size_t& count)
{
    count = sizeof(detail::kStreetShort[0]) / sizeof(void*);
    return detail::kStreetShort[group < 4 ? group : 0];
}

inline char const* const* SecurityRefuseCell(size_t group, size_t& count)
{
    count = sizeof(detail::kSecurityRefuse[0]) / sizeof(void*);
    return detail::kSecurityRefuse[group < 4 ? group : 0];
}

// ------------------------------------------------------------ mood state ----
// (the mood model is documented at the first mood-state block above the
// pools; MoodPoolOf maps mood -> pool, including the plan-v5 dedicated
// smitten/grudge/grief pools)
inline int MoodIndexOf(uint32_t botGuid, uint32_t tickBucket, uint32_t nudges)
{
    // slow weather: the bucket advances ~hourly upstream, nudges come
    // from grudge/smitten/grief events; both fold in GUID-stably
    uint32_t h = botGuid * 0x9E3779B9u + tickBucket * 0x85EBCA6Bu + nudges * 0xC2B2AE35u;
    h ^= h >> 13;
    h *= 0x5BD1E995u;
    h ^= h >> 15;
    return h % MOOD_COUNT;
}

inline char const* MoodSeasoningLine(int mood)
{
    static char const* const v[MOOD_COUNT] = {
        "Your weather right now: restless and bored. Small things itch; you would welcome any distraction.",
        "Your weather right now: blood-drunk from the last fight. Loud, bright, a little larger than life.",
        "Your weather right now: homesick. The far-away aches a little; familiar things land softer.",
        "Your weather right now: coin-heavy and pleased. The purse is full and everything looks affordable.",
        "Your weather right now: night-weary. Heavy boots, honest tongue; loud things grate.",
        "Your weather right now: smitten. Someone here shines a little brighter than the rest, and it shows.",
        "Your weather right now: nursing a grudge. An old sore colors the edges; the grudge itself stays unsaid.",
        "Your weather right now: grieving. A recent loss sits close; you are quieter, and gentle things sting.",
    };
    return (mood >= 0 && mood < MOOD_COUNT) ? v[mood] : v[0];
}

inline PoolId MoodPoolOf(int mood)
{
    // plan v5 H1: smitten/grudge/grief carry their OWN pools - the alias
    // table made the deterministic layer contradict its prompt seasoning
    static PoolId const v[MOOD_COUNT] = {
        POOL_MOOD_BORED, POOL_MOOD_BLOODDRUNK, POOL_MOOD_HOMESICK,
        POOL_MOOD_COINHEAVY, POOL_MOOD_NIGHTWEARY,
        POOL_MOOD_SMITTEN, POOL_MOOD_GRUDGE, POOL_MOOD_GRIEF,
    };
    return (mood >= 0 && mood < MOOD_COUNT) ? v[mood] : POOL_IDLE;
}

// Volatility scaling: dial 0-100 (50 = default). Returns the mood-slot
// weight multiplier for the ambient pool table: 0 halves mood presence
// (steady weather), 100 doubles it (changeable weather). Pure linear,
// applied by the caller to the mood entries' draw odds.
inline int MoodWeightPermille(int volatilityDial)
{
    // out-of-band dials (>100, the unset sentinel path) follow the
    // default; negative folds to 50 the same way
    if (volatilityDial < 0 || volatilityDial > 100) volatilityDial = 50;
    return 500 + volatilityDial * 10; // 500..1500 permille around the 1000 default
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

// C7 (plan v2.3): the boot nonce - ONE store per process (the world's
// StateFor chokepoint sets it from wall time before the first draw;
// the host harness never sets it, so the golden fingerprint stays the
// deterministic baseline). A nonzero nonce mixes into every
// InitBanterState seed so two boots never replay the identical draw
// sequence per (bot, pool) - R6's greet verbatim replay across
// restarts. LLMGreetMemory = 0 leaves the zero nonce: the
// kill-switch's verbatim-replay promise.
namespace detail
{
inline std::atomic<uint32_t>& BootNonceSlot()
{
    static std::atomic<uint32_t> nonce(0);
    return nonce;
}
}
inline uint32_t BanterBootNonce()
{
    return detail::BootNonceSlot().load(std::memory_order_relaxed);
}
inline void SetBanterBootNonce(uint32_t v)
{
    detail::BootNonceSlot().store(v, std::memory_order_relaxed);
}

inline void InitBanterState(BanterState& s, uint32_t botGuid, uint32_t audienceKey)
{
    // C7: the boot-nonce term (zero on the host - the golden baseline;
    // a fresh nonzero nonce per world process breaks the cross-restart
    // verbatim replay)
    s.rng = botGuid * 0x9E3779B9u ^ audienceKey * 0x85EBCA6Bu
        ^ BanterBootNonce() * 0xC2B2AE35u ^ 0xA5A5A5A5u;
    if (!s.rng) s.rng = 0x1234567u;
    s.ringLen = s.ringPos = 0;
    s.cooldownUntilMs = s.lastWildcardMs = 0;
    s.draws = 0;
    SplitMix32(s.rng); SplitMix32(s.rng);
}

// C1 (plan v2.3): render-time first-meeting rewording. A pairing past
// its first meeting must not read "met <name> for the first time"
// forever (R5's stale-first-meeting prose). This rewrites
// first-meeting-shaped rows at RENDER time - no schema write,
// idempotent, reversible, reaches model-authored rows too, and keeps
// created_at (tenure) intact. Replacements are corpus-shaped
// (verb-initial past-tense clauses <= 8 words) and PREFIX-STABLE (the
// "met " prefix the met-dedupe and tenure anchors read); the seed
// rotates three phrasings so restart drills never read verbatim.
inline bool IsFirstMeetingRow(std::string const& fact)
{
    return fact.size() > 24 &&
        fact.rfind("met ", 0) == 0 &&
        fact.find(" for the first time") != std::string::npos;
}

inline std::string RewordFirstMeetingRow(std::string const& fact, uint32_t seed)
{
    if (!IsFirstMeetingRow(fact))
        return fact;
    size_t const tail = fact.find(" for the first time");
    std::string const who = fact.substr(4, tail - 4);
    static char const* const kTails[3] = {
        " once on the road back",      // 7 words with a one-word name
        " before the seasons turned",  // 6
        " some quiet while ago",       // 6
    };
    return "met " + who + kTails[seed % 3];
}

// C3 (plan v2.3): the town-talk clause shaper. The dossier row is minted
// DE-FRAMED (a bare clause - the greeting rider and the murmur {E}
// templates own the single frame; the old "The word on X: <fact>" row
// double-framed at both consumers). The per-category template supplies
// the grammatical SUBJECT (the player's name) so noun-phrase facts read
// whole in any frame, and the name keeps GossipAbout's word-boundary
// match working. Categories are the LogFact whitelist set; anything
// else folds to the shared-event shape.
inline std::string TownTalkClause(std::string const& category,
    std::string const& playerName, std::string const& fact)
{
    // tone prefixes never reach a town row (the caller strips), but the
    // shaper is total: strip defensively rather than trust every caller
    std::string text = fact;
    size_t const toneEnd = text.find(") ");
    if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
        text = text.substr(toneEnd + 2);
    if (text.empty() || playerName.empty())
        return text;
    if (category == "preference" || category == "opinion")
        return playerName + " " + text;   // "Varleigh prefers a quiet road"
    if (category == "player-identity")
        return playerName + " " + text;   // "Varleigh knows the old road songs"
    return text;                           // shared-event: verb-initial already
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

// ----------------------------------------------------------- arbitration ----
// plan v5 F7: pure budget primitive for the authored-line hourly ceiling.
// The caller owns the timestamp deque (seconds) and the mutex; this only
// prunes entries older than the window. Check-then-stamp stays with the
// caller so one lock can cover the global + per-category pair atomically
// (a stamp without a line, or a line without a stamp, is the bug this
// split exists to avoid).
inline void ArbiterPrune(std::deque<int64_t>& stamps, int64_t now, int64_t windowSec)
{
    while (!stamps.empty() && now - stamps.front() >= windowSec)
        stamps.pop_front();
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
