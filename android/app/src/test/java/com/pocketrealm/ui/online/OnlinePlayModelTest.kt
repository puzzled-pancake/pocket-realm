package com.pocketrealm.ui.online

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared multiplayer model's pins: every host status line, every join
 * refusal sentence, the list's sorting and filters, every wizard
 * transition, and the plain-language fence (the wording carries none of
 * the technical vocabulary the index script bans — the word list is read
 * FROM the script so the two can never drift).
 */
class OnlinePlayModelTest {

    // ------------------------------------------------------------ host status

    @Test
    fun everyHostStatusHasItsOwnLine() {
        assertEquals("Starting…", HostStatusCopy.line(HostStatus.Starting))
        assertEquals(
            "Your game is online and listed",
            HostStatusCopy.line(HostStatus.OnlineListed),
        )
        assertEquals(
            "Your game is online — not listed. Share your invite code.",
            HostStatusCopy.line(HostStatus.OnlineUnlisted),
        )
        assertEquals(
            "Your game is online, but the game list could not be reached. Keep trying…",
            HostStatusCopy.line(HostStatus.OnlineNoListRetry),
        )
        assertEquals(
            "Can't go online: no internet connection",
            HostStatusCopy.line(HostStatus.CantGoOnline("no internet connection")),
        )
        assertEquals("Stopped", HostStatusCopy.line(HostStatus.Stopped))
        // Six distinct states, six distinct sentences.
        val lines = listOf(
            HostStatus.Starting, HostStatus.OnlineListed, HostStatus.OnlineUnlisted,
            HostStatus.OnlineNoListRetry, HostStatus.CantGoOnline("x"), HostStatus.Stopped,
        ).map(HostStatusCopy::line)
        assertEquals(lines.size, lines.distinct().size)
    }

    // --------------------------------------------------------- refusal lines

    @Test
    fun everyJoinRefusalCodeHasAPlainSentence() {
        for (code in listOf(
            "PASSWORD_REQUIRED", "PASSWORD_WRONG", "TRY_LATER", "FULL", "BANNED",
            "NAME_TAKEN", "NAME_INVALID", "CAP_REACHED", "SIGNUP_CLOSED", "RATE_LIMITED",
        )) {
            val line = OnlinePlayCopy.refusalLine(code)
            assertNotNull("missing sentence for $code", line)
            assertFalse("the sentence never shows the code: $line", code in line!!)
        }
        assertEquals("That password isn't right.", OnlinePlayCopy.refusalLine("PASSWORD_WRONG"))
        assertNull(OnlinePlayCopy.refusalLine("SOMETHING_ELSE"))
    }

    // ------------------------------------------------------------- game list

    private fun summary(
        id: String,
        name: String,
        players: Int = 5,
        limit: Int? = 100,
        bots: Int = 480,
        needsPassword: Boolean = false,
        sameVersion: Boolean = true,
        joinReady: Boolean = true,
        description: String = "Casual levelling, no rules",
    ) = GameSummary(
        id = id,
        name = name,
        gameVersion = "Wrath of the Lich King 3.3.5a",
        needsVersionLabel = "Wrath of the Lich King",
        patch = "3.3.5",
        playersOnline = players,
        playerLimit = limit,
        bots = bots,
        upSeconds = 3L * 3600 + 12 * 60,
        region = "Europe",
        needsPassword = needsPassword,
        newPlayersWelcome = true,
        description = description,
        seenMinutesAgo = 1,
        hostAppVersion = "v0.111.0",
        joinReady = joinReady,
        sameVersion = sameVersion,
    )

    @Test
    fun aRowRendersThePinnedExample() {
        val row = GameList.row(
            summary("e1", "Dave's Azeroth", players = 12, limit = 100, bots = 480),
        )
        assertEquals("Dave's Azeroth", row.name)
        assertEquals("12 / 100 players", row.playersLine)
        assertEquals("Wrath of the Lich King 3.3.5a · Patch 3.3.5", row.versionLine)
        assertEquals("480 bots · up 3h 12m · Europe · New players welcome", row.factsLine)
        assertEquals("Join", row.joinLabel)
        assertFalse(row.needsPassword)
    }

    @Test
    fun anUnknownLimitBotsAndRegionStillRender() {
        val row = GameList.row(
            summary("e2", "Quiet", players = 3, limit = null, bots = 0).copy(region = null),
        )
        assertEquals("3 players", row.playersLine)
        assertEquals("up 3h 12m · New players welcome", row.factsLine)
    }

    @Test
    fun sortingPutsYourVersionFirstThenTheMostPlayers() {
        val rows = listOf(
            GameList.row(summary("a", "Same version, few", players = 2)),
            GameList.row(summary("b", "Other version, many", players = 99, sameVersion = false)),
            GameList.row(summary("c", "Same version, many", players = 40)),
        )
        val sorted = GameList.sort(rows)
        assertEquals(listOf("c", "a", "b"), sorted.map { it.id })
    }

    @Test
    fun eachFilterWorksAloneAndTogether() {
        val rows = listOf(
            GameList.row(summary("open", "Open", players = 5, limit = 100)),
            GameList.row(summary("locked", "Locked", players = 6, needsPassword = true)),
            GameList.row(summary("full", "Full", players = 100, limit = 100)),
            GameList.row(summary("other", "Other game", players = 7, sameVersion = false)),
        )
        val facts = rows.associate { it.id to summary(it.id, it.name) }.toMutableMap()
        facts["locked"] = summary("locked", "Locked", needsPassword = true)
        facts["full"] = summary("full", "Full", players = 100)
        facts["other"] = summary("other", "Other game", sameVersion = false)

        assertEquals(
            setOf("open", "locked", "full"),
            GameList.filter(rows, facts, "", myVersionOnly = true, hasRoom = false, noPassword = false)
                .map { it.id }.toSet(),
        )
        assertEquals(
            setOf("open", "locked", "other"),
            GameList.filter(rows, facts, "", false, hasRoom = true, noPassword = false)
                .map { it.id }.toSet(),
        )
        assertEquals(
            setOf("open", "full", "other"),
            GameList.filter(rows, facts, "", false, false, noPassword = true)
                .map { it.id }.toSet(),
        )
        assertEquals(
            listOf("open"),
            GameList.filter(rows, facts, "", myVersionOnly = true, hasRoom = true, noPassword = true)
                .map { it.id },
        )
        // The free-text search matches the name or the description.
        assertEquals(
            listOf("other"),
            GameList.filter(rows, facts, "other game", false, false, false).map { it.id },
        )
    }

    @Test
    fun theJoinLabelAnswersVersionAndAvailability() {
        assertEquals(
            "Needs Wrath of the Lich King",
            GameList.row(summary("v", "V", sameVersion = false)).joinLabel,
        )
        assertEquals(
            "Offline right now",
            GameList.row(summary("o", "O", joinReady = false)).joinLabel,
        )
    }

    @Test
    fun uptimeAndSeenRenderThePinnedShapes() {
        assertNull(GameList.uptimeCopy(null))
        assertEquals("up under a minute", GameList.uptimeCopy(30))
        assertEquals("up 45m", GameList.uptimeCopy(45 * 60))
        assertEquals("up 3h 12m", GameList.uptimeCopy(3 * 3600 + 12 * 60))
        assertEquals("up 2d 5h", GameList.uptimeCopy(53 * 3600 + 12 * 60))
        assertEquals("just now", GameList.seenCopy(0))
        assertEquals("2m ago", GameList.seenCopy(2))
        assertEquals("3h ago", GameList.seenCopy(190))
        assertNull("a day-old listing shows no seen copy", GameList.seenCopy(24 * 60 + 5))
    }

    // ---------------------------------------------------------- the wizard

    private fun welcome(signUpAllowed: Boolean = true) = WizardStep.Welcome(
        gameName = "Dave's Azeroth",
        description = "Casual levelling",
        versionLine = "Wrath of the Lich King 3.3.5a · Patch 3.3.5",
        playersLine = "12 / 100 players",
        signUpAllowed = signUpAllowed,
        hasAccountHere = false,
    )

    @Test
    fun aSavedLoginSkipsStraightToConnecting() {
        val step = JoinWizard.firstStep(welcome(), savedLoginName = "Wanderer")
        assertTrue(step is WizardStep.Connecting)
        assertEquals("Wanderer", (step as WizardStep.Connecting).accountName)
        // Without one, the wizard starts at Welcome.
        assertTrue(JoinWizard.firstStep(welcome(), null) is WizardStep.Welcome)
    }

    @Test
    fun welcomeOffersCreateAndLoginWhenSignUpIsAllowedAndOnlyLoginOtherwise() {
        val create = JoinWizard.advance(welcome(), WizardEvent.ChooseCreateAccount)
        assertTrue(create is WizardStep.CreateAccount)
        val login = JoinWizard.advance(welcome(), WizardEvent.ChooseLogIn)
        assertTrue(login is WizardStep.LogIn)
        // Sign-up closed: only Log in exists.
        val closed = JoinWizard.advance(welcome(signUpAllowed = false), WizardEvent.ChooseCreateAccount)
        assertTrue("create is refused when the host closed sign-up", closed is WizardStep.Welcome)
    }

    @Test
    fun accountStepsConnectAndThenSucceedOrFail() {
        val creating = JoinWizard.advance(
            JoinWizard.advance(welcome(), WizardEvent.ChooseCreateAccount),
            WizardEvent.Connecting("Wanderer"),
        )
        assertTrue(creating is WizardStep.Connecting)
        val done = JoinWizard.advance(creating, WizardEvent.Succeeded("Wanderer"))
        assertTrue(done is WizardStep.Done)
        assertEquals(
            "You're in. Log in as WANDERER when the game opens.",
            JoinWizard.doneLine((done as WizardStep.Done).accountName),
        )
        val failed = JoinWizard.advance(creating, WizardEvent.Refused("That password isn't right."))
        assertTrue(failed is WizardStep.Failed)
    }

    @Test
    fun connectingOffersADifferentAccountAndFailedRetriesLogin() {
        val connecting = JoinWizard.advance(
            JoinWizard.advance(welcome(), WizardEvent.ChooseLogIn),
            WizardEvent.Connecting("Wanderer"),
        )
        assertTrue(
            JoinWizard.advance(connecting, WizardEvent.UseDifferentAccount) is WizardStep.LogIn,
        )
        val failed = JoinWizard.advance(connecting, WizardEvent.Refused("full"))
        assertTrue(JoinWizard.advance(failed, WizardEvent.ChooseLogIn) is WizardStep.LogIn)
    }

    @Test
    fun eventsTheStepDoesNotTakeChangeNothing() {
        val welcome = welcome()
        assertEquals(welcome, JoinWizard.advance(welcome, WizardEvent.Succeeded("x")))
        assertEquals(welcome, JoinWizard.advance(welcome, WizardEvent.UseDifferentAccount))
        val connecting = JoinWizard.advance(
            JoinWizard.advance(welcome(), WizardEvent.ChooseLogIn),
            WizardEvent.Connecting("n"),
        )
        assertEquals(connecting, JoinWizard.advance(connecting, WizardEvent.ChooseCreateAccount))
    }

    @Test
    fun theAccountFormRulesMatchTheRealmGrammar() {
        assertNull(JoinWizard.accountInputError("Wanderer", "sesame", "sesame"))
        assertEquals(
            OnlinePlayCopy.NAME_INVALID,
            JoinWizard.accountInputError("way too long name here", "sesame", "sesame"),
        )
        assertEquals(
            OnlinePlayCopy.PASSWORD_INVALID,
            JoinWizard.accountInputError("Wanderer", "has space", "has space"),
        )
        assertEquals(
            OnlinePlayCopy.PASSWORDS_DONT_MATCH,
            JoinWizard.accountInputError("Wanderer", "sesame", "sesamE"),
        )
        assertNull("login needs no repeat", JoinWizard.accountInputError("Wanderer", "sesame", null))
    }

    // ------------------------------------------------- the plain-word fence

    @Test
    fun theCopyCarriesNoBannedWords() {
        val banned = bannedWordsFromTheIndexScript()
        assertTrue(
            "the fence only works with a real word list",
            banned.size >= 25,
        )
        val allCopy = listOf(
            OnlinePlayCopy.TAB_FIND, OnlinePlayCopy.TAB_HOST, OnlinePlayCopy.TAB_DIRECT_JOIN,
            OnlinePlayCopy.TAB_TRANSFER, OnlinePlayCopy.SEARCH_HINT, OnlinePlayCopy.FILTER_MY_VERSION,
            OnlinePlayCopy.FILTER_HAS_ROOM, OnlinePlayCopy.FILTER_NO_PASSWORD,
            OnlinePlayCopy.JOIN_LABEL, OnlinePlayCopy.LIST_EMPTY, OnlinePlayCopy.LIST_UNREACHABLE,
            OnlinePlayCopy.TRY_AGAIN, OnlinePlayCopy.ABOUT_PRIVACY, OnlinePlayCopy.PRIVACY_COPY,
            OnlinePlayCopy.DETAILS_TITLE, OnlinePlayCopy.HOST_GAME_NAME_LABEL,
            OnlinePlayCopy.HOST_GAME_NAME_PLACEHOLDER, OnlinePlayCopy.HOST_DESCRIPTION_LABEL,
            OnlinePlayCopy.HOST_DESCRIPTION_PLACEHOLDER, OnlinePlayCopy.HOST_PASSWORD_LABEL,
            OnlinePlayCopy.HOST_PASSWORD_HELPER, OnlinePlayCopy.HOST_LIST_IN_BROWSER,
            OnlinePlayCopy.HOST_SIGNUP_ALLOWED, OnlinePlayCopy.HOST_ONLINE_BUTTON,
            OnlinePlayCopy.HOST_STOP_BUTTON, OnlinePlayCopy.HOST_PLAYERS_TITLE,
            OnlinePlayCopy.HOST_COPY_INVITE_CODE, OnlinePlayCopy.HOST_SHARE,
            OnlinePlayCopy.HOST_INVITE_CODE_COPIED, OnlinePlayCopy.DIRECT_INVITE_LABEL,
            OnlinePlayCopy.DIRECT_INVITE_PLACEHOLDER, OnlinePlayCopy.DIRECT_LAN_LABEL,
            OnlinePlayCopy.DIRECT_LAN_HINT, OnlinePlayCopy.DIRECT_TEST,
            OnlinePlayCopy.DIRECT_JOIN_BUTTON, OnlinePlayCopy.WIZARD_CREATE_ACCOUNT,
            OnlinePlayCopy.WIZARD_ALREADY_HAVE_ONE, OnlinePlayCopy.WIZARD_LOG_IN,
            OnlinePlayCopy.WIZARD_USE_DIFFERENT_ACCOUNT, OnlinePlayCopy.WIZARD_CANCEL,
            OnlinePlayCopy.WIZARD_BACK, OnlinePlayCopy.WIZARD_ACCOUNT_NAME_LABEL,
            OnlinePlayCopy.WIZARD_PASSWORD_LABEL, OnlinePlayCopy.WIZARD_REPEAT_PASSWORD_LABEL,
            OnlinePlayCopy.WIZARD_CONNECTING, OnlinePlayCopy.WIZARD_WELCOME_TO,
            OnlinePlayCopy.WIZARD_DONE_PREFIX, OnlinePlayCopy.WIZARD_DONE_SUFFIX,
            OnlinePlayCopy.WIZARD_HAS_ACCOUNT_NOTE, OnlinePlayCopy.WIZARD_ACCOUNT_EXPLANATION,
            OnlinePlayCopy.NAME_TAKEN, OnlinePlayCopy.NAME_INVALID, OnlinePlayCopy.PASSWORD_INVALID,
            OnlinePlayCopy.PASSWORDS_DONT_MATCH, OnlinePlayCopy.SIGNUPS_FULL,
            OnlinePlayCopy.SIGNUPS_CLOSED, OnlinePlayCopy.TOO_MANY_ACCOUNTS,
            OnlinePlayCopy.WAIT_BEFORE_RETRY,
        ) + listOf(
            "PASSWORD_REQUIRED", "PASSWORD_WRONG", "TRY_LATER", "FULL", "BANNED",
            "NAME_TAKEN", "NAME_INVALID", "CAP_REACHED", "SIGNUP_CLOSED", "RATE_LIMITED",
        ).mapNotNull(OnlinePlayCopy::refusalLine) + listOf(
            HostStatusCopy.STARTING, HostStatusCopy.ONLINE_LISTED, HostStatusCopy.ONLINE_UNLISTED,
            HostStatusCopy.ONLINE_NO_LIST_RETRY, HostStatusCopy.STOPPED,
            HostStatusCopy.line(HostStatus.CantGoOnline("no internet connection")),
        ) + listOf(
            GameList.JOIN_NEEDS_VERSION_PREFIX + "Wrath",
            GameList.JOIN_OFFLINE_NOW, GameList.NEW_PLAYERS_WELCOME, GameList.ACCOUNTS_BY_INVITE,
        ) + listOfNotNull(GameList.uptimeCopy(3600), GameList.seenCopy(2))
        for (text in allCopy) {
            for (word in banned) {
                assertFalse(
                    "player copy must not say '$word': $text",
                    Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
                )
            }
        }
    }

    @Test
    fun noSentenceRunsPastTwentyFiveWords() {
        val paragraphs = listOf(
            OnlinePlayCopy.LIST_EMPTY, OnlinePlayCopy.LIST_UNREACHABLE, OnlinePlayCopy.PRIVACY_COPY,
            OnlinePlayCopy.WIZARD_ACCOUNT_EXPLANATION, OnlinePlayCopy.DIRECT_LAN_HINT,
        ) + HostStatusCopy.ONLINE_NO_LIST_RETRY
        val sentences = paragraphs.flatMap { p -> p.split(Regex("(?<=[.!?])\\s+")) }
        for (sentence in sentences) {
            val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
            assertTrue("too long (${words.size} words): $sentence", words.size <= 25)
        }
    }

    /** The index script's own list, read from the script — the pin that
     * keeps the test and the gate from drifting apart. */
    private fun bannedWordsFromTheIndexScript(): List<String> {
        val candidates = listOf(
            File("tools/index_online_play.py"),
            File("../tools/index_online_play.py"),
            File("../../tools/index_online_play.py"),
        )
        val script = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("tools/index_online_play.py not found from ${File(".").absolutePath}")
        val text = script.readText(Charsets.UTF_8)
        val listStart = text.indexOf("BANNED_MAIN_WORDS = [")
        assertNotEquals("the word list moved in the script", -1, listStart)
        val listEnd = text.indexOf(']', listStart)
        return Regex("\"([a-z0-9-]+)\"").findAll(text.substring(listStart, listEnd))
            .map { it.groupValues[1] }
            .toList()
    }
}
