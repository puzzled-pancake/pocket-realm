#ifndef _PlayerbotLlamaRuntime_h
#define _PlayerbotLlamaRuntime_h

#include <cstdint>
#include <string>
#include <vector>

/*
 * In-process llama.cpp backend for playerbot LLM chat (arm64-v8a only).
 *
 * Linked directly into libpocket_world_runtime instead of an HTTP sidecar:
 * one dedicated worker thread pinned to the mid cores (3-5 on 8 Gen 2 class
 * devices), a separate watchdog thread that aborts hung decodes through the
 * ggml abort callback, and per-bot warm slots that keep a token history so
 * continuing a conversation only decodes the delta (prefix-cache effect).
 *
 * Any change to how prompt segments are assembled MUST bump
 * POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION so warm slots are invalidated
 * instead of being reused against a different token prefix.
 */
#define POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION 1

class PlayerbotLlamaRuntime
{
public:
    enum LlmCallSource
    {
        LLM_SRC_CHAT_REPLY,
        LLM_SRC_RPG_CHAT,
        LLM_SRC_DEBUG
    };

    // true when the in-process backend is selected, compiled in and the
    // model file loaded (or still loading) successfully
    static bool Enabled();

    // blocking call - must only be invoked from a worker/std::async thread,
    // never from the world or MapUpdater threads. Returns "" on soft
    // rejection, "error" on hard failure (mirrors HTTP path conventions).
    static std::string Generate(const std::string& prompt, uint32 botGuid, LlmCallSource source,
        int timeOutSeconds, std::vector<std::string>& debugLines);

    // companion mode switches the runtime profile for dedicated sit-and-talk
    // sessions (world paused); coexistence is the default profile
    static void SetCompanionMode(bool companion);

    // drop all warm slots (called when the prompt format version changes)
    static void InvalidateSlots();

    // decode the raw prompt into a live slot without generating, so the next
    // real generation starts warm
    static void PreWarmSlot(uint32 botGuid, const std::string& prompt);
};

#endif
