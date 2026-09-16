package com.pocketrealm.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned prompt-pack model: the ordered list of prompt blocks that make up
 * a bot's system prompt, player-visible and player-editable.
 *
 * The DEFAULT pack mirrors the trained contract order in
 * `native/patches/playerbots/PlayerbotLlmPrompt.h` (SysmForCard identity →
 * tools-note → bible + no-narrate → backstory → tier → absence → facts;
 * ComposeUserTurn head → memories tail → state → bridge note) with the
 * RP seasoning appended INSIDE the existing instruction span —
 * no new top-level segment names, so trained weights see familiar shape.
 * The default pack ships seasoning DISABLED (native renders byte-identical
 * trained output until the player enables seasoning in Advanced settings);
 * block on/off + reorder only take effect when
 * the player edits the pack in Advanced settings.
 *
 * Token estimates are a chars/4 heuristic (measured ~1.5 tok/word English
 * prose ≈ 3.75 chars/token on the corpus; 4 is the conservative side).
 * The pack editor shows them as estimates; the tools/llm_lab harness
 * records measured llama-tokenizer counts per variant.
 */
data class LlmPromptBlock(
    /** Stable id, used for per-preset deltas and JSON round-trip. */
    val id: String,
    /** Short player-facing title shown in the Advanced prompt manager. */
    val title: String,
    /** The prompt text. Trained-shape blocks carry the contract wording. */
    val body: String,
    /** Whether the block ships enabled. */
    val enabledByDefault: Boolean = true,
    /**
     * Model ids this block applies to, or empty = all tiers.
     * Terse-only blocks target the 0.8B tier; verbose winners target E2B.
     */
    val tierAllowlist: List<String> = emptyList(),
    /** Longer explanation shown under the title in Advanced settings. */
    val help: String = "",
) {
    /** Conservative token estimate for the editor meter (chars/4, min 1). */
    fun estimatedTokens(): Int = (body.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("enabled", enabledByDefault)
        .put("tiers", JSONArray(tierAllowlist))
        .put("help", help)

    companion object {
        const val CHARS_PER_TOKEN = 4

        /** Generous transport cap: the token meter is the soft guide. */
        const val MAX_BODY_CHARS = 2000

        /**
         * Validate an edited body: only what breaks the transport is
         * rejected (control characters other than \n/\t; the pack travels
         * as a JSON file and the native parser handles quotes and
         * backslashes with full string semantics, so they are allowed).
         * No content policing — the weights carry their own safety; the
         * token meter is the soft guide on length.
         */
        fun validateBody(body: String): String? {
            if (body.length > MAX_BODY_CHARS) {
                return "Block is too long (${body.length} chars, max $MAX_BODY_CHARS)"
            }
            return if (hasTransportBreakingChar(body)) {
                "No control characters (line breaks and tabs are fine)"
            } else {
                null
            }
        }

        private fun hasTransportBreakingChar(body: String): Boolean =
            body.any { it.isTransportControl() }

        private fun Char.isTransportControl(): Boolean =
            isISOControl() && this != '\n' && this != '\t'

        fun fromJson(raw: JSONObject): LlmPromptBlock? = runCatching {
            LlmPromptBlock(
                id = raw.getString("id"),
                title = raw.optString("title", raw.getString("id")),
                body = raw.getString("body"),
                enabledByDefault = raw.optBoolean("enabled", true),
                tierAllowlist = buildList {
                    val arr = raw.optJSONArray("tiers")
                    if (arr != null) {
                        for (i in 0 until arr.length()) add(arr.getString(i))
                    }
                },
                help = raw.optString("help", ""),
            ).takeIf { it.id.isNotBlank() }
            // NOTE: no length rejection here, deliberately. Over-length
            // bodies flow into resolve(), where validateBody() lands the
            // documented fallback (default body, player's enabled flag
            // kept) - parse-time rejection would drop the whole block
            // and revert the player's on/off edit with it.
        }.getOrNull()
    }
}

data class LlmPromptPack(
    val version: Int = CURRENT_VERSION,
    val blocks: List<LlmPromptBlock> = defaultBlocks(),
) {
    /** Sum of per-block estimates over enabled blocks only. */
    fun enabledTokenEstimate(): Int =
        blocks.filter { it.enabledByDefault }.sumOf { it.estimatedTokens() }

    /**
     * Token estimate over the SEASONING tail only. The trained blocks are
     * rendered natively from the frozen contract - the copies here are
     * placeholders for the editor preview - so counting them made the meter
     * measure fiction. This is the number the player's edits actually move.
     */
    fun seasoningTokenEstimate(): Int =
        blocks.filter { it.enabledByDefault && it.id in SEASONING_IDS }
            .sumOf { it.estimatedTokens() }

    /**
     * The native renderer joins the enabled seasoning bodies with single
     * spaces inside the identity instruction span and hard-caps the joined
     * text at [NATIVE_SEASONING_MAX_BYTES] bytes (UTF-8, cut at the last
     * newline - by POSITION, not by priority). This estimate reports the
     * joined size so the editor can warn before the silent native cut.
     */
    fun seasoningByteEstimate(): Int =
        blocks.filter { it.enabledByDefault && it.id in SEASONING_IDS }
            .map { it.body }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .toByteArray(Charsets.UTF_8)
            .size

    /**
     * Import honesty: how many edited bodies fell back to the default at
     * resolve (over-length or invalid) - bodies whose default text now
     * differs from the submitted pack. Zero means the import landed whole.
     */
    fun countBodiesRevertedFrom(submitted: LlmPromptPack): Int =
        blocks.count { resolved ->
            val sent = submitted.blocks.firstOrNull { it.id == resolved.id }
            sent != null && sent.body != resolved.body && resolved.body == defaultBodyOf(resolved.id)
        }

    private fun defaultBodyOf(id: String): String? =
        defaultBlocks().firstOrNull { it.id == id }?.body

    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("blocks", JSONArray(blocks.map { it.toJson() }))

    fun serialize(): String = toJson().toString()

    companion object {
        const val CURRENT_VERSION = 1

        /** The native seasoning cap (PlayerbotLlmMemory.cpp LoadPackSeasoning). */
        const val NATIVE_SEASONING_MAX_BYTES = 1200

        fun parse(raw: String): LlmPromptPack? = runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("blocks") ?: return@runCatching null
            val blocks = buildList {
                for (i in 0 until arr.length()) {
                    LlmPromptBlock.fromJson(arr.getJSONObject(i))?.let { add(it) }
                }
            }
            if (blocks.isEmpty()) null else LlmPromptPack(
                version = root.optInt("version", CURRENT_VERSION),
                blocks = blocks,
            )
        }.getOrNull()

        /**
         * Resolve the player's edited pack: parse, else fall back to the
         * default. Fail-open to default (never to silence): a corrupt edit
         * must cost seasoning, not speech. Unknown block ids are dropped;
         * missing trained blocks are restored from the default in trained
         * order; missing seasoning blocks are restored from the default
         * after the player's own seasoning tail (a hand-trimmed pack, or
         * one written before a block existed, never loses blocks forever);
         * seasoning order otherwise follows the player's edit.
         */
        fun resolve(raw: String): LlmPromptPack {
            val parsed = parse(raw) ?: return LlmPromptPack()
            val defaults = defaultBlocks().associateBy { it.id }
            val trainedOrder = trainedBlocks().map { it.id }
            val seen = linkedMapOf<String, LlmPromptBlock>()
            parsed.blocks.forEach { block ->
                val def = defaults[block.id] ?: return@forEach
                // edited bodies must still pass transport validation and
                // stay within the length cap; rejected bodies fall back to
                // the default body with the player's enabled flag
                val body =
                    if (LlmPromptBlock.validateBody(block.body) == null) block.body else def.body
                seen[block.id] = block.copy(body = body)
            }
            seasoningBlocks().forEach { block ->
                if (block.id !in seen) seen[block.id] = block
            }
            val ordered = trainedOrder.map { id -> seen[id] ?: defaults[id]!! } +
                seen.values.filter { it.id !in trainedOrder }
            return LlmPromptPack(version = parsed.version, blocks = ordered)
        }

        /**
         * The default pack. Order mirrors the trained renderer; the
         * `seasoning` blocks (voice-lock, the 4-rule core, ban-list,
         * scene-close, initiative-opener, mood-weather, tool-exemplar,
         * player-persona) ride
         * inside the existing instruction span. Bodies marked TRAINED must
         * stay byte-identical to the native constants — they are rendered
         * natively, never from this text; the copies here feed the editor
         * preview only (the token meter reads the seasoning tail).
         */
        // LongMethod: the default pack is one literal per block by design —
        // splitting it further would scatter the trained-order contract
        // across helpers and make the order unreadable at the call site.
        @Suppress("LongMethod")
        fun defaultBlocks(): List<LlmPromptBlock> = trainedBlocks() + seasoningBlocks()

        /** The trained-contract blocks, in renderer order (all ship enabled). */
        @Suppress("LongMethod")
        private fun trainedBlocks(): List<LlmPromptBlock> = listOf(
            LlmPromptBlock(
                id = "identity",
                title = "Identity",
                body = "You are a roleplaying character in World of Warcraft: Classic. " +
                    "Answer as a roleplaying character. Speak as your character speaks.",
                help = "Who the bot is in the scene. Trained shape — edit wording, " +
                    "not the role it plays.",
            ),
            LlmPromptBlock(
                id = "tools-note",
                title = "Tool protocol note",
                body = "TRAINED: per-card TOOLS_NOTE variant (crc32(card_id)%4), " +
                    "rendered natively. Shown here for the token meter only.",
                help = "The bracketed-note tool contract with trained examples. " +
                    "Rendered natively per card — toggles and edits here are " +
                    "inert (shown for reference).",
            ),
            LlmPromptBlock(
                id = "bible",
                title = "Voice bible (one line)",
                body = "TRAINED: production bible by stable id hash " +
                    "(gruff/wry, loud/boastful, soft/precise, quiet/dry). Rendered natively.",
                help = "The bot's voice register. One of four trained bibles, " +
                    "picked by stable id hash.",
            ),
            LlmPromptBlock(
                id = "no-narrate",
                title = "Words-only rule",
                body = "You speak WORDS only — never narrate your own actions, " +
                    "expressions or what you notice in (parentheses) or *asterisks*. " +
                    "The world shows actions; you say words.",
                help = "Trained shape. Keeps bots talking instead of stage-directing.",
            ),
            LlmPromptBlock(
                id = "backstory",
                title = "Backstory",
                body = "Backstory: <card backstory or name-is-race-class-role-zone default>.",
                help = "Per-bot history line, filled from the card at render time.",
            ),
            LlmPromptBlock(
                id = "relationship",
                title = "Relationship + tier note",
                body = "Relationship with <player>: <Wary..Bonded> (tier <n> of 5). <tier note>.",
                help = "How the bot feels about this player, tier 1–5 with " +
                    "per-tier seasoning.",
            ),
            LlmPromptBlock(
                id = "absence",
                title = "Absence line",
                body = "<player> was last seen <absence bucket> — or 'a few hours ago' by default.",
                help = "Greeting warmth rides this line: long absences soften, " +
                    "fresh faces stay wary.",
            ),
            LlmPromptBlock(
                id = "facts",
                title = "Remembered facts",
                body = "Facts you remember about <player>: <facts '; '-joined, or 'none yet worth the name.'>.",
                help = "Long-term memory, capped per tier (factsCap). The recall " +
                    "that makes a thousand hours personal.",
            ),
            LlmPromptBlock(
                id = "memories-tail",
                title = "Recent memories",
                body = "[Memories] <last 6 tail, '; '-joined>.",
                help = "Short-term tail on the user turn (memoriesTail).",
            ),
            LlmPromptBlock(
                id = "state",
                title = "Scene state",
                body = "[State] <companion flavor or usual spot in zone>.",
                help = "Where the bot is right now: road, camp, forge, usual spot.",
            ),
            LlmPromptBlock(
                id = "bridge-note",
                title = "Bridge note",
                body = "[BRIDGE AI] <tool lines + fills, or reply-only directive>.",
                help = "Per-turn game instruction. Tool-bearing notes license " +
                    "marked lines; without one the bot speaks plainly.",
            ),
        )

        /** The seasoning blocks (all ship disabled — opt-in depth). */
        val SEASONING_IDS: Set<String> = setOf(
            "voice-lock", "rule-autonomy", "rule-anti-omniscient",
            "rule-boldness", "rule-salience", "ban-list", "scene-close",
            "initiative-opener", "mood-weather",
            // the few-shot marked-line exemplar for external endpoints —
            // ships OFF so the trained lane's prompt stays byte-identical
            "tool-exemplar",
            // the player persona card - an empty body renders
            // nothing (silence doctrine), so the trained default stays
            // byte-identical until the player writes their card
            "player-persona",
        )

        /**
         * The trained-contract block ids (mirrors the native renderer's
         * kTrainedIds skip list): their bodies are rendered natively from
         * the frozen constants, so editor toggles and edits on them are
         * inert - the UI presents them read-only.
         */
        val TRAINED_IDS: Set<String> = setOf(
            "identity", "tools-note", "bible", "no-narrate", "backstory",
            "relationship", "absence", "facts", "memories-tail", "state",
            "bridge-note",
        )

        @Suppress("LongMethod")
        private fun seasoningBlocks(): List<LlmPromptBlock> = listOf(
            LlmPromptBlock(
                id = "tool-exemplar",
                title = "Tool exemplar (seasoning, external models)",
                // FIRST in the seasoning order on purpose: the native
                // budget drops whole trailing blocks past 1200 joined
                // bytes, so the last block is the first one silenced
                // when the tail overflows
                body = "When a [BRIDGE AI] note asks for a marked line, the marked line " +
                    "names game truth - never invented detail. Shape of a look-up:\n" +
                    "Player: what do you see around you?\n" +
                    "[BRIDGE AI] You may look at your surroundings. End your reply with " +
                    "exactly this line:\n" +
                    "<<get_scene fields=\"place,time,weather\">>\n" +
                    "Your reply: Frost on the pines and the light going gray. Snow before " +
                    "the hour is out.\n" +
                    "<<get_scene fields=\"place,time,weather\">>\n" +
                    "No note: plain spoken words only - never a marked line.",
                enabledByDefault = false,
                help = "Few-shot exemplar for EXTERNAL endpoints " +
                    "(MiniMax and friends zero-shot the trained marked-line contract " +
                    "unreliably; the trained GGUF lane must keep its prompt byte-identical, " +
                    "so this ships OFF). Marked lines still fire only when the turn's " +
                    "[BRIDGE AI] note licenses them - the block shapes the reply, it " +
                    "cannot license anything. The 1200-byte seasoning budget covers the " +
                    "WHOLE enabled tail joined: if other seasoning blocks are on, this " +
                    "one (or they) must be turned off to fit - the byte meter warns.",
            ),
            LlmPromptBlock(
                id = "voice-lock",
                title = "Voice lock (seasoning)",
                body = "Stay in your bible voice all the way through: the register " +
                    "above is how you sound even when the topic changes. " +
                    "Never open two replies the same way.",
                enabledByDefault = false,
                help = "Anti-flanderization: one voice, fresh openings.",
            ),
            LlmPromptBlock(
                id = "rule-autonomy",
                title = "Rule: no player dialogue (seasoning)",
                body = "Never speak for the player, decide their actions, or " +
                    "narrate what they do — your words are your own only.",
                enabledByDefault = false,
                help = "Anti-puppeteering. Compresses to one clause with no loss.",
            ),
            LlmPromptBlock(
                id = "rule-anti-omniscient",
                title = "Rule: no mind-reading (seasoning)",
                body = "You know only what you were told in this conversation, your " +
                    "remembered facts, and what anyone present could see or hear. " +
                    "Never state the player's motives, history, or off-screen " +
                    "events as fact — when unsure, answer from your own view or " +
                    "say you do not know, in your own words.",
                enabledByDefault = false,
                help = "Anti-omniscience scope. May need the full scope spelled " +
                    "out on small tiers.",
            ),
            LlmPromptBlock(
                id = "rule-boldness",
                title = "Rule: opinions first (seasoning)",
                body = "Have a view and say it plainly in your own voice: needle " +
                    "boasts, answer from things you have built, mended, or drunk. " +
                    "Never simply agree.",
                enabledByDefault = false,
                help = "Anti-yes-man. The Frankenstein rule that changes every line.",
            ),
            LlmPromptBlock(
                id = "rule-salience",
                title = "Rule: one ledger at a time (seasoning)",
                body = "Advance at most one thing per reply — a fact, a feeling, " +
                    "or a question — then stop. Let the player pull the next thread.",
                enabledByDefault = false,
                help = "Salience: dense replies blur on small models; one beat lands.",
            ),
            LlmPromptBlock(
                id = "ban-list",
                title = "Stale-phrase avoid list (seasoning)",
                body = "Stock turns of phrase that crowd the corpus — avoid them, " +
                    "find fresher phrasing: first light; never once; hold still; " +
                    "stand still; eat something; eat first; eat before; drink water; " +
                    "sit drink; still warm; sleep well; second watch; first watch; " +
                    "watch is mine.",
                enabledByDefault = false,
                help = "Trained LEDGER_AVOID wording. Keeps thousand-hour chatter " +
                    "from going stale.",
            ),
            LlmPromptBlock(
                id = "scene-close",
                title = "Scene-close rule (seasoning)",
                body = "End with the scene still open: a small question back, an " +
                    "offer, or something left undone — never a summary, never a moral.",
                enabledByDefault = false,
                help = "Cinematic close. Invites the next player line instead of " +
                    "ending the night.",
            ),
            LlmPromptBlock(
                id = "initiative-opener",
                title = "Initiative opener guide (seasoning)",
                body = "When you speak first, open from something real: a remembered " +
                    "fact, a debt, a goal you were told, or what you can see. " +
                    "One line, in your voice, never a tool-bearing line. " +
                    "Each memory opens a conversation once — then it is spent.",
                enabledByDefault = false,
                help = "Guides bot-initiated openers. The engine " +
                    "unchanged: arrival greetings, debt/goal ask-afters, " +
                    "each fact once, 10-minute floor scaled by the " +
                    "initiative dial.",
            ),
            LlmPromptBlock(
                id = "mood-weather",
                title = "Mood weather (seasoning)",
                body = "Your weather line names how you feel right now; let it " +
                    "color the edges of the reply without flipping who you are. " +
                    "A grudge shows, never announced; grief quiets; being smitten " +
                    "brightens. Volatility sets how fast the weather turns.",
                enabledByDefault = false,
                help = "Mood seasoning companion to the native weather " +
                    "line. The engine picks weather GUID-stably; this block " +
                    "tells the model how to wear it.",
            ),
            LlmPromptBlock(
                id = "player-persona",
                title = "Player persona card (seasoning)",
                body = "",
                enabledByDefault = false,
                help = "Who YOU are, in your own words - habits, " +
                    "look, history, how bots should read you. Empty (default) " +
                    "renders nothing; the trained prompt is untouched until " +
                    "you write your card. Keep it under ~120 tokens - the " +
                    "native renderer cuts the joined seasoning at 1200 bytes " +
                    "by position, so blocks ordered LAST go first; watch the " +
                    "byte meter above.",
            ),
        )
    }
}
