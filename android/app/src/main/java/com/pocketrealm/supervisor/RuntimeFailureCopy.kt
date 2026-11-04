package com.pocketrealm.supervisor

/**
 * Human-readable mapping for raw supervisor failure details.
 *
 * Applied at the UI decode boundary (`RuntimeSupervisorClient.decodeRealmState`)
 * so journal details like `UNVERIFIED_ORPHAN` or a start timeout never reach
 * the user verbatim; the raw detail still goes to logs unchanged.
 *
 * The LAN design’s failure-copy section: the LAN-aware entries — the interface-loss stop reason,
 * the stale LAN address refusal, the join preflight client refusal, the
 * pre-join reachability probe outcomes, and the ban surfacing. Both
 * platforms decode through this one shared file; the raw details stay in
 * the logs verbatim (that is this file's whole contract).
 */
object RuntimeFailureCopy {

    fun humanize(detail: String): String = when {
        "UNVERIFIED_ORPHAN" in detail ->
            "A previous realm start left a world running that this device cannot " +
                "verify as its own, so automatic recovery will not stop it. It must " +
                "be force-stopped before the realm can start again — use Force stop " +
                "realm below."
        "DB_OWNED_BY_DEAD_SESSION" in detail ->
            "The realm database is still locked by an earlier realm session on " +
                "this device that ended without releasing the lock, so retrying " +
                "the start cannot fix it. Use Force stop realm below to release " +
                "the lock; the databases are checked and repaired automatically " +
                "on the next start."
        "TimeoutCancellationException" in detail ->
            "A realm component stopped responding during the last operation. " +
                "Tap Start to recover safely and try again."
        "LAN_HOST_INTERFACE_LOST" in detail ->
            "Your network connection changed, so hosting stopped and the world was " +
                "saved. Pick your current address in the LAN hosting settings and " +
                "start hosting again."
        "TUNNEL_HOST_INTERFACE_LOST" in detail ->
            "Your tunnel connection (Tailscale/ZeroTier) dropped, so hosting stopped " +
                "and the world was saved. Reconnect the tunnel on this device and on " +
                "your friends' devices, then start hosting again — your saved " +
                "addresses did not change."
        // Platform-neutral on purpose: this file is SHARED, and the copy
        // renders on both platforms — Android has no "Settings → LAN play"
        // destination (its LAN settings live on the LAN screen) and the
        // device is a phone, not a PC.
        "LAN host address is not an active private IPv4 interface" in detail ->
            "The LAN address you saved isn't active on this device right now. " +
                "Pick a detected address in the LAN hosting settings."
        "LAN-JOIN-CLIENT-MISSING" in detail ->
            "Joining doesn't need a local realm, but the game client must be " +
                "importable first — open Import."
        "LAN-PROBE-UNREACHABLE" in detail -> {
            val address = Regex("""\d{1,3}(?:\.\d{1,3}){3}""").find(detail)?.value
            "No realm answered at ${address ?: "the host's address"}:3724. Check the " +
                "host's realm is online and both devices are on the same network — " +
                "if the host just enabled hosting, Windows Firewall on the host must " +
                "have allowed Pocket Realm on private networks."
        }
        // v2-gated (design-lan): the join grammar refuses every
        // non-private address at validation time today, so this branch is
        // dormant until the tunnel lane's endpoint widening lands.
        "LAN-PROBE-NOT-LAN" in detail ->
            "This address is not a LAN address. You will be able to log in, but the " +
                "realm list will offer a world you cannot reach unless you are " +
                "inside the host's tunnel."
        "AUTH_LOGON_FAILED_BANNED" in detail -> {
            val name = Regex("""account '([^']+)'""").find(detail)?.groupValues?.getOrNull(1)
            if (name != null) {
                "$name is banned on this realm; they can't log in until you unban them."
            } else {
                "That account is banned on this realm; it can't log in until you " +
                    "unban it (Guests → Unban)."
            }
        }
        // Design-timeframe 's fail-closed world-start refusals: the
        // shared overlay plan's refusal strings, humanized with a remedy.
        // The mechanics stay fail-loud — the raw engineer detail stays in
        // the journal and logs verbatim; the realm card just never renders it.
        // On the non-vanilla lanes the unstaged
        // refusal is the honest "this build stages no overlays for that
        // flavor" state — re-preparing cannot fix it, so the remedy names
        // the real one. The flavor decision routes through the registry
        // (the stage id the PRODUCER embedded, resolved against
        // ClientFlavors) — never a duplicated flavor-set regex, so the
        // registry stays the single owner of the flavor set.
        "timeframe overlay: no overlay staged for" in detail -> {
            val stageId = Regex("no overlay staged for \"([^\"]+)\"")
                .find(detail)?.groupValues?.get(1)
            val stageFlavorId = stageId?.substringBefore("-")
            val vanillaStage = stageFlavorId == null ||
                stageFlavorId == com.pocketrealm.client.ClientFlavors.VANILLA.flavorId
            if (vanillaStage) {
                "The realm's game data is missing the files that carry the selected " +
                    "content stage, so the start was refused. Re-prepare the game files " +
                    "(Settings → Game files) and start the realm again."
            } else {
                "This game version's timeframe content data is not part " +
                    "of this build yet, so the start at the selected stage " +
                    "was refused. Set World timeframe back to the " +
                    "current-release stage (Settings → World timeframe) and " +
                    "start the realm again."
            }
        }
        "timeframe overlay" in detail ->
            "The realm's world database could not be brought to the selected " +
                "content stage, so the start was refused — the database may be " +
                "damaged. Use Recover (or restore a realm-data archive from before " +
                "this start) and try again."
        else -> detail
    }
}
