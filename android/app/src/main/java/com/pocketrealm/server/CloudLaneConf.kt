package com.pocketrealm.server

/**
 * A0.a/A7: the WS-A cloud-lane conf group, one parameter object (the
 * emission surface's detekt law). Values are the plan v2.3 §0.a rows;
 * the native side carries the same defaults, so this emission is
 * self-describing conf (an operator reading the staged aiplayerbot.conf
 * sees the lane's economics) rather than a behavior override - EXCEPT
 * [cloudChatter], the app's Cloud conversation toggle, which masters
 * every cloud widening natively as CloudLaneOpen() = key AND external
 * tier (never the bare key; a device-lane emission can never widen).
 *
 * The toggle ships OFF for the upgrade cohort (every existing external
 * user): cloud conversation is opt-in with its spend disclosure
 * (F3), and `0` restores today's external behavior exactly.
 */
internal data class CloudLaneConf(
    /** AiPlayerbot.LLMCloudChatter - the Cloud conversation toggle. */
    val cloudChatter: Boolean = false,
    /** % of admitted crowd reactions that may speak (rest emote only). */
    val streetSayPct: Int = 25,
    /** Street /say generations per UTC day (realm-global, per-process). */
    val streetSayPerDay: Int = 200,
    /** RPG-lane generations per UTC day (a trigger is 5-11 turns). */
    val rpgChatPerDay: Int = 300,
    /** Bot-to-bot exchange generations per UTC day. */
    val botToBotPerDay: Int = 300,
    /** Ambient cloud lines per hour (realm-global; 0 = non-exempt off). */
    val lineBudgetPerHour: Int = 90,
    /** Interactive replies per real player per hour (tier I). */
    val interactivePerPlayerHour: Int = 240,
    /** The A2 dialogue fast-lane arming switch. */
    val dialogueFastLane: Int = 1,
) {
    /** The appended-block lines (empty when the toggle is off: the native
     * defaults then govern and every widening stays conjunction-keyed). */
    internal fun confLines(): String = buildString {
        if (!cloudChatter) {
            append("\n            AiPlayerbot.LLMCloudChatter = 0")
            return@buildString
        }
        append("\n            AiPlayerbot.LLMCloudChatter = 1")
        append("\n            AiPlayerbot.LLMCloudStreetSayPct = $streetSayPct")
        append("\n            AiPlayerbot.LLMStreetSayPerDay = $streetSayPerDay")
        append("\n            AiPlayerbot.LLMRpgChatPerDay = $rpgChatPerDay")
        append("\n            AiPlayerbot.LLMBotToBotPerDay = $botToBotPerDay")
        append("\n            AiPlayerbot.LLMCloudLineBudgetPerHour = $lineBudgetPerHour")
        append("\n            AiPlayerbot.LLMCloudInteractivePerPlayerHour = $interactivePerPlayerHour")
        append("\n            AiPlayerbot.LLMDialogueFastLane = $dialogueFastLane")
    }
}
