package com.pocketrealm.server

/**
 * The cloud-lane conf group, one parameter object (the
 * emission surface's detekt law). The native side carries the same
 * defaults, so this emission is
 * self-describing conf (an operator reading the staged aiplayerbot.conf
 * sees the lane's economics) rather than a behavior override - EXCEPT
 * [cloudChatter], the app's Cloud conversation toggle, which masters
 * every cloud widening natively as CloudLaneOpen() = key AND external
 * tier (never the bare key; a device-lane emission can never widen).
 *
 * The toggle ships OFF: cloud conversation is opt-in with its spend
 * disclosure, and `0` keeps external behavior exactly as it is without
 * the lane.
 */
internal data class CloudLaneConf(
    /** AiPlayerbot.LLMCloudChatter - the Cloud conversation toggle. */
    val cloudChatter: Boolean = false,
    /** Unaddressed party replies - 0 matches the native default, so
     * this emission stays self-describing, not an override. */
    val partyReplyEnabled: Int = 0,
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
    /** The dialogue fast-lane arming switch. */
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
        append("\n            AiPlayerbot.LLMPartyReplyEnabled = $partyReplyEnabled")
        append("\n            AiPlayerbot.LLMCloudStreetSayPct = $streetSayPct")
        append("\n            AiPlayerbot.LLMStreetSayPerDay = $streetSayPerDay")
        append("\n            AiPlayerbot.LLMRpgChatPerDay = $rpgChatPerDay")
        append("\n            AiPlayerbot.LLMBotToBotPerDay = $botToBotPerDay")
        append("\n            AiPlayerbot.LLMCloudLineBudgetPerHour = $lineBudgetPerHour")
        append("\n            AiPlayerbot.LLMCloudInteractivePerPlayerHour = $interactivePerPlayerHour")
        append("\n            AiPlayerbot.LLMDialogueFastLane = $dialogueFastLane")
    }
}
