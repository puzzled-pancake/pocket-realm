package com.pocketrealm.server

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.pocketrealm.R
import com.pocketrealm.bots.BotAdmissionController
import com.pocketrealm.bots.BotAdmissionState
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotResourceSample
import com.pocketrealm.bots.BotResourceSampler
import com.pocketrealm.bots.BotRuntimeMetrics
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ActiveFlavorState
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.TimeframeOverlayApplier
import com.pocketrealm.log.AppLog
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.ComponentOwnership
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject
import com.pocketrealm.BuildConfig

/** Dedicated mangosd fault domain; bots require an explicit measured profile. */
class WorldRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    private lateinit var resourceSampler: BotResourceSampler
    private val admissionMonitorLock = Any()
    private val admissionEpoch = AdmissionMonitorEpoch()
    private val transitionGate = AdmissionTransitionGate()
    @Volatile private var admissionThread: Thread? = null
    @Volatile private var admissionGeneration: Long? = null
    @Volatile private var activeBotProfile: BotProfile? = null
    @Volatile private var admissionState: BotAdmissionState? = null
    @Volatile private var latestMetrics: BotRuntimeMetrics? = null
    @Volatile private var admissionError = ""
    /** R2-2 (android-core review round 2): the lane THIS process's world
     * booted with, pinned from the start leg's own settings snapshot. The
     * account-expansion writes consume THIS lane — a Home switch after
     * world start must never write the new pick's expansion level into the
     * booting lane's world DB (the silently-wrong-expansion class). Null
     * until a start leg runs; the account seams refuse without it. */
    @Volatile private var bootLane: ClientFlavor? = null
    /** Desired native target awaiting acknowledgement; retained across a timeout. */
    @Volatile private var pendingAdmissionTarget: Int? = null
    @Volatile private var activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
    // Supervisor-driven FGS promotion state; guarded by transitionGate.
    @Volatile private var foregroundActive = false
    @Volatile private var stopAccepted = false
    private var normalDataLease: PreparedDataStore.GenerationLease? = null
    override fun onCreate() {
        super.onCreate()
        // Saved bot presets must resolve in the world process too.
        com.pocketrealm.bots.BotCustomPresets.install(java.io.File(filesDir, "bots"))
        // R1-arch-5/R2-2: the overlay plan's lane binding is installed at
        // :world start — the ActiveFlavor holder's selection never crosses
        // the process boundary, so every overlay-plan resolve in this
        // process goes through the persisted Home selection instead (the
        // same Settings-threaded form the expansion lanes below use).
        com.pocketrealm.database.TimeframeOverlayPlan.installLaneResolver {
            worldLaneFlavor("the timeframe overlay plan resolves the qualified lane")
        }
        files = ServerRuntimeFiles(applicationContext)
        resourceSampler = BotResourceSampler(applicationContext, applicationContext.filesDir)
        ownership = ComponentOwnership("world") {
            Thread({
                transitionGate.run {
                    acceptStopLocked()
                    stopAdmissionMonitor()
                    files.writeLifecycle("world", false, "owner-lost")
                    runCatching { WorldNative.stopNative(5_000) }
                    stopSelf()
                    Process.killProcess(Process.myPid())
                }
            }, "world-owner-loss").start()
        }
    }

    /**
     * The supervisor promotes :world to a specialUse FGS while a real
     * player is present and demotes it when the realm is playerless. Every
     * verb (including these intents) is serialized by the
     * [AdmissionTransitionGate]; the promote-then-demote fence makes an
     * in-flight promote intent unable to outlive an already accepted stop.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROMOTE_FOREGROUND -> transitionGate.run { promoteForegroundLocked() }
            ACTION_DEMOTE_FOREGROUND -> transitionGate.run { demoteForegroundLocked() }
        }
        return START_NOT_STICKY
    }

    private fun promoteForegroundLocked() {
        RealmService.ensureChannel(this)
        // Always satisfy the startForegroundService contract first, even
        // when the promotion is about to be undone: skipping startForeground
        // crashes the freshly created service
        // (ForegroundServiceDidNotStartInTimeException).
        startForeground(WORLD_NOTIF_ID, buildForegroundNotification())
        if (stopAccepted) {
            demoteForegroundLocked()
            stopSelf()
        } else {
            foregroundActive = true
        }
    }

    private fun demoteForegroundLocked() {
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
            // Drop the started half too: this service is otherwise bound-only,
            // and a lingering started state would keep it at service priority
            // after the demotion. Any binding keeps the process alive.
            stopSelf()
        }
    }

    /** In-gate stop fence: an accepted stop wins over any in-flight promote. */
    private fun acceptStopLocked() {
        stopAccepted = true
        demoteForegroundLocked()
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, RealmService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pocket Realm world")
            .setContentText("World server active while a player is in the realm")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private val binder = object : IWorldControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { transitionGate.run {
            ownership.decorate(
                ServerStatusJson.world(
                    WorldNative.statusNative(), WorldNative.detailNative(), activeBindAddress)
                    .put("onlinePlayers", WorldNative.onlinePlayersNative())
                    .also(::addBotStatus))
        } }
        override fun start() = startAt(
            RealmEndpoint.LOOPBACK_ADDRESS,
            NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        )
        override fun startAt(bindAddress: String, nearbyInteractTriggerGuardMs: Int) =
            guarded { transitionGate.run {
            stopAccepted = false
            // Design-tunnel §4.4.2: the wildcard (0.0.0.0) is the ONE raw
            // conf value beside the typed grammar — the tunnel host's bind.
            val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            files.prepareWorldLogsForStart(currentNativeState())
            files.writeLifecycle("world", false, "start", bind)
            activeBindAddress = bind
            // Design-timeframe §4.3: conf first (an unprepared install
            // refuses at the conf gate), then the stage overlay, then the
            // native boot - never a world whose conf names a stage the DB
            // never reached. ONE settings snapshot feeds both the conf write
            // and the overlay's desired stage, so a :ui/:supervisor settings
            // write landing between two reads cannot split them.
            val settingsSnapshot = Settings(this@WorldRuntimeService).blockingSnapshot()
            val config = files.worldConfig(
                bind,
                nearbyInteractTriggerGuardMs,
                settingsSnapshot = settingsSnapshot,
            )
            bootLane = worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot)
            // the boot-time lane-vs-runtime gate (BEFORE any native
            // call) — the packaged Android runtime is vanilla-lane only.
            ServerRuntimeFiles.requireAndroidRuntimeLaneMatches(
                checkNotNull(bootLane) { "the world boot lane resolved null" },
            )
            applyStageOverlayOrRefuse(settingsSnapshot)
            val rc = WorldNative.startNative(config.absolutePath)
            if (rc != 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            ServerStatusJson.operation("world", "start", rc)
        } }
        override fun startNormal() = startNormalAt(
            RealmEndpoint.LOOPBACK_ADDRESS,
            NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        )
        override fun startNormalAt(bindAddress: String, nearbyInteractTriggerGuardMs: Int) =
            guarded { transitionGate.run {
            stopAccepted = false
            // Design-tunnel §4.4.2: the wildcard (0.0.0.0) is the ONE raw
            // conf value beside the typed grammar — the tunnel host's bind.
            val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            files.prepareWorldLogsForStart(currentNativeState())
            files.writeLifecycle("world", false, "start-normal", bind)
            activeBindAddress = bind
            // Design-timeframe §4.3: conf, then the stage overlay, then the
            // native boot (inside the lease guard - an overlay failure
            // closes the lease and refuses the start loudly). ONE settings
            // snapshot feeds both the conf write and the overlay.
            val settingsSnapshot = Settings(this@WorldRuntimeService).blockingSnapshot()
            val rc = startWithNormalData {
                val config = files.worldConfigNormal(
                    bind,
                    nearbyInteractTriggerGuardMs,
                    settingsSnapshot = settingsSnapshot,
                )
                bootLane = worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot)
                applyStageOverlayOrRefuse(settingsSnapshot)
                config
            }
            if (rc != 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            ServerStatusJson.operation("world", "start-normal", rc)
        } }
        override fun startBotProfile(profileId: String) =
            startBotProfileAt(
                profileId,
                RealmEndpoint.LOOPBACK_ADDRESS,
                NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
            )
        override fun startBotProfileAt(
            profileId: String,
            bindAddress: String,
            nearbyInteractTriggerGuardMs: Int,
        ) = guarded { transitionGate.run {
            stopAccepted = false
            // Design-tunnel §4.4.2: the wildcard (0.0.0.0) is the ONE raw
            // conf value beside the typed grammar — the tunnel host's bind.
            val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            files.prepareWorldLogsForStart(currentNativeState())
            val profile = BotProfiles.require(profileId)
            files.writeLifecycle("world", false, "start-bot-profile", "${profile.id}@${bind}")
            activeBindAddress = bind
            // Design-timeframe §4.3: conf, then the stage overlay, then the
            // native boot (inside the lease guard - an overlay failure
            // closes the lease and refuses the start loudly). ONE settings
            // snapshot feeds both the conf write and the overlay.
            val settingsSnapshot = Settings(this@WorldRuntimeService).blockingSnapshot()
            val rc = startWithNormalData {
                val config = files.worldConfigBot(
                    profile, bind, nearbyInteractTriggerGuardMs,
                    settingsSnapshot = settingsSnapshot)
                bootLane = worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot)
                applyStageOverlayOrRefuse(settingsSnapshot)
                config
            }
            if (rc != 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            if (rc == 0) startAdmissionMonitor(profile)
            ServerStatusJson.operation("world", "start-bot-profile", rc).put("profileId", profile.id)
        } }
        override fun setBotTarget(target: Int) = guarded { transitionGate.run {
            check(activeBotProfile == null) {
                "bot admission owns the target while a measured profile is active"
            }
            val rc = WorldNative.setBotTargetNative(target)
            ServerStatusJson.operation("world", "set-bot-target", rc).put("requestedTarget", target)
        } }
        override fun botStatus() = guarded { transitionGate.run {
            JSONObject().put("schema", 1).put("ok", true).put("component", "world")
                .also(::addBotStatus)
        } }
        override fun createAccount(username: String, password: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireAccountToken("password", password)
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            // F1-10 (lanes item 12): the fresh row is born with the seed's
            // expansion default (0), and the core gates expansion content
            // per account row — so the ACTIVE flavor's level is written
            // here, after a successful create and before first login
            // (vanilla stays 0; the resolve fails loud on an ambiguous
            // flavor state, exactly like the start lanes' resolve). A
            // refused addon op fails the answer naming its own error,
            // never a silently classic tbc/wotlk account. R1-arch-1: the
            // write also runs on the designed HOST RETRY leg. The
            // retry-vs-collision discriminator is
            // the PASSWORD — the retry re-submits the exact credential
            // attempt 1 created the row with (the desktop ACCOUNT_VERIFIED
            // precedent); a mismatched password on ACCOUNT_EXISTS is a
            // collision and is refused without any addon write.
            val accountExists = accountExists(username)
            val identityProven = if (rc == ServerRuntimeContract.ACCOUNT_EXISTS_CODE && accountExists) {
                WorldNative.verifyAccountPasswordNative(username, password)
            } else {
                true
            }
            // the BOOT lane — the world DB being written is the one
            // this process booted; a Home switch after start must never
            // re-lane the expansion write.
            val expansion = (bootLane
                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion
            val addonRc = if (ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven) {
                WorldNative.setAccountAddonNative(
                    username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            } else {
                null
            }
            val accepted = if (ServerRuntimeContract.shouldWriteExpansion(rc, accountExists)) {
                addonRc == 0 && identityProven
            } else {
                rc == 0
            }
            val answerCode =
                if (!identityProven) "ACCOUNT_PASSWORD_MISMATCH"
                else ServerRuntimeContract.errorName(addonRc?.toLong() ?: rc.toLong())
            accountResult("create-account", username, addonRc ?: rc)
                .put("ok", accepted)
                .put("code", answerCode)
                .put("expansion", expansion)
                .put("addonCode", addonRc?.let { ServerRuntimeContract.errorName(it.toLong()) } ?: "SKIPPED")
        }
        override fun verifyAccountPassword(username: String, password: String) = guarded { transitionGate.run {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireAccountToken("password", password)
            val verified = WorldNative.verifyAccountPasswordNative(username, password)
            ownership.decorate(
                accountResult("verify-account-password", username, 0)
                    .put("passwordVerified", verified),
            )
        } }
        override fun setAccountGmLevel(username: String, level: Int) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            require(level in 0..3) { "GM level must be in 0..3" }
            val rc = WorldNative.setAccountGmLevelNative(
                username, level, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            accountResult("set-account-gmlevel", username, rc)
        }
        override fun accountStatus(username: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            accountResult("account-status", username, 0)
        }
        override fun createAccountVerifier(
            username: String,
            sHex: String,
            vHex: String,
            allowExistingAccount: Boolean,
        ) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            require(FriendProvisionProtocol.isSrpHex(sHex) && FriendProvisionProtocol.isSrpHex(vHex)) {
                "verifier must be 64 lowercase hex chars"
            }
            val verdict = WorldNative.createAccountVerifierNative(username, sHex, vHex)
            val rc = verdict.getOrElse(0) { Long.MAX_VALUE }.toInt()
            // R1-1 (android-core review round 1, serious): the verifier path
            // is provisioning's SECOND account-creation seam and was the only
            // one that never wrote the per-flavor expansion - the native
            // INSERT births the row at the seed's expansion default (0), so
            // at the W4.4 flip every provisioned friend account on a
            // tbc/wotlk host was silently born classic (the core then
            // refuses expansion races/classes and maps for the account).
            // Mirror createAccount's leg: the ACTIVE flavor's level is
            // written after a successful create and before first login; a
            // refused addon op fails the answer naming its own error, never
            // a silently classic tbc/wotlk friend. R1-arch-1: the write also
            // runs on the designed HOST RETRY leg (ACCOUNT_EXISTS with the
            // row present — attempt 1 created it, its addon write failed,
            // approvalFailed returned the row to SUBMITTED) — skipping here
            // re-opens the hole on exactly the lane the retry design serves.
            val accountExists = accountExists(username)
            // the BOOT lane — the world DB being written is the one
            // this process booted; a Home switch after start must never
            // re-lane the expansion write.
            val expansion = (bootLane
                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion
            // R2-1 (android-core review round 2, serious): the (rc, exists)
            // decision alone cannot distinguish the designed HOST RETRY (the
            // row is OUR attempt-1 create; the host threads its attempt
            // bookkeeping as allowExistingAccount) from an IDENTITY COLLISION
            // (the username was already on the realm before any attempt — the
            // submitted s/v never proven against it). Without the host's
            // authorization, ACCOUNT_EXISTS refuses with ok=false and NO
            // addon write on the existing row; the approval leg then rejects
            // the invite (never PROVISIONED, never a success reveal).
            // R3-1 (android-core review round 3, serious): the decision is
            // CONJUNCTIVE on the authorization — the expression OR-ed
            // the gated leg with shouldWriteExpansion, whose rc==10 leg is
            // authorization-blind, so the gate was a no-op and every
            // collision still wrote the existing row and answered ok=true.
            val writeExpansion = ServerRuntimeContract.verifierExpansionWrite(
                rc, accountExists, allowExistingAccount,
            )
            val addonRc = if (writeExpansion) {
                WorldNative.setAccountAddonNative(
                    username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            } else {
                null
            }
            // An accepted answer requires the addon write when the decision
            // demanded one (addonRc == 0); an unauthorized ACCOUNT_EXISTS is
            // a rejection even though accountResult's shape would accept it.
            val accepted = writeExpansion && addonRc == 0
            accountResult("create-account-verifier", username, addonRc ?: rc)
                .put("ok", accepted)
                .put("accountId", verdict.getOrElse(1) { 0L })
                .put("expansion", expansion)
                .put("addonCode", addonRc?.let { ServerRuntimeContract.errorName(it.toLong()) } ?: "SKIPPED")
        }
        override fun accountList() = guarded {
            JSONObject(WorldNative.accountListNative())
                .put("schema", 1).put("component", "world").put("operation", "account-list")
        }
        override fun accountCharacters(username: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            JSONObject(WorldNative.accountCharactersNative(username))
                .put("schema", 1).put("component", "world").put("operation", "account-characters")
        }
        override fun banAccount(username: String, duration: String, reason: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            JSONObject(WorldNative.banAccountNative(username, duration, reason, ServerRuntimeContract.CONTROL_TIMEOUT_MS))
                .put("schema", 1).put("component", "world").put("operation", "ban-account")
        }
        override fun unbanAccount(username: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            JSONObject(WorldNative.unbanAccountNative(username, ServerRuntimeContract.CONTROL_TIMEOUT_MS))
                .put("schema", 1).put("component", "world").put("operation", "unban-account")
        }
        override fun characterPersistence(username: String, characterName: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireCharacterName(characterName)
            JSONObject(WorldNative.characterPersistenceNative(username, characterName))
                .put("schema", 1).put("ok", true).put("component", "world")
        }
        // Test-support smoke rail: the native side owns validation and
        // answers with its own ok verdict (character names/channel/text are
        // op-specific shapes, not the account/character contract tokens).
        override fun worldChat(characterName: String, channel: String, target: String, text: String) =
            guarded { transitionGate.run {
                JSONObject(WorldNative.worldChatNative(
                    characterName, channel, target, text, ServerRuntimeContract.CONTROL_TIMEOUT_MS))
                    .put("schema", 1).put("component", "world")
            } }
        override fun resetState(player: String) = guarded { transitionGate.run {
            JSONObject(WorldNative.resetStateNative(player))
                .put("schema", 1).put("component", "world")
        } }
        override fun llmMemoryState(player: String) = guarded { transitionGate.run {
            JSONObject(WorldNative.llmMemoryStateNative(player))
                .put("schema", 1).put("component", "world")
        } }
        override fun realmStatus() = guarded {
            JSONObject(WorldNative.realmInfoNative())
                .put("schema", 1).put("ok", true).put("component", "world")
        }
        override fun save() = guarded { transitionGate.run {
            val saveStartedAt = SystemClock.elapsedRealtime()
            val rc = WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            val saveAckMs = SystemClock.elapsedRealtime() - saveStartedAt
            android.util.Log.i("PocketWorld", "saveall-ack code=${ServerRuntimeContract.errorName(rc.toLong())} durationMs=$saveAckMs")
            files.writeLifecycle("world", false, "save", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world", "save", rc)
        } }
        override fun setWorldPaused(paused: Int) = guarded { transitionGate.run {
            val rc = WorldNative.pauseWorldNative(if (paused != 0) 1 else 0)
            files.writeLifecycle("world", false,
                if (paused != 0) "pause" else "resume",
                ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world", if (paused != 0) "pause" else "resume", rc)
        } }
        override fun setCompanionMode(enabled: Int) = guarded { transitionGate.run {
            // world pause + the full-residency LLM profile in one verb; the
            // coexistence profile restores when leaving companion mode
            val rc = WorldNative.setCompanionModeNative(if (enabled != 0) 1 else 0)
            files.writeLifecycle("world", false,
                if (enabled != 0) "companion-enter" else "companion-exit",
                ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world",
                if (enabled != 0) "companion-enter" else "companion-exit", rc)
        } }
        override fun stop() = guarded { transitionGate.run {
            acceptStopLocked()
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) {
                activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
                releaseNormalDataLease()
                retireCleanProcess()
            }
            ServerStatusJson.operation("world", "stop", rc)
        } }
        override fun stopOwned(instanceToken: String) = guarded { transitionGate.run {
            ownership.requireOwner(instanceToken)
            acceptStopLocked()
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) {
                activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
                releaseNormalDataLease()
                ownership.clear(instanceToken)
                retireCleanProcess()
            }
            ServerStatusJson.operation("world", "stop", rc)
        } }
        override fun forceStopOwned(instanceToken: String): String {
            return transitionGate.run {
                ownership.requireOwner(instanceToken)
                acceptStopLocked()
                stopAdmissionMonitor()
                files.writeLifecycle("world", false, "forced-stop")
                Process.killProcess(Process.myPid())
                ""
            }
        }
        override fun killForTest(): String {
            check(BuildConfig.DEBUG) { "test process kill is debug-only" }
            return transitionGate.run {
                acceptStopLocked()
                stopAdmissionMonitor()
                files.writeLifecycle("world", false, "kill-for-test")
                Process.killProcess(Process.myPid())
                ""
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private fun currentNativeState(): Long {
        val status = WorldNative.statusNative()
        check(status.size == 8 && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "world native status contract mismatch"
        }
        return status[1]
    }

    override fun onDestroy() {
        transitionGate.run {
            stopAdmissionMonitor()
            val stopped = runCatching { WorldNative.stopNative(5_000) }.getOrNull() == 0
            if (stopped) releaseNormalDataLease()
        }
        super.onDestroy()
    }
    /**
     * CMaNGOS owns process-lifetime singleton registries and queue threads.
     * Once native shutdown has acknowledged and the clean journal is durable,
     * retire this dedicated fault domain instead of reusing stale globals.
     * The delay lets the successful Binder reply reach the supervisor first.
     */
    private fun retireCleanProcess() {
        Thread({
            Thread.sleep(250)
            stopSelf()
            Process.killProcess(Process.myPid())
        }, "world-clean-retire").start()
    }

    private fun startWithNormalData(config: () -> java.io.File): Int {
        check(normalDataLease == null) { "normal-play data is already leased by this world" }
        val lease = files.acquireNormalDataLease()
        return try {
            val rc = WorldNative.startNative(config().absolutePath)
            if (rc == 0) normalDataLease = lease else lease.close()
            rc
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    /**
     * Design-timeframe §4.3 (the Android execution leg): bring the world DB
     * from its recorded stage to the stage the conf names — the shared
     * decision plan executed through TimeframeOverlayApplier. The desktop
     * lane runs the identical plan through its SQLite seam; any failure
     * here refuses the world start loudly (the transition gate turns the
     * throw into the honest failure JSON, and the lifecycle record names
     * the overlay).
     */
    @Suppress("TooGenericExceptionCaught") // the loud-refusal contract (the desktop startWorld twin)
    private fun applyStageOverlayOrRefuse(settingsSnapshot: Settings.Snapshot) {
        // The CALLER's snapshot — the same one the conf write consumed — so
        // the conf's PocketRealm.TimeframeStage and the overlay's desired
        // stage can never disagree (one stage resolution per start).
        // R2-1/R4-2 (android-core review round 2): the desired stage
        // resolves through the SAME persisted-selection machine as the conf
        // write (worldLaneFlavor) — the old bare `TimeframeStages.confValue`
        // resolved through the :ui-local holder, which refuses at a
        // multi-qualified flip even with a valid persisted pick and blamed
        // the overlay for a lane-resolution failure.
        val desiredStage = TimeframeStages.confValueFor(
            settingsSnapshot.timeframeStage,
            worldLaneFlavor("the overlay's desired stage resolves the qualified lane").flavorId,
        )
        try {
            TimeframeOverlayApplier.prepareForStart(
                java.io.File(
                    StorageRoots.get(this).databaseRoot,
                    DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME,
                ),
                desiredStage = desiredStage,
            )
        } catch (overlayFailure: Throwable) {
            files.writeLifecycle(
                "world",
                false,
                "start-failed-overlay",
                overlayFailure.message ?: "overlay failure",
            )
            error("world start refused: timeframe overlay failed: ${overlayFailure.message}")
        }
    }

    private fun releaseNormalDataLease() {
        normalDataLease?.close()
        normalDataLease = null
    }
    private inline fun guarded(block: () -> JSONObject): String = try { block().toString() } catch (error: Throwable) {
        AppLog.e(TAG, "world control request failed", error); failure(error)
    }
    private fun failure(error: Throwable) = JSONObject().put("schema", 1).put("ok", false)
        .put("component", "world").put("errorClass", error.javaClass.simpleName)
        .put("error", (error.message ?: "world request failed").take(512)).toString()
    private fun accountResult(operation: String, username: String, rc: Int): JSONObject {
        val info = WorldNative.accountInfoNative(username)
        val exists = info.size >= 2 && info[0] > 0
        val accepted = rc == 0 || (rc == 10 && exists)
        return JSONObject().put("schema", 1).put("ok", accepted)
            .put("component", "world").put("operation", operation)
            .put("code", ServerRuntimeContract.errorName(rc.toLong()))
            .put("accountExists", exists).put("accountId", info.getOrElse(0) { 0 })
            .put("gmLevel", info.getOrElse(1) { -1 })
    }

    /** The accountInfoNative presence check the expansion-write decision
     * consumes (R1-arch-1: ACCOUNT_EXISTS retry legs must prove the row is
     * really there before writing the addon — the same shape accountResult
     * uses for its accepted verdict). */
    private fun accountExists(username: String): Boolean =
        WorldNative.accountInfoNative(username).let { it.size >= 2 && it[0] > 0 }

    /** R1-arch-5/R2-2 (android-core review round 1): the flavor resolver
     * for this process's per-flavor gates. The [ActiveFlavor] holder's
     * selection is :ui-process-local in-memory state (the Home picker) that
     * never crosses the process boundary — this service runs in :world
     * (AndroidManifest android:process=":world"), where the holder's own
     * resolve would refuse at a multi-qualified flip even with a valid
     * persisted pick. Resolve through the PERSISTED Home selection instead,
     * threaded as a value exactly like the :supervisor twins
     * (AndroidRuntimeBackend REALMD_BUILDS_LANE / launchLaneFlavor): sole
     * qualified degrades to it, the persisted pick is honored, and an
     * ambiguous post-flip state refuses loud — never a silent downgrade. */
    private fun worldLaneFlavor(
        lane: String,
        settingsSnapshot: Settings.Snapshot? = null,
    ): ClientFlavor =
        ActiveFlavorState(
            settingsSnapshot?.selectedFlavorOrNull()
                ?: runBlocking {
                    Settings(applicationContext).flow.first().selectedFlavorOrNull()
                },
        ).resolve(ActiveFlavor.qualifiedFlavors, lane)
    private fun addBotStatus(value: JSONObject) {
        val bot = WorldNative.botStatusNative()
        check(bot.size == BOT_STATUS_SIZE && bot[0] == ServerRuntimeContract.ABI_VERSION)
        val profile = activeBotProfile
        val cachedMetrics = latestMetrics
        val performance = if (profile == null) WorldNative.performanceStatusNative() else null
        if (performance != null) {
            check(performance.size == PERFORMANCE_STATUS_SIZE &&
                performance[0] == ServerRuntimeContract.ABI_VERSION)
        }
        value.put("compiledPlayerbots", bot[1] != 0L)
            .put("playerbotsEnabled", bot[2] != 0L)
            .put("botsAvailable", bot[3])
            .put("botsOnline", bot[4])
            .put("effectiveBotTarget", bot[5])
            .put("botAccountCount", bot[6])
            .put("botTelemetrySampleUnixSeconds", bot[7])
            .put("activeBots", bot[8])
            .put("realPlayers", bot[9])
            .put("botsSameActiveZone", bot[10])
            .put("botsWithin150", bot[11])
            .put("botsWithin500", bot[12])
            .put("botsWithin1500", bot[13])
            .put("botsLevelDelta2", bot[14])
            .put("botsLevelDelta4", bot[15])
            .put("botLoginsLast60s", bot[16])
            .put("botTeleportsLast60s", bot[17])
            .put("botRerandomizesLast60s", bot[18])
            .put("auctionHouseBot", false)
        profile?.let {
            value.put("botProfileId", profile.id).put("selectedBotTarget", profile.selectedTarget)
                .put("botGenerationState", when {
                    bot[2] == 0L -> "generating"
                    bot[3] >= profile.selectedTarget -> "complete"
                    else -> "incomplete"
                })
        }
        admissionState?.let { state ->
            value.put("botTargetAdapted", state.adapted)
                .put("botAdmissionReason", state.reason)
        }

        if (cachedMetrics != null) {
            putPerformanceStatus(value, cachedMetrics)
        } else if (performance != null) {
            value.put("tickWindowSamples", performance[1])
                .put("worldTickP50Ms", performance[2])
                .put("worldTickP95Ms", performance[3])
                .put("worldTickP99Ms", performance[4])
                .put("worldTickWindowMaxMs", performance[5])
                .put("worldHardStalls", performance[6])
                .put("worldHardStallsInWindow", performance[6])
                .put("worldHardStallTotal", performance[7])
                .put("worldLastHardStallElapsedMs", performance[8])
                .put("dbProbeDelayMs", performance[9])
        } else {
            value.put("tickWindowSamples", 0)
                .put("worldTickP50Ms", 0)
                .put("worldTickP95Ms", 0)
                .put("worldTickP99Ms", 0)
                .put("worldTickWindowMaxMs", 0)
                .put("worldHardStalls", 0)
                .put("worldHardStallsInWindow", 0)
                .put("worldHardStallTotal", 0)
                .put("worldLastHardStallElapsedMs", 0)
                .put("dbProbeDelayMs", 0)
        }

        val statusMetrics = cachedMetrics ?: if (profile == null && bot[2] != 0L && performance != null) {
            runCatching { resourceSampler.read(performance) }.getOrNull()
        } else null
        if (statusMetrics != null) {
            val metrics = statusMetrics
            value.put("worldPssMiB", metrics.worldPssMiB)
                .put("freeMemoryMiB", metrics.freeMemoryMiB)
                .put("freeStorageMiB", metrics.freeStorageMiB)
                .put("thermalLevel", metrics.thermal.name.lowercase())
                .put("resourceSampleElapsedMs", metrics.sampledAtElapsedMs)
                .put("resourceSampleAgeMs",
                    (SystemClock.elapsedRealtime() - metrics.sampledAtElapsedMs).coerceAtLeast(0))
                .put("resourceSampleState", "ready")
        } else if (profile != null) {
            value.put("worldPssMiB", 0)
                .put("freeMemoryMiB", 0)
                .put("freeStorageMiB", 0)
                .put("thermalLevel", "unknown")
                .put("resourceSampleElapsedMs", 0)
                .put("resourceSampleAgeMs", 0)
                .put("resourceSampleState", "pending")
        }
        if (admissionError.isNotEmpty()) value.put("botAdmissionError", admissionError.take(256))
        pendingAdmissionTarget?.let { value.put("botAdmissionPendingTarget", it) }
    }

    private fun putPerformanceStatus(value: JSONObject, metrics: BotRuntimeMetrics) {
        value.put("tickWindowSamples", metrics.tickSamples)
            .put("worldTickP50Ms", metrics.worldP50Ms)
            .put("worldTickP95Ms", metrics.worldP95Ms)
            .put("worldTickP99Ms", metrics.worldP99Ms)
            .put("worldTickWindowMaxMs", metrics.worldMaxMs)
            .put("worldHardStalls", metrics.hardStallCount)
            .put("worldHardStallsInWindow", metrics.hardStallCount)
            .put("worldHardStallTotal", metrics.hardStallTotal)
            .put("worldLastHardStallElapsedMs", metrics.lastHardStallElapsedMs)
            .put("dbProbeDelayMs", metrics.dbProbeDelayMs)
    }

    private fun startAdmissionMonitor(profile: BotProfile) {
        synchronized(admissionMonitorLock) {
            check(admissionThread == null && admissionGeneration == null) {
                "bot admission monitor is already registered"
            }
        }
        val generation = admissionEpoch.begin()
        val beginRc = WorldNative.beginAdmissionBotTargetGenerationNative(generation)
        if (beginRc != 0) {
            admissionEpoch.invalidate()
            error("native bot-target generation registration failed: " +
                ServerRuntimeContract.errorName(beginRc.toLong()))
        }
        val controller = BotAdmissionController(profile)
        val startedAt = SystemClock.elapsedRealtime()
        val thread = Thread({
            runAdmissionMonitor(controller, startedAt, generation)
        }, "bot-admission")
        synchronized(admissionMonitorLock) {
            activeBotProfile = profile
            admissionState = null
            latestMetrics = null
            admissionError = ""
            pendingAdmissionTarget = null
            admissionGeneration = generation
            admissionThread = thread
        }
        try {
            thread.start()
        } catch (failure: Throwable) {
            admissionEpoch.invalidate {
                WorldNative.retireAdmissionBotTargetGenerationNative(generation)
            }
            synchronized(admissionMonitorLock) {
                if (admissionThread === thread) {
                    admissionThread = null
                    admissionGeneration = null
                    activeBotProfile = null
                }
            }
            throw failure
        }
    }

    private fun runAdmissionMonitor(
        controller: BotAdmissionController,
        startedAt: Long,
        generation: Long,
    ) {
        while (admissionEpoch.isCurrent(generation)) {
            try {
                if (!admissionEpoch.isCurrent(generation)) break
                // a paused world (companion mode) reports frozen tick metrics;
                // withholding adaptation avoids the monitor misreading a
                // deliberate pause as a hard stall
                if (WorldNative.isWorldPausedNative() != 0) {
                    Thread.sleep(ADMISSION_INTERVAL_MS)
                    continue
                }
                val bot = WorldNative.botStatusNative()
                if (!admissionEpoch.isCurrent(generation)) break
                if (bot.size == BOT_STATUS_SIZE && bot[2] != 0L) {
                    // A timed-out native request stays queued only while this
                    // exact generation remains active. Retry it before policy
                    // advances so reported and native targets cannot diverge.
                    val pendingTarget = pendingAdmissionTarget
                    if (pendingTarget != null) {
                        val rc = admissionEpoch.runIfCurrent(generation) {
                            WorldNative.setAdmissionBotTargetNative(pendingTarget, generation)
                        } ?: break
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target $pendingTarget: " +
                                    ServerRuntimeContract.errorName(rc.toLong())
                            }
                        }) break
                    }
                    if (pendingAdmissionTarget != null) {
                        Thread.sleep(ADMISSION_INTERVAL_MS)
                        continue
                    }
                    if (!admissionEpoch.isCurrent(generation)) break
                    val performance = WorldNative.performanceStatusNative()
                    if (!admissionEpoch.isCurrent(generation)) break
                    val metrics = resourceSampler.read(performance)
                    if (!admissionEpoch.publishIfCurrent(generation) {
                        latestMetrics = metrics
                    }) break
                    val state = controller.observe(BotResourceSample(
                        elapsedMs = metrics.sampledAtElapsedMs - startedAt,
                        onlineBots = bot[4].toInt(),
                        worldP99Ms = metrics.worldP99Ms,
                        freeMemoryMiB = metrics.freeMemoryMiB,
                        freeStorageMiB = metrics.freeStorageMiB,
                        thermal = metrics.thermal,
                        hardStallCount = metrics.hardStallCount,
                        hardStallTotal = metrics.hardStallTotal,
                    ))
                    if (!admissionEpoch.publishIfCurrent(generation) {
                        admissionState = state
                    }) break
                    if (state.changed) {
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            pendingAdmissionTarget = state.effectiveTarget
                        }) break
                        val rc = admissionEpoch.runIfCurrent(generation) {
                            WorldNative.setAdmissionBotTargetNative(state.effectiveTarget, generation)
                        } ?: break
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target ${state.effectiveTarget}: " +
                                    ServerRuntimeContract.errorName(rc.toLong())
                            }
                        }) break
                    }
                }
                Thread.sleep(ADMISSION_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (error: Throwable) {
                if (!admissionEpoch.publishIfCurrent(generation) {
                    admissionError = (error.message ?: error.javaClass.simpleName).take(256)
                    AppLog.e(TAG, "bot admission sample failed", error)
                }) break
                try { Thread.sleep(ADMISSION_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopAdmissionMonitor(): Boolean {
        val (thread, generation) = synchronized(admissionMonitorLock) {
            admissionThread to admissionGeneration
        }
        var retireRc = 0
        /* Invalidation shares the epoch gate with setters, and native retire
         * shares the target fence with the world-tick consumer. Once this
         * returns, no queued or executing retired target can reach a new run. */
        admissionEpoch.invalidate {
            if (generation != null) {
                retireRc = WorldNative.retireAdmissionBotTargetGenerationNative(generation)
            }
        }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(ADMISSION_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val stopped = thread?.isAlive != true && retireRc == 0
        synchronized(admissionMonitorLock) {
            if (stopped && admissionThread === thread) {
                admissionThread = null
                admissionGeneration = null
                activeBotProfile = null
                admissionState = null
                latestMetrics = null
                admissionError = ""
                pendingAdmissionTarget = null
            } else if (!stopped) {
                admissionError = if (retireRc != 0) {
                    "native admission generation retirement failed: " +
                        ServerRuntimeContract.errorName(retireRc.toLong())
                } else "admission monitor stop timed out"
            }
        }
        return stopped
    }

    companion object {
        private const val TAG = "WorldRuntimeService"
        private const val ADMISSION_INTERVAL_MS = 10_000L
        private const val ADMISSION_JOIN_TIMEOUT_MS = 6_000L
        private const val BOT_STATUS_SIZE = 19
        private const val PERFORMANCE_STATUS_SIZE = 10
        /** F1-10: create-account's expansion write resolves the qualified
         * lane (the ActiveFlavor fail-loud suffix, PreparedDataStore's
         * SERVER_DATA_LANE idiom). */
        private const val ACCOUNT_EXPANSION_LANE =
            "account expansion resolves the qualified lane"
        /** Supervisor-driven specialUse FGS promotion intents. */
        const val ACTION_PROMOTE_FOREGROUND = "com.pocketrealm.action.WORLD_FOREGROUND_PROMOTE"
        const val ACTION_DEMOTE_FOREGROUND = "com.pocketrealm.action.WORLD_FOREGROUND_DEMOTE"
        const val WORLD_NOTIF_ID = 3
    }
}
