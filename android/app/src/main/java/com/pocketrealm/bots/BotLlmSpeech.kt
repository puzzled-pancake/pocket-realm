package com.pocketrealm.bots

/**
 * Per-preset AI speech overrides for the playerbot LLM. Every value is
 * 0/-1 = "follow the model's tuned profile" (the registry sampling/tier
 * values the models were validated against); anything above the sentinel
 * is clamped into the supported band and emitted into THIS preset's
 * appended LLM conf block at realm start — never into the base
 * `playerbotConfig()` (the merge-order contract keeps the base profile
 * free of LLM keys, and the frozen adv/legacy identity digests hash that
 * text, so speech knobs deliberately do not mint a new preset identity).
 *
 * Engine, model and the global speech switches live in Settings →
 * AI bot LLM; these knobs only shape how THIS preset's bots speak.
 *
 * Phase 2 adds the RP layer: per-block pack deltas (block id → enabled,
 * null = follow the global pack) plus RP dials (initiative, volatility,
 * reactivity, long-form license). The dials reach the native layer as
 * LLMRp* conf values consumed by the Phase-3 mood/initiative/long-form
 * machinery (llm_banter_core.h and PlayerbotLlmMemory.cpp).
 */
data class BotLlmSpeech(
    /**
     * Reply-length cap in tokens (`AiPlayerbot.LLMMaxNewTokens`), or 0 to
     * follow the model profile (then the global advanced-tier override,
     * then the model's tuned default).
     */
    val replyTokens: Int = 0,
    /**
     * Bot-to-bot conversation chance (`AiPlayerbot.LLMBotToBotChatChance`),
     * or -1 to follow the model tier. 0 explicitly disables bot-to-bot
     * chat for this preset.
     */
    val botToBotChatChance: Int = -1,
    /** Memory facts cap (`AiPlayerbot.LLMFactsCap`), or 0 for the tier default. */
    val factsCap: Int = 0,
    /** Recent memories carried into prompts (`AiPlayerbot.LLMMemoriesTail`), or 0 for the tier default. */
    val memoriesTail: Int = 0,
    /**
     * Per-block pack deltas: block id → enabled override. Absent id =
     * follow the global pack (the sentinel discipline, like botToBot -1).
     * Unknown ids are dropped at normalize time.
     */
    val packDeltas: Map<String, Boolean> = emptyMap(),
    /**
     * RP dials, 0-100, or -1 = follow the global default (50). Initiative:
     * how often the bot opens conversation; volatility: mood-transition
     * rate; reactivity: event-shortcut eagerness; longForm: taste for the
     * 150-word telling shapes (gated by the tier's max-tokens license).
     */
    val initiative: Int = -1,
    val volatility: Int = -1,
    val reactivity: Int = -1,
    val longForm: Int = -1,
    /**
     * D3 (plan v2.3 §5): per-preset cap on the staged chatter-power rung
     * (`AiPlayerbot.LLMChatterPowerFile` in the appended LLM conf names the
     * staged file; the RUNG line inside it is what the native scheduler
     * re-reads every tick). -1 = follow the computed ambience state (the
     * default - staged bytes unchanged); 0 forces this preset's chatter
     * OFF even when the ambience toggle is on; 1..4 cap the rung. The cap
     * may only LOWER the computed rung: the low-battery courtesy dim and
     * the master ambience toggle always win, and a preset can never force
     * chatter against either.
     */
    val chatterRung: Int = CHATTER_RUNG_FOLLOW,
) {
    init {
        require(replyTokens == 0 || replyTokens in MIN_REPLY_TOKENS..MAX_REPLY_TOKENS)
        require(botToBotChatChance == -1 || botToBotChatChance in 0..MAX_BOT_TO_BOT_CHANCE)
        require(factsCap == 0 || factsCap in MIN_FACTS_CAP..MAX_FACTS_CAP)
        require(memoriesTail == 0 || memoriesTail in MIN_MEMORIES_TAIL..MAX_MEMORIES_TAIL)
        require(initiative == RP_FOLLOW_SENTINEL || initiative in MIN_RP_DIAL..MAX_RP_DIAL)
        require(volatility == RP_FOLLOW_SENTINEL || volatility in MIN_RP_DIAL..MAX_RP_DIAL)
        require(reactivity == RP_FOLLOW_SENTINEL || reactivity in MIN_RP_DIAL..MAX_RP_DIAL)
        require(longForm == RP_FOLLOW_SENTINEL || longForm in MIN_RP_DIAL..MAX_RP_DIAL)
        require(chatterRung == CHATTER_RUNG_FOLLOW || chatterRung in CHATTER_RUNG_OFF..CHATTER_RUNG_NORMAL)
    }

    /** True when every knob still sits at its "follow the model" sentinel. */
    fun isDefault(): Boolean =
        replyTokens == 0 && botToBotChatChance == -1 && factsCap == 0 && memoriesTail == 0 &&
            packDeltas.isEmpty() && initiative == -1 && volatility == -1 &&
            reactivity == -1 && longForm == -1 && chatterRung == CHATTER_RUNG_FOLLOW

    companion object {
        const val MIN_REPLY_TOKENS = 24
        const val MAX_REPLY_TOKENS = 600
        const val MAX_BOT_TO_BOT_CHANCE = 100
        const val MIN_FACTS_CAP = 4
        const val MAX_FACTS_CAP = 24
        const val MIN_MEMORIES_TAIL = 2
        const val MAX_MEMORIES_TAIL = 12
        const val MIN_RP_DIAL = 0
        const val MAX_RP_DIAL = 100
        const val RP_FOLLOW_SENTINEL = -1

        /**
         * D3: chatter-power rung values — must move with the native
         * pocketllm::ChatterRung enum (and ChatterPowerMonitor's copy).
         * The sentinel follows the computed ambience state; 0..4 mirror
         * the rungs the staged power file carries.
         */
        const val CHATTER_RUNG_FOLLOW = -1
        const val CHATTER_RUNG_OFF = 0
        const val CHATTER_RUNG_EMERGENCY = 1
        const val CHATTER_RUNG_CRITICAL = 2
        const val CHATTER_RUNG_CONSTRAINED = 3
        const val CHATTER_RUNG_NORMAL = 4

        /**
         * Known seasoning block ids a preset may override. References the
         * pack model's universe directly - one list, no drift when a
         * block is added (the driver's native kBlocks list and the bakeoff
         * script keep their own copies, pinned by tests).
         */
        val PACK_DELTA_IDS: Set<String> =
            com.pocketrealm.llm.LlmPromptPack.SEASONING_IDS

        fun normalizeDial(value: Int): Int =
            if (value < 0) RP_FOLLOW_SENTINEL else value.coerceIn(MIN_RP_DIAL, MAX_RP_DIAL)

        /**
         * Route a stepper tap that lands between the 0 sentinel and the
         * band floor: an up-step from "follow model" snaps to the floor,
         * a down-step into the gap lands back on 0 (normalize would
         * otherwise clamp it UP, stranding one button or the other).
         */
        fun routeStepper(value: Int, stored: Int, min: Int): Int = when {
            value >= min -> value
            value > 0 && stored == 0 -> min
            else -> 0
        }

        /**
         * Clamp persisted/imported values into the supported bands,
         * mapping negatives (except the botToBot sentinel) to "default".
         */
        fun normalize(
            replyTokens: Int,
            botToBotChatChance: Int,
            factsCap: Int,
            memoriesTail: Int,
            packDeltas: Map<String, Boolean> = emptyMap(),
            initiative: Int = -1,
            volatility: Int = -1,
            reactivity: Int = -1,
            longForm: Int = -1,
            chatterRung: Int = CHATTER_RUNG_FOLLOW,
        ): BotLlmSpeech = BotLlmSpeech(
            replyTokens = if (replyTokens <= 0) 0
            else replyTokens.coerceIn(MIN_REPLY_TOKENS, MAX_REPLY_TOKENS),
            botToBotChatChance = if (botToBotChatChance < 0) -1
            else botToBotChatChance.coerceIn(0, MAX_BOT_TO_BOT_CHANCE),
            factsCap = if (factsCap <= 0) 0
            else factsCap.coerceIn(MIN_FACTS_CAP, MAX_FACTS_CAP),
            memoriesTail = if (memoriesTail <= 0) 0
            else memoriesTail.coerceIn(MIN_MEMORIES_TAIL, MAX_MEMORIES_TAIL),
            packDeltas = packDeltas.filterKeys { it in PACK_DELTA_IDS },
            initiative = normalizeDial(initiative),
            volatility = normalizeDial(volatility),
            reactivity = normalizeDial(reactivity),
            longForm = normalizeDial(longForm),
            chatterRung = if (chatterRung < 0) CHATTER_RUNG_FOLLOW
            else chatterRung.coerceIn(CHATTER_RUNG_OFF, CHATTER_RUNG_NORMAL),
        )
    }
}
