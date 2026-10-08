package com.pocketrealm.server;

import android.os.IBinder;

/** Fixed app-private control surface for the isolated :world process. */
interface IWorldControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String status();
    String start();
    String startAt(String bindAddress, int nearbyInteractTriggerGuardMs);
    String startNormal();
    String startNormalAt(String bindAddress, int nearbyInteractTriggerGuardMs);
    String startBotProfile(String profileId);
    String startBotProfileAt(String profileId, String bindAddress, int nearbyInteractTriggerGuardMs);
    String setBotTarget(int target);
    String botStatus();
    String createAccount(String username, String password);
    String verifyAccountPassword(String username, String password);
    String setAccountGmLevel(String username, int level);
    String accountStatus(String username);
    String characterPersistence(String username, String characterName);
    /** Remote friend accounts (design-remote-accounts §3/§8/§9/§11): the verifier
     * provisioning op (no password input; the friend derived s/v on their
     * device), the roster read, per-friend characters, and the ban/unban
     * verbs through the SEC_CONSOLE relay. JSON answers follow the
     * characterPersistence pattern. */
    String createAccountVerifier(String username, String sHex, String vHex);
    String accountList();
    String accountCharacters(String username);
    String banAccount(String username, String duration, String reason);
    String unbanAccount(String username);
    /** H2 relay-min smoke rail: inject a player chat line via the real chat opcode handler (channel is say|party|whisper|yell; target is the receiving bot name for whisper). */
    String worldChat(String characterName, String channel, String target, String text);
    /** H2 relay-min smoke rail: clear bot_player_facts/bot_player_relationship for one player (empty = all). */
    String resetState(String player);
    /** H2 relay-min smoke rail: LLM memory state for one player (empty = world summary with online bot names). */
    String llmMemoryState(String player);
    String realmStatus();
    String save();
    /** Companion mode: pause/resume world ticking (1/0). */
    String setWorldPaused(int paused);
    /** Companion mode: pause + full-residency LLM profile (1 = enter, 0 = leave). */
    String setCompanionMode(int enabled);
    String stop();
    String stopOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
    String killForTest();
}
