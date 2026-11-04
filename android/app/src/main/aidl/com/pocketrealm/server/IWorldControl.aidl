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
    /** R2-1 (android-core review round 2): allowExistingAccount authorizes
     * the ACCOUNT_EXISTS retry leg (the host threads its attempt
     * bookkeeping). Without it, ACCOUNT_EXISTS is an identity COLLISION:
     * refused with ok=false and NO addon write on the existing row. */
    String createAccountVerifier(String username, String sHex, String vHex, boolean allowExistingAccount);
    String accountList();
    String accountCharacters(String username);
    String banAccount(String username, String duration, String reason);
    String unbanAccount(String username);
    /** Character transfer: `pdump write` one character; answers {ok, dump, output}. */
    String characterExport(String characterName);
    /** Character transfer: `pdump load` a dump into an account (newName empty = keep). */
    String characterImport(String dumpText, String account, String newName);
    /** H2 relay-min smoke rail: inject a player chat line via the real chat opcode handler (channel is say|party|whisper|yell; target is the receiving bot name for whisper). */
    String worldChat(String characterName, String channel, String target, String text);
    String realmStatus();
    String save();
    /** Companion mode: pause/resume world ticking (1/0). */
    String setWorldPaused(int paused);
    /** Companion mode: pause/resume world ticking as one journaled verb (1 = enter, 0 = leave). */
    String setCompanionMode(int enabled);
    String stop();
    String stopOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
    String killForTest();
}
