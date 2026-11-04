package com.pocketrealm.ui.online

/**
 * The one shared, UI-free model behind the Multiplayer screens (the find-a-
 * game list, hosting, direct join and the first-join wizard). Both platforms
 * compile this file, so the two apps say exactly the same thing and the
 * wording is unit-tested in one place.
 *
 * Nothing here imports UI toolkits or names the technology underneath: the
 * screens stay in plain language, and anything technical belongs to the
 * Advanced panels or to the wiring outside this package.
 */

// ------------------------------------------------------------- player copy

/** Every sentence the Multiplayer screens show. */
object OnlinePlayCopy {

    // The four tabs (D9/D10).
    const val TAB_FIND: String = "Find a game"
    const val TAB_HOST: String = "Host"
    const val TAB_DIRECT_JOIN: String = "Direct join"
    const val TAB_TRANSFER: String = "Transfer"

    // Find a game -----------------------------------------------------------

    const val SEARCH_HINT: String = "Search games"
    const val FILTER_MY_VERSION: String = "My game version only"
    const val FILTER_HAS_ROOM: String = "Has room"
    const val FILTER_NO_PASSWORD: String = "No password"

    const val JOIN_LABEL: String = "Join"
    const val LIST_EMPTY: String = "No games right now. Host one from the Host tab."
    const val LIST_UNREACHABLE: String =
        "Can't reach the game list. Check your internet connection."
    const val TRY_AGAIN: String = "Try again"
    const val ABOUT_PRIVACY: String = "About privacy"

    /** The privacy dialog, in plain words. */
    const val PRIVACY_COPY: String =
        "Games in the list are run by other players, not by us. While you look for a " +
            "game, the services that carry the list can see this device's internet " +
            "address. The host of a game you join can see it too. When you make an " +
            "account on a game, your password stays on this device. The host only " +
            "receives a one-way code it can check, never the password. The host also " +
            "sees a scrambled device code that names this device on their game alone."

    /** The list's standing connection disclosure, in plain words (the
     * main screens never name the transport technology; the Advanced
     * panes and the host card carry the technical wording). */
    const val LIST_CONNECTION_DISCLOSURE: String =
        "The service that carries the game list can see this device's internet " +
            "address, and a player connected directly to yours may learn it too."

    const val DETAILS_TITLE: String = "About this game"
    const val DETAILS_SEEN: String = "Seen"

    // Host ------------------------------------------------------------------

    const val HOST_GAME_NAME_LABEL: String = "Game name"
    const val HOST_GAME_NAME_PLACEHOLDER: String = "Pocket Realm"
    const val HOST_DESCRIPTION_LABEL: String = "Description"
    const val HOST_DESCRIPTION_PLACEHOLDER: String = "optional"
    const val HOST_PASSWORD_LABEL: String = "Password"
    const val HOST_PASSWORD_HELPER: String = "Leave empty to let anyone join"
    const val HOST_LIST_IN_BROWSER: String = "Show in the game list"
    const val HOST_SIGNUP_ALLOWED: String = "Let new players create accounts"
    const val HOST_ONLINE_BUTTON: String = "Host online"
    const val HOST_STOP_BUTTON: String = "Stop hosting"
    const val HOST_PLAYERS_TITLE: String = "Players"
    const val HOST_COPY_INVITE_CODE: String = "Copy invite code"
    const val HOST_SHARE: String = "Share"
    const val HOST_INVITE_CODE_COPIED: String = "Invite code copied"

    // Direct join -----------------------------------------------------------

    const val DIRECT_INVITE_LABEL: String = "Join with an invite code"
    const val DIRECT_INVITE_PLACEHOLDER: String = "PR1-…"
    const val DIRECT_LAN_LABEL: String = "On your local network"
    const val DIRECT_LAN_HINT: String = "Ask the host for the address shown under Host, Advanced."
    const val DIRECT_TEST: String = "Test"
    const val DIRECT_JOIN_BUTTON: String = "Join"

    // Wizard ----------------------------------------------------------------

    const val WIZARD_CREATE_ACCOUNT: String = "Create an account"
    const val WIZARD_ALREADY_HAVE_ONE: String = "I already have one"
    const val WIZARD_LOG_IN: String = "Log in"
    const val WIZARD_USE_DIFFERENT_ACCOUNT: String = "Use a different account"
    const val WIZARD_CANCEL: String = "Cancel"
    const val WIZARD_BACK: String = "Back"
    const val WIZARD_ACCOUNT_NAME_LABEL: String = "Account name"
    const val WIZARD_PASSWORD_LABEL: String = "Password"
    const val WIZARD_REPEAT_PASSWORD_LABEL: String = "Repeat the password"
    const val WIZARD_CONNECTING: String = "Connecting to"
    const val WIZARD_WELCOME_TO: String = "Welcome to"
    const val WIZARD_DONE_PREFIX: String = "You're in. Log in as"
    const val WIZARD_DONE_SUFFIX: String = "when the game opens."
    const val WIZARD_HAS_ACCOUNT_NOTE: String = "You already have an account on this game."

    /** The account step's explanation, plain. */
    const val WIZARD_ACCOUNT_EXPLANATION: String =
        "Pick an account name and a password. The password stays on this " +
            "device — the host only ever receives a one-way code it can check."

    const val NAME_TAKEN: String = "That name is taken. Try another one."
    const val NAME_INVALID: String = "The account name must be 1-16 letters or digits."
    const val PASSWORD_INVALID: String = "The password must be 1-16 letters or digits."
    const val PASSWORDS_DONT_MATCH: String = "The passwords do not match."
    const val SIGNUPS_FULL: String = "This game has no room for more new accounts."
    const val SIGNUPS_CLOSED: String = "This game is not taking new accounts right now."
    const val TOO_MANY_ACCOUNTS: String = "You already made enough accounts on this game."
    const val WAIT_BEFORE_RETRY: String = "You just made an account here. Wait a few minutes."

    /** Why a join was refused, in one plain sentence. */
    fun refusalLine(code: String): String? = when (code) {
        "PASSWORD_REQUIRED" -> "This game needs a password."
        "PASSWORD_WRONG" -> "That password isn't right."
        "TRY_LATER" -> "Too many tries. Wait 10 minutes, then try again."
        "FULL" -> "This game is full right now."
        "BANNED" -> "The host of this game has turned you away."
        "NAME_TAKEN" -> NAME_TAKEN
        "NAME_INVALID" -> NAME_INVALID
        "CAP_REACHED" -> SIGNUPS_FULL
        "SIGNUP_CLOSED" -> SIGNUPS_CLOSED
        "RATE_LIMITED" -> TOO_MANY_ACCOUNTS
        else -> null
    }
}

// ---------------------------------------------------------- the game list

/** The plain facts of one game in the list (the wiring fills these in). */
data class GameSummary(
    /** The listing's identity — never the name. */
    val id: String,
    val name: String,
    /** "Wrath of the Lich King 3.3.5a" — already rendered. */
    val gameVersion: String,
    /** The version string shown on the Join button for another version. */
    val needsVersionLabel: String,
    val patch: String,
    val playersOnline: Int,
    /** Null = not carried by the listing. */
    val playerLimit: Int?,
    val bots: Int,
    val upSeconds: Long?,
    val region: String?,
    val needsPassword: Boolean,
    val newPlayersWelcome: Boolean,
    val description: String,
    val seenMinutesAgo: Long,
    val hostAppVersion: String?,
    /** False when the host's join connection is down. */
    val joinReady: Boolean,
    /** True when this device runs the same game version. */
    val sameVersion: Boolean,
)

/** One rendered row: the strings the screens show, in order. */
data class GameRow(
    val id: String,
    val name: String,
    val playersLine: String,
    val versionLine: String,
    val factsLine: String,
    val description: String,
    val needsPassword: Boolean,
    val sameVersion: Boolean,
    val joinReady: Boolean,
    val needsVersionLabel: String,
    /** "Join", "Needs <version>" or "Offline right now". */
    val joinLabel: String,
)

/** The list's projection, sorting and filters. */
object GameList {

    const val JOIN_NEEDS_VERSION_PREFIX: String = "Needs "
    const val JOIN_OFFLINE_NOW: String = "Offline right now"
    const val NEW_PLAYERS_WELCOME: String = "New players welcome"
    const val ACCOUNTS_BY_INVITE: String = "Accounts by invitation"

    fun row(summary: GameSummary): GameRow {
        val room = summary.playerLimit
        val playersLine = if (room == null) {
            "${summary.playersOnline} players"
        } else {
            "${summary.playersOnline} / $room players"
        }
        val facts = buildList {
            if (summary.bots > 0) add("${summary.bots} bots")
            uptimeCopy(summary.upSeconds)?.let { add(it) }
            summary.region?.let { add(it) }
            add(if (summary.newPlayersWelcome) NEW_PLAYERS_WELCOME else ACCOUNTS_BY_INVITE)
        }.joinToString(" · ")
        return GameRow(
            id = summary.id,
            name = summary.name,
            playersLine = playersLine,
            versionLine = "${summary.gameVersion} · Patch ${summary.patch}",
            factsLine = facts,
            description = summary.description,
            needsPassword = summary.needsPassword,
            sameVersion = summary.sameVersion,
            joinReady = summary.joinReady,
            needsVersionLabel = summary.needsVersionLabel,
            joinLabel = when {
                !summary.sameVersion -> JOIN_NEEDS_VERSION_PREFIX + summary.needsVersionLabel
                !summary.joinReady -> JOIN_OFFLINE_NOW
                else -> OnlinePlayCopy.JOIN_LABEL
            },
        )
    }

    /** Your version first, then the most players; the name breaks ties. */
    fun sort(rows: List<GameRow>): List<GameRow> = rows.sortedWith(
        compareByDescending<GameRow> { it.sameVersion }
            .thenByDescending { playersCount(it.playersLine) }
            .thenBy { it.name.lowercase() },
    )

    /** The three switches plus the free-text search. */
    fun filter(
        rows: List<GameRow>,
        summaries: Map<String, GameSummary>,
        query: String,
        myVersionOnly: Boolean,
        hasRoom: Boolean,
        noPassword: Boolean,
    ): List<GameRow> = rows.filter { row ->
        val facts = summaries[row.id] ?: return@filter false
        val needle = query.trim().lowercase()
        (needle.isEmpty() || row.name.lowercase().contains(needle) ||
            row.description.lowercase().contains(needle)) &&
            (!myVersionOnly || row.sameVersion) &&
            (!hasRoom || facts.playerLimit == null || facts.playersOnline < facts.playerLimit) &&
            (!noPassword || !row.needsPassword)
    }

    /** "up under a minute", "up 45m", "up 3h 12m", "up 2d 5h". */
    fun uptimeCopy(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val minutes = seconds / SECONDS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val days = hours / HOURS_PER_DAY
        return when {
            minutes == 0L -> "up under a minute"
            hours == 0L -> "up ${minutes}m"
            days == 0L -> "up ${hours}h ${minutes % MINUTES_PER_HOUR}m"
            else -> "up ${days}d ${hours % HOURS_PER_DAY}h"
        }
    }

    /** "seen 2m ago", or null for anything older than a day. */
    fun seenCopy(minutesAgo: Long): String? = when {
        minutesAgo < 1L -> "just now"
        minutesAgo < MINUTES_PER_HOUR -> "${minutesAgo}m ago"
        minutesAgo < MINUTES_PER_DAY -> "${minutesAgo / MINUTES_PER_HOUR}h ago"
        else -> null
    }

    private fun playersCount(playersLine: String): Int =
        playersLine.substringBefore(" /").filter(Char::isDigit).toIntOrNull() ?: 0

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val MINUTES_PER_DAY = 24L * 60L
}

// ------------------------------------------------------------- host status

/** What the Host tab's status line says (all states, unit-tested). */
sealed interface HostStatus {
    data object Starting : HostStatus
    data object OnlineListed : HostStatus
    data object OnlineUnlisted : HostStatus
    data object OnlineNoListRetry : HostStatus
    data class CantGoOnline(val reason: String) : HostStatus
    data object Stopped : HostStatus
}

object HostStatusCopy {
    const val STARTING: String = "Starting…"
    const val ONLINE_LISTED: String = "Your game is online and listed"
    const val ONLINE_UNLISTED: String = "Your game is online — not listed. Share your invite code."
    const val ONLINE_NO_LIST_RETRY: String =
        "Your game is online, but the game list could not be reached. Keep trying…"
    const val STOPPED: String = "Stopped"

    fun line(status: HostStatus): String = when (status) {
        HostStatus.Starting -> STARTING
        HostStatus.OnlineListed -> ONLINE_LISTED
        HostStatus.OnlineUnlisted -> ONLINE_UNLISTED
        HostStatus.OnlineNoListRetry -> ONLINE_NO_LIST_RETRY
        is HostStatus.CantGoOnline -> "Can't go online: ${status.reason}"
        HostStatus.Stopped -> STOPPED
    }
}

// ---------------------------------------------------------- the join wizard

/** The first-join wizard's steps (D8: the one account-creation path). */
sealed interface WizardStep {
    data class Welcome(
        val gameName: String,
        val description: String,
        val versionLine: String,
        val playersLine: String,
        /** False = the host closed sign-up: only Log in shows. */
        val signUpAllowed: Boolean,
        /** True when this device already made an account here. */
        val hasAccountHere: Boolean,
    ) : WizardStep

    data class CreateAccount(val gameName: String) : WizardStep
    data class LogIn(val gameName: String) : WizardStep
    data class Connecting(val gameName: String, val accountName: String?) : WizardStep
    data class Done(val gameName: String, val accountName: String) : WizardStep
    data class Failed(val gameName: String, val reason: String) : WizardStep
}

/** What moves the wizard between steps. */
sealed interface WizardEvent {
    data object ChooseCreateAccount : WizardEvent
    data object ChooseLogIn : WizardEvent
    /** A valid create or login form was submitted — the wiring takes over
     * (the password rides along for the launcher; the state machine only
     * keeps the name). */
    data class Connecting(val accountName: String, val accountPassword: String? = null) : WizardEvent
    data class Succeeded(val accountName: String) : WizardEvent
    data class Refused(val reason: String) : WizardEvent
    data object UseDifferentAccount : WizardEvent
    data object BackToWelcome : WizardEvent
}

object JoinWizard {

    /** The first step: Welcome, or straight to Connecting when a saved
     * login exists for this game. */
    fun firstStep(
        welcome: WizardStep.Welcome,
        savedLoginName: String?,
    ): WizardStep =
        if (savedLoginName != null) WizardStep.Connecting(welcome.gameName, savedLoginName)
        else welcome

    /** The transition table; an event the step does not take changes
     * nothing (returned unchanged). */
    fun advance(step: WizardStep, event: WizardEvent): WizardStep = when (event) {
        WizardEvent.ChooseCreateAccount -> if (step is WizardStep.Welcome && step.signUpAllowed) {
            WizardStep.CreateAccount(step.gameName)
        } else {
            step
        }

        WizardEvent.ChooseLogIn -> when (step) {
            is WizardStep.Welcome, is WizardStep.Failed -> WizardStep.LogIn(gameNameOf(step))
            else -> step
        }

        is WizardEvent.Connecting -> when (step) {
            is WizardStep.CreateAccount, is WizardStep.LogIn ->
                WizardStep.Connecting(gameNameOf(step), event.accountName)

            else -> step
        }

        is WizardEvent.Succeeded -> if (step is WizardStep.Connecting) {
            WizardStep.Done(step.gameName, event.accountName)
        } else {
            step
        }

        is WizardEvent.Refused -> if (step is WizardStep.Connecting) {
            WizardStep.Failed(step.gameName, event.reason)
        } else {
            step
        }

        WizardEvent.UseDifferentAccount -> if (step is WizardStep.Connecting) {
            WizardStep.LogIn(step.gameName)
        } else {
            step
        }

        WizardEvent.BackToWelcome -> if (step is WizardStep.CreateAccount || step is WizardStep.LogIn) {
            WizardStep.Welcome(
                gameName = gameNameOf(step),
                description = "",
                versionLine = "",
                playersLine = "",
                signUpAllowed = true,
                hasAccountHere = false,
            )
        } else {
            step
        }
    }

    /** The account form's one-line refusal, or null when it can submit. */
    fun accountInputError(username: String, password: String, repeat: String?): String? = when {
        !isAccountName(username.trim()) -> OnlinePlayCopy.NAME_INVALID
        !isAccountPassword(password) -> OnlinePlayCopy.PASSWORD_INVALID
        repeat != null && password != repeat -> OnlinePlayCopy.PASSWORDS_DONT_MATCH
        else -> null
    }

    fun isAccountName(name: String): Boolean =
        name.length in 1..16 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    fun isAccountPassword(password: String): Boolean =
        password.length in 1..16 && password.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /** The done line: "You're in. Log in as NAME when the game opens." */
    fun doneLine(accountName: String): String =
        "${OnlinePlayCopy.WIZARD_DONE_PREFIX} ${accountName.uppercase()} " +
            OnlinePlayCopy.WIZARD_DONE_SUFFIX

    private fun gameNameOf(step: WizardStep): String = when (step) {
        is WizardStep.Welcome -> step.gameName
        is WizardStep.CreateAccount -> step.gameName
        is WizardStep.LogIn -> step.gameName
        is WizardStep.Connecting -> step.gameName
        is WizardStep.Done -> step.gameName
        is WizardStep.Failed -> step.gameName
    }
}
