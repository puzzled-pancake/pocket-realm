#ifndef _PlayerbotLlmChatter_h
#define _PlayerbotLlmChatter_h

/*
 * S10/E6: the world-chatter scheduler (plan SS4.6b) - ambient bot-to-bot
 * speech in three layers (proximity murmur, party banter, rare
 * general-channel set pieces) plus the authored event-grounded floor,
 * under the doctrine SILENCE IS THE DEFAULT: no line is generated,
 * queued or delivered without a real fact-bank row behind it (world
 * gossip rows, player facts, verified events - the bank A19/S8 already
 * write). The pure decision machinery (power-ladder policy, world
 * repetition ring, fatigue + legend ledger, floor templates, the frozen
 * murmur/composer prompt wording) lives in PlayerbotLlmChatterCore.h and
 * is pinned by the host battery; this class owns the game state: the
 * queue, the power file the app refreshes, the async batch workers and
 * the world-thread delivery.
 *
 * Threads: Tick/delivery/hooks run on the WORLD thread only. The batch
 * workers (device murmur line, cloud composer script) run detached and
 * touch no Player* - they receive copied strings and hand validated
 * entries back through the mutex-guarded queue. Device generations pass
 * the shared duty-cycle governor (PlayerbotLLMInterface::GovernorAdmit)
 * and the resident embedded server's endpoint - never a second runtime.
 *
 * Power: the app computes the ladder rung (PowerManager thermal headroom
 * + battery + charging + connectivity) and publishes it in the chatter
 * power file (AiPlayerbot.LLMChatterPowerFile); Tick re-reads it every
 * pass. Missing/disabled file = chatter OFF; a stale file (writer died)
 * degrades to the authored floor - both fail toward silence.
 */
#include <cstdint>
#include <string>

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

    // The interruption rule (E1/E6): player chat owns the channel. Stamp
    // from the ChatReplyDo trigger gate on every real-player
    // conversational trigger; murmur and party delivery pause while the
    // stamp is fresh (kPlayerChannelHoldSec).
    static void NotePlayerInteraction(uint32 playerGuid);

    // Party-layer event note: a duel completed by/against a real player
    // (from PlayerbotLlmMemory::OnDuelComplete) outranks the idle roll -
    // the group's next party window fires promptly instead of waiting
    // out the ~10-15 min cadence.
    static void OnDuelCompleted(Player* participant, Player* opponent);
};

#endif
