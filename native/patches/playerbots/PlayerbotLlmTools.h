#ifndef _PlayerbotLlmTools_h
#define _PlayerbotLlmTools_h

#include <cstdint>
#include <string>
#include <vector>

#include "PlayerbotLlamaRuntime.h"

class Player;

/*
 * Tool-calling for the LLM backends (A0: both the in-process llama runtime
 * and the HTTP chat-completions path route their generations through
 * ExtractAndQueue before anything is voiced). One decode pass may emit
 * dialogue text plus zero or more structured side-effects in the form
 *     <<tool_name field="value" field2="value2">>
 * The marker deliberately does not start with '*' or '[' so the chat packet
 * builder's emote-line routing can never misroute it, and blocks are stripped
 * from the raw generation output BEFORE ParseResponse runs (its regexes
 * corrupt JSON-ish payloads).
 *
 * Since A2 the vocabulary is the full trained set - the four persistence
 * tools plus the six ACT tools (duel_challenge, give_item, follow,
 * party_invite, move_to, loot_roll) - and admission is LICENSED: a call
 * queues only when the bridge's note for that generation carried the
 * tool (stamp-checked again at execution; see PlayerbotLlmBridge).
 * Nothing executes off raw model output: parsed calls are queued inert
 * and executed on the WORLD thread by PlayerbotAI::UpdateAI, validated
 * against live game state at execution time - never against the model's
 * claims. move_to stays executor-gated until S7's POI resolution;
 * combat and pathing outside the licensed set stay out of scope.
 *
 * The scanner, whitelist, trained emote table and beat predicates live
 * in PlayerbotLlmToolsCore.h (pure, host-compilable, battery-pinned).
 */
class PlayerbotLlmTools
{
public:
    // Extracts every <<tool ...>> block from raw output, queues the calls for
    // the bot (tagged with the generation's call source and the speaking
    // player's GUID so persistence tools attribute to the interlocutor, not
    // the bot's owner), and returns the cleaned dialogue text.
    static std::string ExtractAndQueue(std::string const& raw, uint32 botGuid,
        uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source,
        uint64_t licenseStamp);

    // A7: the TRAINED TOOLS_NOTE wording (prtools3 v3.2 instruction
    // paragraph + the F7 example-block rotation, keyed on the bot's GUID)
    // - identical text and selection discipline to the trained system
    // prompt's segment 2 (PlayerbotLlmPrompt.h), so every prompt path
    // speaks the instruction distribution the weights were trained on.
    // The legacy unkeyed form is gone: a bot must never see two different
    // example blocks across its turns.
    static std::string ToolInstructions(uint32 botGuid);

    // World-thread executor: validates each queued call against live state
    // and applies its side-effect. Call from PlayerbotAI::UpdateAI only.
    static void ExecutePending(Player* bot);

    // S8/A18: the deterministic text-emote path shared by the licensed
    // perform_emote executor and the authored crowd tier (SMSG_TEXT_EMOTE
    // through HandleTextEmoteOpcode - the authentic "X grins." line plus
    // the EmotesText.dbc animation). No license involved: the caller
    // (bridge-authored machinery) IS the authority. No-op on an
    // unresolvable emote name or a dead/out-of-world bot.
    static void PlayTextEmote(Player* bot, Player* target, std::string const& emoteName);
};

#endif
