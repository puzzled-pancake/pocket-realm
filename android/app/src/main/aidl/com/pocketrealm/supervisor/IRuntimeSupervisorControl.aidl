package com.pocketrealm.supervisor;

/** Fixed same-APK control surface; operations are asynchronous and journaled. */
interface IRuntimeSupervisorControl {
    String status();
    String start(String profileId, boolean includeClient);
    String startSpec(String launchSpecJson);
    String stop(boolean forced);
    String relaunchClient();
    String setCompanionMode(boolean enabled);
    String recover();
    String createAccount(String username, String password, int gmLevel);
    /** F9 (census-backend-tools): the read-only world presence sample
     * (state/realPlayers/playerbotsEnabled/onlinePlayers) behind the LAN
     * screen's joined-mode players line — the DesktopAppModel
     * .lanPlayersOnline twin. Observe-only: never starts or claims
     * anything, and a stopped world reports EMPTY without binding :world. */
    String worldPresence();
    /** Remote friend accounts (design-remote-accounts §5/§9/§11): the same
     * seam carries the new verbs. provisionFriendAccount inserts the
     * friend-derived (s, v) with NO password input; the moderation verbs
     * are the shared ban/unban/gm surface; friendAccessState carries the
     * listener state + invite rows; approveFriendRequest runs the
     * verifier-provision approval leg. */
    /** R2-1: allowExistingAccount = the invite's own attempt bookkeeping
     * (the approval is a RETRY whose attempt-1 create succeeded) — the
     * only authorization under which ACCOUNT_EXISTS converges. */
    String provisionFriendAccount(String username, String sHex, String vHex, boolean allowExistingAccount);
    String banFriendAccount(String username, String duration, String reason);
    String unbanFriendAccount(String username);
    /** Character transfer (LAN screen): the world's player-dump verbs. */
    String exportCharacter(String characterName);
    /** Internet join (Realm browser): the in-app iroh gateway's state. */
    String internetJoinStatus();
    /** Internet hosting (Host tab): the realm-browser listing's state. */
    String publicListingStatus();
    /** Self sign-up (Host tab): the signed-up accounts and their removal. */
    String signupAccounts();
    String removeSignupAccount(String username);
    /** Public hosting (Host tab): the players list with ban state and the
     * device bans; one ban verb (BAN_ACCOUNT / UNBAN_ACCOUNT / BAN_DEVICE /
     * UNBAN_DEVICE / BAN_BOTH) on an account name or device token. */
    String publicPlayers();
    String moderatePublicPlayer(String action, String target, String reason);
    String importCharacter(String dumpText, String account, String newName);
    String setFriendGmLevel(String username, int level);
    String friendRoster();
    String friendCharacters(String username);
    String friendAccessState();
    String createFriendInvite(String note);
    String revokeFriendInvite(String inviteId);
    String rejectFriendRequest(String inviteId);
    String approveFriendRequest(String inviteId);
    String createBackup(String name);
    String listBackups();
    String restoreBackup(String snapshotId);
    String backupStatus();
    String forceComponentForTest(String component);
    String killSupervisorForTest();
}
