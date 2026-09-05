#ifndef _PlayerbotLlmChatter_h
#define _PlayerbotLlmChatter_h

/*
 * The world-chatter scheduler - ambient bot-to-bot
 * speech in three layers (proximity murmur, party banter, rare
 * general-channel set pieces) plus the authored event-grounded floor,
 * under the doctrine SILENCE IS THE DEFAULT: no line is generated,
 * queued or delivered without a real fact-bank row behind it (world
 * gossip rows, player facts, verified events - the rows the bridge and
 * memory layers already write). The pure decision machinery (power-ladder
 * policy, world
 * repetition ring, fatigue + legend ledger, floor templates, the frozen
 * murmur/composer prompt wording) lives in PlayerbotLlmChatterCore.h and
 * is pinned by the host test suite; this class owns the game state: the
 * queue, the power file the app refreshes, the async batch workers and
 * the world-thread delivery.
 *
 * Threads: Tick/delivery/hooks run on the WORLD thread only. The batch
 * workers (device murmur line, cloud composer script, campfire saga)
 * run detached and touch no Player* - they receive copied strings and
 * hand validated entries back through the mutex-guarded queue. Device
 * generations pass the shared duty-cycle governor
 * (PlayerbotLLMInterface::GovernorAdmit) and the resident embedded
 * server's endpoint - never a second runtime.
 *
 * Power: the app computes the collapsed rung (off / low-battery dim /
 * normal) from battery + charging only and publishes it in the chatter
 * power file (AiPlayerbot.LLMChatterPowerFile), staged at world start and
 * re-staged on battery events; Tick re-reads it every pass. A
 * missing/disabled file = chatter OFF - fail toward silence. Writer and
 * world share one process, so the file carries no staleness protocol.
 */
#include <cstdint>
#include <string>
#include <vector>

#include "PlayerbotLlmChatterCore.h"

class Player;

class PlayerbotLlmChatter
{
public:
    // World-thread scheduler tick (the RandomPlayerbotMgr telemetry
    // block, ~10 s): reconcile the power rung, drain due queue entries
    // (ring + fatigue re-vetted at delivery), and decide batch refills,
    // party-idle rolls and the rare global roll. All gates live inside;
    // it is a no-op when chatter is off.
    static void Tick();

    // The interruption rule: player chat owns the channel. Stamp
    // from the ChatReplyDo trigger gate on every real-player
    // conversational trigger; murmur and party delivery pause while the
    // stamp is fresh (kPlayerChannelHoldSec).
    static void NotePlayerInteraction(uint32 playerGuid);

    // Party-layer event note: a duel completed by/against a real player
    // (from PlayerbotLlmMemory::OnDuelComplete) outranks the idle roll -
    // the group's next party window fires promptly instead of waiting
    // out the ~10-15 min cadence.
    static void OnDuelCompleted(Player* participant, Player* opponent);

    // plan v5 C3: remember a real master's PARTY line so the next
    // composer exchange can argue about what the player said (consumed
    // within 120 s by the roundtable row, quota-capped)
    static void NotePartyLine(uint32 playerGuid, std::string const& line);

    // plan v5 C2: hand narrator sys-lines to the world thread - worker
    // threads (the recap prose path) never send chat packets themselves;
    // Tick drains the queue on the world thread, fail-closed when the
    // player left
    static void DeliverSysLines(uint32 playerGuid,
        std::vector<std::string> const& lines);

    // plan v5 F4b: the staged long-form delivery lane (the campfire saga
    // and every future 200-byte-per-line performance). The block is ONE
    // performance: the F7 hourly ceiling is charged once here, the
    // per-line fatigue vet is bypassed (the daily quota is the cap)
    static void EnqueueLongForm(pocketllm::ChatterLayer layer, uint32 speakerGuid,
        std::vector<std::string> const& lines, std::string const& factKey);

    // plan v5 C1: the campfire saga trigger - a SEATED master at rest
    // with a tier-3+ storyteller holding real shared-event memories gets
    // one quota-capped cloud call that stages the pairing's own story
    // back to the fire (the first safe line becomes a town gossip row).
    // Cloud tier only; false on any unmet gate
    static bool TryBeginCampfireSaga(Player* master);
};

#endif
