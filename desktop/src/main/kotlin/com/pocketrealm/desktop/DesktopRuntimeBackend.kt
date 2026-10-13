package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteConfigPolicy
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopDatabaseService
import com.pocketrealm.database.DesktopMariaDbEngine
import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DesktopSqliteSeeder
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.server.RealmNative
import com.pocketrealm.server.ServerStatusJson
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.ServerRuntimeFiles
import com.pocketrealm.server.WorldNative
import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.AdvertisedAddress
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RosterRead
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeBackend
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.ModerationResult
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.TunnelInterfacePolicy
import com.pocketrealm.supervisor.CharactersRead
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.FriendRosterJson
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.DesktopClientScanner
import com.pocketrealm.client.DesktopRealmlist
import com.pocketrealm.client.DesktopVanillaTweaksPatcher
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.client.nearbyUseEnabledForExecutable
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.importer.ClientGenerationStore
import com.pocketrealm.ingame.DesktopConfigWtf
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.WorldPresenceSample
import com.pocketrealm.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One WoW.exe process found by the [ClientProcessScanner] scan: the pid
 * plus the executable path the attribution (under the configured client
 * directory?) is checked against. */
data class ClientProcessScan(val pid: Int, val exePath: String?)

/** The client-process probe seam: production enumerates every WoW.exe the
 * OS can see (plain JVM ProcessHandle — no JNA); tests fake it. */
fun interface ClientProcessScanner {
    fun scan(): List<ClientProcessScan>

    companion object {
        /** Every process whose executable name is the 1.12 client's
         * WoW.exe (case-insensitive), with its full image path. A process
         * whose path is unreadable (access denied) yields no row — an
         * unattributable process must never be drained by pid. */
        val SYSTEM = ClientProcessScanner {
            ProcessHandle.allProcesses().toList().mapNotNull { process ->
                // ProcessHandle.Info.command() is Optional<String>: absent
                // when the OS denies the image path (an unattributable
                // process must never be drained by pid).
                val command = runCatching { process.info().command().orElse(null) }.getOrNull()
                val exeName = command?.substringAfterLast('\\')?.substringAfterLast('/')
                if (exeName != null && exeName.equals(CLIENT_EXE_NAME, ignoreCase = true)) {
                    ClientProcessScan(process.pid().toInt(), command)
                } else {
                    null
                }
            }
        }

        private const val CLIENT_EXE_NAME = "WoW.exe"
    }
}

/**
 * The client tree-kill seam (DESK-121): the Android contract drains the
 * WHOLE client process tree before teardown completes; on Windows the tree
 * sweep is `taskkill /PID <pid> /T /F`. Returns null on a confirmed
 * dispatch, else a human detail — the stop path's direct destroy
 * escalation stays as the fallback either way. Tests fake it.
 */
fun interface ClientTreeKiller {
    fun kill(pid: Long): String?

    companion object {
        val SYSTEM = ClientTreeKiller { pid ->
            if (!IS_WINDOWS) {
                "taskkill unavailable outside Windows"
            } else {
                runCatching {
                    val taskkill = ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                    val output = taskkill.inputStream.bufferedReader().readText().trim()
                    val rc = taskkill.waitFor()
                    if (rc == 0) null else "taskkill rc=$rc: $output"
                }.getOrElse { failure -> "taskkill failed: ${failure.message}" }
            }
        }

        val IS_WINDOWS = "windows" in System.getProperty("os.name").lowercase()
    }
}

/**
 * The desktop RuntimeBackend: the four components served by ONE process
 * (the co-load gate proved both DLLs live in this JVM), where Android
 * spread them across services.
 *
 *  - DATABASE follows the provider selection (the default-flip): the
 *    shipped MARIADB default serves through the engine lane
 *    (DesktopMariaDbEngine via DesktopDatabaseService — the first boot
 *    bootstraps the MariaDB datadir in-preflight and the daemon runs as a
 *    managed process), while the explicit SQLITE dev/compat selection is
 *    the datadir materialization by the seed-replay twin (gradlew
 *    seedRealmData / the engine's first boot): no daemon exists, so
 *    "running" means every database file + generation marker verified
 *    present and no WAL sidecar left behind;
 *  - REALM/WORLD are the in-process native runtimes driven through the
 *    shared JNI shims with the Android services' exact transition
 *    discipline (stopped-state gate, log rotation between lifetimes,
 *    lifecycle records, CONTROL_TIMEOUT_MS stop);
 *  - CLIENT (WoW.exe via ProcessBuilder): observed across the OS process
 *    table (self-spawned or ownerless — DESK-114), its foreground verbs
 *    drive the client window via JNA and never fake success (DESK-71).
 *
 * Single-transition semantics mirror the services' transitionLock.
 */
// LargeClass/TooManyFunctions: the shared RuntimeBackend seam dictates the
// verb count, and the four components' lifecycle lives together by design.
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class DesktopRuntimeBackend(
    private val roots: DesktopStorageRoots = DesktopStorageRoots(),
    private val clientDir: File? = null,
    /** The client-process scan seam (DESK-114): production scans
     * ProcessHandle.allProcesses(); tests inject a fake probe. */
    private val clientProcessScanner: ClientProcessScanner = ClientProcessScanner.SYSTEM,
    /** The client tree-kill seam (DESK-121): production runs
     * taskkill /PID <pid> /T /F; tests record the pid instead. */
    private val treeKiller: ClientTreeKiller = ClientTreeKiller.SYSTEM,
    /** DESK-139: the audio-session mute seam. Production walks the Windows
     * Core Audio session list via JNA; tests record the verdict instead. */
    private val audioMuter: AudioSessionMuter = Win32AudioMute.SYSTEM,
    /** The DESK-108 seed source override (tests inject a synthetic
     * packaged layout); null resolves the packaged/dev source lazily. */
    private val seedSourceOverride: DesktopSeedLocator.SeedSource? = null,
    /** D3 (design-lan §2.5): the desktop LAN interface seam — the app model
     * shares one instance between this backend's liveness re-checks, the
     * Settings quick-picks, and the D2 monitor, so all three filter the
     * SAME candidate list. */
    private val lanInterfaces: DesktopLanInterfaces = DesktopLanInterfaces(),
) : RuntimeBackend {
    private val files = ServerRuntimeFiles(roots)
    private val transitionLock = Any()

    /** The runtime-bootstrap seed source (packaged transcripts +
     * provenance), resolved once — cached null is "looked and honestly
     * not there" (the truly-missing refusal case). */
    private val seedSource: DesktopSeedLocator.SeedSource? by lazy {
        seedSourceOverride ?: DesktopSeedLocator.locate()
    }

    /** The prepare-for-start gate (DESK-19/53/79/98) over this backend's
     * datadir; the provider lambda keeps the lazy source lazy. */
    private val preparation = DesktopDatabasePreparation(roots.sqliteDatadir) { seedSource }

    /** The DATABASE component's materialization verbs (observe/start/stop). */
    private val database = DesktopDatabaseComponent(roots, preparation)

    /** The DESK-47/106 provider selection (the default-flip): MARIADB (the
     * shipped default, matching the on-device product — the engine lane
     * through [DesktopDatabaseService], which initializes the MariaDB
     * datadir on a first boot and writes the durable active-provider
     * marker) or SQLITE (the explicit dev/compat selection — the
     * [database] materialization, the legacy-adoption lane for pre-flip
     * desktop state). One blocking settings read per verb — the selection
     * applies at the next start, like every lane toggle. */
    private fun selectedDatabaseProvider(): Settings.DatabaseProvider =
        DesktopSettingsStore(roots.settingsFile).load().databaseProvider

    /** The MariaDB engine lane's service, created on FIRST MARIADB use
     * only (the engine's constructor resolves the provider staging; a
     * SQLite-only session must never pay for it). Closed in [close]. */
    // R1-r2-1 (desktop-twin round 2): the lane the RUNNING world booted on —
    // pinned by startWorld from the persisted selection and consumed by the
    // provisioning seams, so a Home switch after start can never re-lane the
    // expansion write onto another flavor's world database (the D63 R2-2m
    // android twin, which the desktop boot path had never received).
    // R1-r2-1 + R1-r3-2: the boot lane is observable — the model derives
    // the supervisor snapshot's flavor field from it (the journal field
    // G3-4 defined but nothing produced until now).
    private val _bootLane = kotlinx.coroutines.flow.MutableStateFlow<ClientFlavor?>(null)

    /** The lane the RUNNING world booted on (null = no world). The four
     * provision seams consume this through the [bootLane] getter. */
    val bootLaneFlow: kotlinx.coroutines.flow.StateFlow<ClientFlavor?> = _bootLane

    /** The pinned boot lane (the getter keeps every consumer's shape). */
    val bootLane: ClientFlavor?
        get() = _bootLane.value

    /** R3-1 (round 3): the snapshot bootLane was pinned FROM — the realm
     * and world conf writers consume the same one, so a persisted write
     * landing between the two component starts cannot straddle the boot. */
    @Volatile
    private var bootLaneSnapshot: Settings.Snapshot? = null

    init {
        // W4 flip (D72 retirement): the timeframe overlay's lane resolver
        // threads the PINNED boot lane — a headless host (RpHost) never
        // populates the :ui holder, whose multi-qualified no-pick state
        // would refuse a legitimate lane boot. Production start() pins
        // _bootLane before any component leg, so the resolver sees the
        // same lane the conf writer staged.
        com.pocketrealm.database.TimeframeOverlayPlan.installLaneResolver {
            bootLane ?: ServerRuntimeFiles.serverFlavor()
        }
    }

    @Volatile
    private var mariaDbService: DesktopDatabaseService? = null

    /** The MariaDB engine seam (GAP-5's observation mapping and the lane's
     * tests inject a scripted engine over a synthetic staged provider):
     * when set, the lane uses THIS engine instead of building the
     * production one over [roots]. Internal because the engine type is
     * module-internal — a public constructor parameter cannot name it. */
    @Volatile
    internal var mariaDbEngineOverride: DesktopMariaDbEngine? = null

    /** R1-r3-5: the harness gates' provider routing — the shipped-default
     * MariaDB lane routes verifier seeding through the engine; the sqlite
     * datadir leg stays the dev opt-in lane's. */
    internal fun isMariadbLane(): Boolean =
        selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB

    internal fun mariadbVerifierSeeding(): DesktopDatabaseService = mariaDbLane()

    /**
     * W4 (D72 retirement): pins the boot lane from the persisted settings
     * BEFORE any engine op — the same read-and-pin start() performs at the
     * top of every component leg, exposed for the headless harness lane
     * (RpHost) whose pre-start migration apply must resolve the LANE's
     * ledger, not the empty :ui holder. Production start() keeps its own
     * pin (this is idempotent).
     */
    /**
     * R1-2 (code-red round 1): the WORLD-START runtime-lane identity gate.
     * The shipped image loads the VANILLA lane's pocket_*.dll; a non-vanilla
     * boot is refused loud naming both lanes UNLESS that lane's own runtime
     * is actually present (the dev `-PrpFlavor` lane root), in which case
     * the boot proceeds against its per-lane lockfile. This is the
     * wire-instrument law (fail loud on drift) applied to the product boot
     * path — never silently boot vanilla core bytes against a tbc/wotlk
     * database family.
     */
    internal fun requireRuntimeLaneMatches(bootLane: ClientFlavor) {
        if (bootLane.flavorId == "vanilla") return
        val lane = bootLane.flavorId
        // R3-W4-2 (code-red round 3): the lane ROOT loads TWO DLLs at boot
        // (Main.kt: pocket_realmd_runtime then pocket_world_runtime) and
        // the lane lockfile pins BOTH — the gate verifies BOTH rows, so a
        // partial/stale rebuild that replaced only the realmd DLL is
        // refused exactly like a stale world DLL.
        val runtimeRoot = "native/.build-win-x86_64-$lane-mariadb/pocket-runtime-build"
        val dllRelatives = listOf(
            "$runtimeRoot/pocket_realmd_runtime.dll",
            "$runtimeRoot/pocket_world_runtime.dll",
        )
        val lockfileRelative = "schemas/realm-runtime-lockfile-mariadb-win-$lane.json"
        // candidate runtime DLLs: the dev lane resolves the repo root from
        // the working directory (the Gradle harness tasks run with
        // cwd = desktop/, so the repo root is the parent) AND from the
        // database root walked up (a roots tree inside the repo); the
        // packaged image has neither and refuses — correct. R3-3: existence
        // is not identity — the same root must hold the lane lockfile and
        // the staged bytes must match its pin, or a stale/partial rebuild
        // would boot unnoticed against the lane's database family.
        val bases = mutableListOf<String?>()
        bases.add(System.getProperty("user.dir"))
        bases.add(File(System.getProperty("user.dir")).parentFile?.absolutePath)
        var walked: File? = roots.database
        repeat(6) {
            walked = walked?.parentFile
            bases.add(walked?.absolutePath)
        }
        for (base in bases) {
            if (base == null) continue
            val laneLockfile = File(File(base), lockfileRelative)
            val candidates = dllRelatives.map { File(File(base), it) }
            if (candidates.all { it.isFile } && laneLockfile.isFile) {
                dllRelatives.zip(candidates).forEach { (relative, candidate) ->
                    requireLaneRuntimePinned(lane, laneLockfile, relative, candidate)
                }
                return
            }
        }
        error(
            "RUNTIME-LANE MISMATCH: the boot lane is '$lane' but " +
                "this image loads the vanilla lane's pocket_*.dll — booting " +
                "vanilla core bytes against the $lane database " +
                "family is refused. Build the $lane runtime " +
                "(tools/build_win_realm_runtime.py --flavor $lane " +
                "--database-backend mariadb) and run the harness task with " +
                "-PrpFlavor=$lane, or boot the vanilla lane.",
        )
    }

    /** R3-3: the staged lane runtime must be the lane lockfile's pinned
     * bytes (size + sha256), not merely a file at the lane path. */
    private fun requireLaneRuntimePinned(
        lane: String,
        laneLockfile: File,
        dllRelative: String,
        candidate: File,
    ) {
        val pin = runCatching {
            org.json.JSONObject(laneLockfile.readText(Charsets.UTF_8))
                .optJSONArray("artifacts")?.asSequence()
                ?.mapNotNull { it as? org.json.JSONObject }
                ?.firstOrNull { it.optString("path") == dllRelative }
        }.getOrNull()
        check(pin != null) {
            "RUNTIME-LANE MISMATCH: the $lane lane lockfile " +
                "(${laneLockfile.name}) pins no artifact at $dllRelative — the " +
                "staged DLL cannot be proven to be the $lane lane's bytes. " +
                "Rebuild the lane (tools/build_win_realm_runtime.py --flavor $lane " +
                "--database-backend mariadb) or boot the vanilla lane."
        }
        val digest = com.pocketrealm.fs.FileDigests.sha256(candidate)
        check(
            pin.getLong("size") == candidate.length() &&
                pin.getString("sha256").equals(digest, ignoreCase = true),
        ) {
            "RUNTIME-LANE MISMATCH: the staged $lane " +
                "${dllRelative.substringAfterLast('/')} " +
                "(size ${candidate.length()}, sha256 $digest) disagrees with the " +
                "$lane lane lockfile pin (size ${pin.getLong("size")}, sha256 " +
                "${pin.getString("sha256")}) — a stale or foreign core build must " +
                "not boot against the $lane database family. Rebuild with " +
                "tools/build_win_realm_runtime.py --flavor $lane --database-backend mariadb."
        }
    }

    internal fun pinBootLaneFromSettings(): ClientFlavor {
        val startSnapshot = DesktopSettingsStore(roots.settingsFile).load()
        val lane = files.bootLaneFlavor(startSnapshot)
        _bootLane.value = lane
        bootLaneSnapshot = startSnapshot
        System.err.println("PIN-BOOT-LANE flavor=" + lane.flavorId + " roots=" + roots.settingsFile)
        return lane
    }

    private fun mariaDbLane(): DesktopDatabaseService =
        mariaDbService ?: synchronized(this) {
            mariaDbService ?: DesktopDatabaseService(
                mariaDbEngineOverride ?: DesktopMariaDbEngine(
                    roots,
                    // W4 flip: the engine resolves the START's pinned boot
                    // lane (the persisted pick read once at start), never
                    // the :ui holder a headless host never populates.
                    bootLane = { bootLane },
                ),
            ).also { mariaDbService = it }
        }

    /** The MARIADB lane's DATABASE observation, mapped from the service's
     * guarded JSON (the Android ComponentObservation contract: RUNNING is
     * READY and owner-echoed; every unprovable state is FAILED with the
     * engine's honest detail — never a fake READY). The pid is the ENGINE'S
     * daemon (mariadbd, from the engine's tracked handle), never this JVM's
     * ambient pid (GAP-5). */
    private fun observeMariaDb(): ComponentObservation {
        val status = mariaDbLane().status()
        val running = status.optString("state") == "RUNNING"
        // GAP-5: the daemon pid the engine tracked at startDaemon; NULL
        // whenever the daemon is not RUNNING — never a plausible stand-in.
        val daemonPid = status.optLong("daemonPid").takeIf { running && it > 0 }?.toInt()
        val owner = ownership.getValue(RuntimeComponent.DATABASE).current()
        val ready = running && status.optBoolean("providerReady") && owner != null
        val detail = when {
            !status.optBoolean("providerReady") -> "DB-LINK: pinned MariaDB provider is not " +
                "staged; stage the distribution or select the SQLITE provider"
            // The durable-seal drift honest line (the diagnostics surface
            // for compatibilityMismatch): a populated datadir whose seals
            // the staged closure can no longer prove — PROVIDER_IDENTITY,
            // INVALID_DURABLE_STATE (a corrupt seal), or GENERATION drift —
            // is refused by the preflight as its own drift verdict
            // ([providerDriftRefusalCode]), never the first-boot "bootstrap
            // leg pending" copy.
            !status.optBoolean("initialized") && !status.isNull("compatibilityMismatch") -> {
                val mismatch = status.optString("compatibilityMismatch")
                "mariadb datadir not initialized under the staged provider closure " +
                    "(compatibilityMismatch=$mismatch; the preflight refuses as " +
                    providerDriftRefusalCode(mismatch) + ")"
            }
            !status.optBoolean("initialized") -> "mariadb datadir not initialized (bootstrap leg pending)"
            else -> "mariadb provider ${status.optInt("migrationSealedCount")}/" +
                "${status.optInt("migrationManifestCount")} sealed migrations, " +
                "state=${status.optString("state")}"
        }
        return ComponentObservation(
            component = RuntimeComponent.DATABASE,
            state = when {
                ready -> ComponentLifecycle.READY
                status.optString("state") == "FAILED" -> ComponentLifecycle.FAILED
                !status.optBoolean("providerReady") -> ComponentLifecycle.FAILED
                else -> ComponentLifecycle.STOPPED
            },
            ready = ready,
            owner = owner,
            pid = daemonPid,
            detail = detail,
        )
    }

    /** The MARIADB preflight: the shared DatabaseStartPreparation machine
     * consumes the engine status VERBATIM (the JSON fields are the shared
     * machine's exact input shape) — the same decision code both lanes'
     * admissions run. An unstaged provider refuses with the DB-LINK copy
     * before the machine runs, and durable-seal drift over an existing
     * install (a provider-closure change, a corrupt seal, or generation
     * drift) refuses as itself ([providerClosureChangeRefusal]) — the
     * machine would otherwise misroute that drift to INITIALIZE. The
     * default-flip's first-boot leg
     * rides the
     * machine's INITIALIZE action: a store with no initialized MariaDB
     * generation BOOTSTRAPS in-preflight ([bootstrapUninitializedMariaDb],
     * the DESK-108 sqlite bootstrap's engine-lane twin) instead of
     * refusing, so the marker exists naming MARIADB before any server
     * start. */
    private fun preflightMariaDb(): RuntimeActionResult {
        legacySqliteDatadirRefusal()?.let { return it }
        val status = mariaDbLane().status()
        if (!status.optBoolean("providerReady")) {
            return RuntimeActionResult(
                ok = false,
                detail = "DB-LINK: pinned MariaDB provider is not staged; stage the " +
                    "distribution (tools/build_win_realm_runtime.py --database-backend " +
                    "mariadb) or select the SQLITE provider",
            )
        }
        providerClosureChangeRefusal(status)?.let { return it }
        return when (val action = com.pocketrealm.supervisor.DatabaseStartPreparation.next(status)) {
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.READY ->
                RuntimeActionResult(ok = true, detail = "mariadb generation verified (seals current)")
            // RESUME_INITIALIZATION mirrors the Android twin's arm
            // (AndroidRuntimeBackend: INITIALIZE + RESUME_INITIALIZATION both
            // run initialize(), whose recoverIncompleteInitialization()
            // converges an interrupted first-boot INIT): without it, a
            // bootstrap that died mid-transaction strands the desktop lane
            // in a refusal no desktop verb can recover — the engine's own
            // resume path is unreachable through the production start.
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.INITIALIZE,
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.RESUME_INITIALIZATION,
            -> bootstrapUninitializedMariaDb()
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION ->
                RuntimeActionResult(
                    false,
                    "$CODE_DB_DIRTY_GENERATION: the mariadb generation is not clean-stopped; " +
                        "recover the database (Diagnostics) before start",
                )
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS ->
                RuntimeActionResult(
                    false,
                    "$CODE_DB_MIGRATION_PENDING: the mariadb datadir's pinned migrations are " +
                        "not current; apply them (applyPinnedMigrations) before start",
                )
            // The interrupted-restore recovery arm (the Android twin's
            // prepareDatabaseForStart loop leg, consumed at
            // AndroidRuntimeBackend.kt:661-662): a durable restore record a
            // DEAD process left behind — the crash window between the
            // engine's beginRestore and its commit/rollback — is settled by
            // the ownerless engine verb (the in-process token died with the
            // process; the record is the authority). Without this arm the
            // machine's first-check verdict fell to the generic DB-NOT-READY
            // refusal whose remedies (initialize, recover) both refuse on
            // the clean-sealed candidate the interrupted beginRestore had
            // already swapped in, wedging every later start on the MARIADB
            // default until the record and trees were deleted by hand.
            com.pocketrealm.supervisor.DatabaseStartPreparation.Action.ROLLBACK_PENDING_RESTORE ->
                recoverPendingMariaDbRestore()
            else -> RuntimeActionResult(
                false,
                "DB-NOT-READY: mariadb datadir admission is ${action.name}; initialize or " +
                    "recover the provider lane before start",
            )
        }
    }

    /** The ROLLBACK_PENDING_RESTORE arm's driver: the ownerless engine verb
     * settles the interrupted restore (its durable phase names the
     * direction — COMMITTING finishes the commit, anything else rolls the
     * quarantined original back), then the shared machine re-runs over the
     * recovered state exactly like the Android loop's convergence (bounded
     * by construction: the verb's rollback and commit legs both retire the
     * record, so the re-run cannot reach this arm again). READY admits the
     * start with copy naming the state that was actually settled; any other
     * verdict is the next gate's own honest refusal. */
    private fun recoverPendingMariaDbRestore(): RuntimeActionResult {
        val recovered = mariaDbLane().rollbackPendingRestore()
        if (!recovered.optBoolean("ok")) {
            return RuntimeActionResult(
                false,
                "$CODE_DB_RESTORE_PENDING-FAILED: the interrupted-restore recovery refused: " +
                    "${recovered.optString("errorClass")}: ${recovered.optString("error")}",
            )
        }
        val settled = when {
            !recovered.optBoolean("pending") ->
                "no longer pending (retired outside this start)"
            recovered.optBoolean("committed") -> "commit finished"
            else -> "rolled back"
        }
        val verdict = preflightMariaDb()
        return if (verdict.ok && recovered.optBoolean("pending")) {
            RuntimeActionResult(
                ok = true,
                detail = "$CODE_DB_RESTORE_PENDING: interrupted restore $settled; " + verdict.detail,
            )
        } else {
            verdict
        }
    }

    /**
     * The default-flip's legacy-dual-provider guard (fail-closed, the
     * DB-PROVIDER-MISMATCH family's posture): the resolved provider is
     * MariaDB while a POPULATED SQLite datadir sits beside the (empty)
     * MariaDB one with NO active-provider marker. Booting a fresh MariaDB
     * generation there would strand the legacy SQLite state silently — the
     * user's realm would vanish behind an empty MariaDB realm — so the
     * start refuses loudly instead, with the adoption remedy: select the
     * SQLITE provider explicitly (the dev/compat lane keeps the datadir
     * serving — the honest legacy adoption), or delete the datadir to
     * start fresh on MariaDB. A marker naming MARIADB clears the guard
     * (that datadir is dormant legacy state by the durable decision, the
     * same posture as the shared resolveProviderMode's rollback window);
     * an unparseable marker counts as NO marker (fail-closed — a corrupt
     * marker must never let a silent fork through).
     */
    private fun legacySqliteDatadirRefusal(): RuntimeActionResult? {
        if (DATABASES.none { database ->
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
            }
        ) {
            return null
        }
        val marker = com.pocketrealm.database.DatabaseDurableState.parseActiveProviderMarker(
            File(roots.database, com.pocketrealm.database.DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
                .takeIf(File::isFile)?.readText(),
        )
        if (marker != null) return null
        return RuntimeActionResult(
            ok = false,
            detail = "$CODE_DB_LEGACY_SQLITE: a populated SQLite datadir exists with no " +
                "active-provider marker while the database provider resolves to MariaDB; " +
                "booting MariaDB there would strand that state. Select the SQLite provider " +
                "(Settings) to keep using it, or delete " + roots.sqliteDatadir +
                " to start fresh on MariaDB",
        )
    }

    /**
     * The durable-seal drift guard (the same fail-closed posture as
     * [legacySqliteDatadirRefusal], for the engine lane): ANY
     * compatibilityMismatch the engine reports over a POPULATED datadir —
     * a RESTAGED provider closure (PROVIDER_IDENTITY: the identity the
     * durable seals must prove ownership against changed), a corrupt or
     * truncated seal (INVALID_DURABLE_STATE: initialized.json no longer
     * parses as the ownership seal the engine's own commit writes), or a
     * GENERATION drift (the initialized.json seal names a generation the
     * datadir's .pocketrealm-generation.json marker does not) — lands as
     * initialized=false while the datadir itself is still populated. The
     * shared DatabaseStartPreparation machine cannot distinguish ANY of
     * them from a first boot — it answers INITIALIZE — and the bootstrap
     * it routes to wedges forever: initialize() refuses "datadir is
     * non-empty without init marker" (it can neither adopt the legacy
     * generation nor bootstrap over it) and the engine's recover()
     * refuses likewise (dirtyRecoveryPermitted requires an initialized
     * generation). Refuse as the drift ITSELF, before the machine runs,
     * each kind with its own verdict and both honest remedies: restore /
     * re-stage what the datadir was initialized from, or adopt the new
     * closure by quarantining the legacy datadir exactly like the engine's
     * interrupted-init recovery (recoverIncompleteInitialization's
     * QUARANTINE_AND_RETRY arm: datadir aside + the durable markers
     * retired) and bootstrapping fresh. Null = no drift the guard owns
     * (initialized, no mismatch, a genuinely EMPTY datadir — the first
     * boot's INITIALIZE rides the machine — or a mismatch name the
     * engine's codec does not produce); the machine owns the verdict.
     */
    private fun providerClosureChangeRefusal(status: org.json.JSONObject): RuntimeActionResult? {
        if (status.optBoolean("initialized")) return null
        val mismatch = status.optString("compatibilityMismatch")
        if (mismatch.isEmpty()) return null
        if (roots.mariadbDatadir.listFiles().isNullOrEmpty()) return null
        return when (mismatch) {
            "PROVIDER_IDENTITY" -> RuntimeActionResult(
                ok = false,
                detail = "$CODE_DB_PROVIDER_CLOSURE_CHANGE: the staged MariaDB provider closure " +
                    "changed since " + roots.mariadbDatadir + " was initialized, so its durable " +
                    "seals no longer prove ownership and the start can neither adopt nor " +
                    "bootstrap over it. Re-stage the sealed closure this datadir was initialized " +
                    "from (tools/build_win_realm_runtime.py --database-backend mariadb) to keep " +
                    "its state, or adopt the new closure by quarantining the legacy datadir — " +
                    "move " + roots.mariadbDatadir + " under " + roots.database + " (as " +
                    "init-quarantine-<generation>-<timestamp>) and delete the initialized.json, " +
                    "clean-stop.json and secrets.json markers beside it, the engine's own " +
                    "interrupted-init quarantine discipline — then start again to bootstrap a " +
                    "fresh generation on the new closure",
            )
            "INVALID_DURABLE_STATE" -> RuntimeActionResult(
                ok = false,
                detail = "$CODE_DB_DURABLE_STATE_INVALID: the initialized.json durable seal " +
                    "beside " + roots.mariadbDatadir + " is corrupt (compatibilityMismatch=" +
                    "INVALID_DURABLE_STATE: it no longer parses as the ownership seal the " +
                    "engine's own commit writes), so the datadir's ownership can no longer be " +
                    "proven and the start can neither adopt nor bootstrap over it. Restore the " +
                    "initialized.json marker from a known-good backup of " + roots.database +
                    ", or adopt the quarantine remedy — move " + roots.mariadbDatadir +
                    " under " + roots.database + " (as init-quarantine-<generation>-<timestamp>) " +
                    "and delete the initialized.json, clean-stop.json and secrets.json markers " +
                    "beside it, the engine's own interrupted-init quarantine discipline — then " +
                    "start again to bootstrap a fresh generation",
            )
            "GENERATION" -> RuntimeActionResult(
                ok = false,
                detail = "$CODE_DB_GENERATION_DRIFT: the durable markers disagree — the " +
                    "initialized.json seal names a generation the datadir's " +
                    ".pocketrealm-generation.json marker does not (compatibilityMismatch=" +
                    "GENERATION) — so ownership can no longer be proven and the start can " +
                    "neither adopt nor bootstrap over it. Restore the matching marker pair " +
                    "(initialized.json + .pocketrealm-generation.json of the same generation) " +
                    "from a known-good backup of " + roots.database + ", or adopt the " +
                    "quarantine remedy — move " + roots.mariadbDatadir + " under " +
                    roots.database + " (as init-quarantine-<generation>-<timestamp>) and " +
                    "delete the initialized.json, clean-stop.json and secrets.json markers " +
                    "beside it, the engine's own interrupted-init quarantine discipline — " +
                    "then start again to bootstrap a fresh generation",
            )
            // The engine's status codec produces exactly the three names
            // above (DesktopMariaDbEngine.status' ownership mapping); a
            // name it does not produce leaves the verdict to the machine.
            else -> null
        }
    }

    /** The preflight refusal code a compatibilityMismatch refuses as (the
     * diagnostics surface's honest name for the drift guard's verdict);
     * DB-NOT-READY for a name the guard does not own. */
    private fun providerDriftRefusalCode(mismatch: String): String = when (mismatch) {
        "PROVIDER_IDENTITY" -> CODE_DB_PROVIDER_CLOSURE_CHANGE
        "INVALID_DURABLE_STATE" -> CODE_DB_DURABLE_STATE_INVALID
        "GENERATION" -> CODE_DB_GENERATION_DRIFT
        else -> "DB-NOT-READY"
    }

    /**
     * The default-flip's first-boot bootstrap (the DESK-108 sqlite
     * bootstrap's engine-lane twin): the machine's INITIALIZE action over
     * a stopped engine becomes an in-preflight initialize() +
     * applyPinnedMigrations(), so a fresh install on the MARIADB default
     * materializes the MariaDB datadir — NEVER the SQLite datadir — and
     * the engine's commit writes the durable active-provider marker naming
     * MARIADB before any server conf can exist. Serialized under the
     * transition lock with the unclaimed-slot check, exactly like the
     * SQLite lane's [bootstrapUnseededDatadir].
     */
    private fun bootstrapUninitializedMariaDb(): RuntimeActionResult =
        when {
            ownership.getValue(RuntimeComponent.DATABASE).current() != null ->
                RuntimeActionResult(false, "database is still claimed by a runtime session")
            else -> runMariaDbBootstrap()
        }

    /** The engine-lane bootstrap itself; any failure (unstaged closure,
     * bootstrap exit, migration refusal) is the honest refusal — never a
     * half-admitted start and never a SQLite fallback. */
    private fun runMariaDbBootstrap(): RuntimeActionResult {
        try {
            synchronized(transitionLock) {
                val initialized = mariaDbLane().initialize()
                check(initialized.optBoolean("ok")) {
                    "${initialized.optString("errorClass")}: ${initialized.optString("error")}"
                }
                val migrations = mariaDbLane().applyPinnedMigrations()
                check(migrations.optBoolean("ok")) {
                    "${migrations.optString("errorClass")}: ${migrations.optString("error")}"
                }
                return RuntimeActionResult(
                    ok = true,
                    detail = "$CODE_DB_BOOTSTRAP: initialized the mariadb provider lane from " +
                        "the staged closure (generation " +
                        initialized.optString("generationUuid").take(8) +
                        ", migrations applied=${migrations.optInt("applied")}/" +
                        "${migrations.optInt("total")}); active provider marker names MARIADB",
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            return RuntimeActionResult(
                ok = false,
                detail = "${CODE_DB_BOOTSTRAP}-FAILED: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    /** The MARIADB start: the service's guarded start (the engine throws
     * the honest DB-LINK/state-gate refusals, the service answers them as
     * JSON) mapped back to the supervisor's observation contract. */
    private fun startMariaDb(owner: ComponentOwner?): ComponentObservation {
        // The service-level ownership claim (the Android DatabaseService's
        // bind-time claim twin): the supervisor's projection and the stop
        // path's stopOwned verifyOwner against THAT registry — without the
        // claim here, every projection and every clean stop is withheld
        // ("database ownership mismatch; operation withheld"), stranding a
        // RUNNING daemon after any later stage fails (proven live
        // 2026-10-07). Same-owner re-claim is idempotent.
        owner?.let { mariaDbLane().claim(it) }
        val result = mariaDbLane().start()
        if (!result.optBoolean("ok")) {
            return ComponentObservation(
                component = RuntimeComponent.DATABASE,
                state = ComponentLifecycle.FAILED,
                ready = false,
                owner = null,
                pid = null,
                detail = "${result.optString("errorClass")}: ${result.optString("error")}",
            )
        }
        return observeMariaDb().copy(owner = owner)
    }

    /** The client drain in progress (the stop path): while true, a client
     * death is the drain's own doing, never a journaled abnormal exit. */
    @Volatile
    private var drainingClient = false
    private val clientExitLock = Any()

    /** DESK-51: the user's realmlist backup slot for the running client
     * session; restored (and retired) on the client teardown paths. */
    private val realmlistSession = DesktopRealmlist.Session()

    /** DESK-51: the client directory this session projected the realmlist
     * into (the restore target), captured at launch. */
    @Volatile
    private var launchedClientDir: File? = null

    /** Per-component ownership slots mirroring the Android services'
     * ComponentOwnership handshake minus the IBinder lease: the owner's
     * liveness is this JVM itself. The shared supervisor's readiness proofs
     * (startStage), stopOwned guards, and orphan-heal policy all key off
     * observation.owner, so every start claims, every observe echoes, and
     * every owner-scoped verb verifies — null owners are only ever visible
     * on genuinely ownerless (stopped) components. */
    private val ownership = RuntimeComponent.entries.associateWith { DesktopOwnershipSlot(it.name) }

    @Volatile
    private var client: Process? = null

    /** The active prepared-data generation's runtime lease, held from
     * world start to world stop. */
    @Volatile
    private var worldDataLease: AutoCloseable? = null

    /** DESK-25 spent-world state, the JVM mirror of the native pin (the
     * embedded lane refuses every start after a world reached READY once:
     * the WRONG_STATE in-process-restart error — a second world lifetime
     * needs a fresh app process). Set once a world start reaches READY;
     * the app model surfaces it so the Home card offers the app-relaunch
     * action instead of a Start that can only refuse. */
    @Volatile
    var worldLifetimeSpent: Boolean = false
        private set

    init {
        // The shared BotSelection resolution reads saved presets through
        // the process-wide BotCustomPresets store; install it over the
        // desktop storage root once per process (idempotent), mirroring
        // the Android app's filesDir/bots install.
        com.pocketrealm.bots.BotCustomPresets.install(java.io.File(roots.root, "bots"))
    }

    /**
     * DESK-8/23/43/99: the bot admission monitor riding the world's
     * lifetime — the shared BotAdmissionController fed by the JVM sampler
     * (DesktopBotResourceSampler) through the admission-generation
     * handshake. Every WorldNative touch sits behind a seam, and
     * construction loads no natives, so DLL-less lanes construct the
     * backend freely. The record file is the next boot's conf feed (the
     * admission-lowered `PocketRealm.BotTarget` knob survives the
     * one-world-per-process lifetime boundary).
     */
    private val botAdmission = DesktopBotAdmission(
        sampler = DesktopBotResourceSampler(roots.root),
        recordFile = File(File(roots.runtime, "server"), "bot-admission.json"),
    )

    /** The live admission verdict for the Bots screen (null while no
     * monitor runs — i.e. no bot-profile world is up in this process). */
    fun botAdmissionVerdicts(): kotlinx.coroutines.flow.StateFlow<DesktopBotAdmissionVerdict?> =
        botAdmission.verdict

    /** The world's native pause flag (companion mode / the pause verb),
     * false when the native lane cannot answer (never a throw into UI). */
    fun worldPaused(): Boolean =
        runCatching { WorldNative.isWorldPausedNative() == 1 }.getOrDefault(false)

    /** The resolved launch bot profile id, or null when the realm would
     * start bots-disabled. Exposed so the launch spec can carry it - the
     * supervisor's bot-aware world budget keys off spec.profileId. */
    fun activeBotProfileId(): String? = resolveBotProfile()?.id

    /** Bot-aware world READY budget (the supervisor's stageTimeoutMs
     * shape): bot boots get the 600s-class budget because honest-READY
     * waits for the world loop's first tick and bot admission starts
     * inside it; bots-disabled boots keep the plain 120s-class budget. */
    private fun worldReadyWaitMs(): Long =
        if (activeBotProfileId() != null) BOT_WORLD_READY_WAIT_MS else WORLD_READY_WAIT_MS

    /**
     * The launch bot profile from the settings snapshot — the Android
     * WorldRuntimeService.startBotProfile contract: a selected built-in or
     * saved preset resolves through the shared BotSelection rule; no
     * selection at all keeps the reviewed bots-disabled conf. DESK-118/124:
     * the launcher-level advanced override (settings `botAdvancedEnabled`/
     * `botAdvanced`) passes through for real — the shared resolve's
     * advanced branch (BotSelection.kt:46-52) is reachable, exactly the
     * Android resolution (no effect on the saved-preset branch, which
     * ignores the advanced args).
     */
    private fun resolveBotProfile(): com.pocketrealm.bots.BotProfile? {
        val snapshot = DesktopSettingsStore(roots.settingsFile).load()
        if (snapshot.botProfileId.isEmpty() && snapshot.botSavedPresetId == null) return null
        val selection = com.pocketrealm.bots.BotSelection.resolve(
            savedPresetId = snapshot.botSavedPresetId,
            advancedEnabled = snapshot.botAdvancedEnabled,
            advancedTarget = advancedLaunchTarget(snapshot),
            advanced = snapshot.botAdvanced,
            profileId = snapshot.botProfileId,
        )
        return selection.profile
    }

    /**
     * GAP-2 (was DESKPROD-2): the launch spec is the AUTHORITY for the
     * boot's bot profile — the settings resolution is admitted only when
     * it is the one the spec froze. The ordinary path builds
     * spec.profileId from [activeBotProfileId] (so the two always agree),
     * while the restore proof forces the bots-disabled
     * [DesktopAppModel.DESKTOP_PROFILE] over a settings selection: the
     * proof then stages the DISABLED bot conf and a zero BotTarget, so no
     * population accounts are provisioned into the restored candidate
     * before READY. Resolution (not lookup-by-id) because a saved preset
     * or advanced selection mints profiles outside the BotProfiles
     * registry — the settings resolution can only be confirmed, never
     * reconstructed from the id alone.
     */
    internal fun launchBotProfile(spec: RuntimeLaunchSpec): com.pocketrealm.bots.BotProfile? =
        resolveBotProfile()?.takeIf { settings -> settings.id == spec.profileId }

    /**
     * The population target the shared advanced branch resolves with. The
     * adv4 contract (BotProfiles.advanced) requires a 25-step target in
     * 25..700 and a floor at the nearby-bot limit — a launcher override
     * stacked over a non-25-step built-in (the 80/160-class world sizes)
     * must normalize instead of refusing the launch. The Android twin never
     * hits this: its advanced lane only ever carried adv4-minted targets.
     */
    fun advancedLaunchTarget(snapshot: com.pocketrealm.storage.Settings.Snapshot): Int =
        if (!snapshot.botAdvancedEnabled) {
            snapshot.botPopulationTarget
        } else {
            val floor = maxOf(25, snapshot.botAdvanced.nearbyBotLimit)
            snapshot.botPopulationTarget
                .coerceIn(floor, 700)
                .let { it - (it % 25) }
                .coerceAtLeast(floor)
        }

    /**
     * Preflight = the DESK-53/98/108 start-admission gate:
     *
     *  - the MARIADB lane (the default-flip's shipped default) preflights
     *    through the engine service: a first boot BOOTSTRAPS the MariaDB
     *    datadir in-preflight (the machine's INITIALIZE leg), a populated
     *    legacy SQLite datadir with no active-provider marker refuses with
     *    DB-LEGACY-SQLITE (never a silent MariaDB boot over stranded
     *    state), and every unstaged provider refuses with the DB-LINK copy;
     *  - the explicit SQLITE selection keeps the materialization gate: a
     *    fully unseeded datadir BOOTSTRAPS in-app from the packaged
     *    transcripts (DESK-108 — the jpackage image ships the four
     *    .sqlz transcripts + BUILD_PROVENANCE.json; the INITIALIZE leg
     *    of the shared DatabaseStartPreparation machine);
     *  - a partially seeded datadir refuses with the honest
     *    delete-and-reseed copy (the seeder never seeds over partials);
     *  - a seeded datadir must PROVE its migration seal (DESK-53): a
     *    stale, tampered, or pre-seal (legacy) datadir refuses with a
     *    distinct code — DB-MIGRATION-PENDING / DB-PROVIDER-MISMATCH /
     *    DB-DIRTY-GENERATION — never the generic DB-NOT-SEEDED.
     *
     * D1 (design-lan §2.4) adds the mode-keyed legs around the datadir
     * gate:
     *  - LAN_JOIN runs NO server legs at all: it returns after the client
     *    prerequisites, mirroring Android's early return
     *    (AndroidRuntimeBackend.kt:177-179) — the guest app's own
     *    datadir/seeds are unrequired for a client-only join;
     *  - LAN_HOST adds the liveness legs AFTER the datadir gates pass:
     *    the stored authorization (defensive twin of the model gate) and
     *    the endpoint being a CURRENTLY live private interface (through
     *    the D3 filtered view);
     *  - LOCAL runs no liveness leg (loopback never disappears).
     */
    override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            if (spec.mode == RuntimeMode.LAN_JOIN) {
                return@withContext preflightJoinClient()
            }
            val datadir = preflightDatadir()
            if (!datadir.ok) return@withContext datadir
            lanHostLivenessRefusal(spec) ?: datadir
        }

    /** The LAN_JOIN preflight: client prerequisites only. A client that
     * does not resolve refuses with the raw LAN-JOIN-CLIENT-MISSING marker
     * (D9's humanize renders the import copy; the raw detail stays in the
     * logs verbatim). */
    private fun preflightJoinClient(): RuntimeActionResult =
        if (resolveClientDir() == null) {
            RuntimeActionResult(
                ok = false,
                detail = "LAN-JOIN-CLIENT-MISSING: joining needs a launchable game " +
                    "client and none resolves (import or choose one first)",
            )
        } else {
            RuntimeActionResult(
                ok = true,
                detail = "join: client and private LAN endpoint verified " +
                    "(no local server legs)",
            )
        }

    /** R3-6-2 (W4 code-red round 6): the SQLite provider ships only the
     * VANILLA classic* datadir/seed transcripts — a tbc/wotlk boot lane on
     * the SQLite provider dies late with the non-actionable 'tbcrealmd is
     * not seeded' whose remedy can only produce vanilla again. Refuse LOUD
     * before the legacy gate, naming the MariaDB remedy (the android
     * twin's ANDROID-LANE-SCOPE shape). Internal for the JVM pin. */
    internal fun requireSqliteLaneMatchesProvider() {
        if (selectedDatabaseProvider() != Settings.DatabaseProvider.SQLITE) return
        // no persisted pick: the existing DB-NOT-SEEDED gate owns the
        // refusal (the D19 unseeded leg) — never a second ambiguous-lane
        // error ahead of it.
        val persistedPick = DesktopSettingsStore(roots.settingsFile).load()
            .selectedFlavorOrNull() ?: return
        val sqliteBootLane = files.bootLaneFlavor(
            DesktopSettingsStore(roots.settingsFile).load()
                .copy(selectedFlavorId = persistedPick),
        )
        check(sqliteBootLane.flavorId == ClientFlavors.VANILLA.flavorId) {
            "SQLITE-LANE-SCOPE: the SQLite provider ships the vanilla " +
                "classic* datadir and seed transcripts only — the " +
                "${sqliteBootLane.flavorId} lane cannot boot on SQLite. " +
                "Switch Settings → Database provider to MariaDB (the " +
                "shipped default, which provisions the " +
                "${sqliteBootLane.flavorId} family) or re-pick Vanilla on Home."
        }
    }

    /** The pre-D1 datadir/provider gate, verbatim (provider selection,
     * bootstrap, seal proof) — extracted so the D1 mode legs wrap it. */
    private fun preflightDatadir(): RuntimeActionResult {
        // DESK-47/106 provider selection (the default-flip): the MariaDB
        // lane — the shipped DEFAULT — preflights through the engine
        // service (the shared DatabaseStartPreparation machine over the
        // engine's durable status, the first-boot INITIALIZE bootstrap, and
        // the legacy-SQLITE-datadir guard), never through the SQLite
        // datadir gate; the explicit SQLITE selection keeps that gate.
        if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
            return preflightMariaDb()
        }
        // R3-6-2: the SQLite provider is vanilla-scoped — refuse a
        // tbc/wotlk boot lane loud before the legacy gate.
        requireSqliteLaneMatchesProvider()
        val missing = DATABASES.filterNot { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        return when {
            missing.isEmpty() -> {
                val verdict = preparation.evaluate()
                if (verdict.ready) {
                    RuntimeActionResult(ok = true, detail = verdict.detail)
                } else {
                    RuntimeActionResult(ok = false, detail = "${verdict.code}: ${verdict.detail}")
                }
            }
            missing.size < DATABASES.size -> RuntimeActionResult(
                ok = false,
                detail = "$CODE_DB_NOT_SEEDED: partially seeded datadir — " +
                    missing.joinToString() + " missing under " + roots.sqliteDatadir +
                    "; delete " + roots.sqliteDatadir + " and relaunch to re-seed from the " +
                    "packaged transcripts, or run gradlew seedRealmData from desktop/",
            )
            else -> bootstrapUnseededDatadir()
        }
    }

    /**
     * D1's LAN_HOST-only liveness legs, run AFTER the datadir gates pass.
     * Null = no refusal (LOCAL passes through untouched; LAN_JOIN never
     * reaches here). The predicate is mode-keyed, not topology-assuming: a
     * future tunnel/relay hosting mode that reuses LAN_HOST with a
     * non-private bind cannot exist without changing the shared
     * RuntimeLaunchSpec constructor first — and must then supply its own
     * predicate rather than inheriting this one (design-lan §2.4's
     * mode/predicate table).
     *
     * Mode-aware (design-tunnel §10, the shared [InterfaceWatch] table —
     * the Android preflight's exact twin): a plain LAN start proves the
     * private LAN interface; a tunnel-advertised start proves the TUNNEL
     * adapter instead (the saved LAN address need not be an active
     * interface for the tunnel lane — hotel Wi-Fi, DHCP change, hotspot —
     * and physical-LAN churn must never refuse a tunnel host); a loopback
     * advertisement (the cloudflared recipe) proves nothing — it binds
     * 0.0.0.0 with no interface to lose.
     */
    internal fun lanHostLivenessRefusal(spec: RuntimeLaunchSpec): RuntimeActionResult? {
        if (spec.mode != RuntimeMode.LAN_HOST) return null
        val snapshot = DesktopSettingsStore(roots.settingsFile).load()
        if (!snapshot.allowLanPlayers) {
            return RuntimeActionResult(
                false,
                "LAN hosting requires explicit allowLanPlayers authorization " +
                    "(defensive re-check: the stored toggle is off)",
            )
        }
        when (val watch = com.pocketrealm.supervisor.InterfaceWatch.forSession(
            spec.advertisedAddress?.value,
        )) {
            com.pocketrealm.supervisor.InterfaceWatch.None -> Unit
            com.pocketrealm.supervisor.InterfaceWatch.Lan ->
                if (!lanInterfaces.isCurrentPrivateInterface(spec.endpoint)) {
                    return RuntimeActionResult(
                        false,
                        "LAN host address is not an active private IPv4 interface",
                    )
                }
            is com.pocketrealm.supervisor.InterfaceWatch.Tunnel ->
                if (!TunnelInterfacePolicy.isCurrentTunnelInterface(watch.advertised)) {
                    return RuntimeActionResult(
                        false,
                        "the tunnel adapter carrying ${watch.advertised.value} is not up; " +
                            "join the tunnel network before hosting over the internet",
                    )
                }
        }
        return null
    }

    /** The DESK-108 bootstrap: seed the empty datadir from the packaged
     * transcripts + provenance (the app dir ships them flat; the dev
     * checkout resolves the staging tree). Serialized under the
     * transition lock; the unclaimed-slot check keeps a concurrent
     * in-process session out. No native runtime probes here on purpose:
     * an empty datadir has nothing a live runtime could hold, and
     * preflight must stay pure-JVM (DLL-less machines preflight too). */
    private fun bootstrapUnseededDatadir(): RuntimeActionResult =
        when {
            seedSource == null -> missingSeedSourceResult()
            ownership.getValue(RuntimeComponent.DATABASE).current() != null ->
                RuntimeActionResult(false, "database is still claimed by a runtime session")
            else -> runBootstrapSeed()
        }

    /** The truly-missing case: nothing bundled, nothing in the checkout
     * — the rewritten DB-NOT-SEEDED copy (it instructs BOTH the packaged
     * posture and the dev remedy). */
    private fun missingSeedSourceResult(): RuntimeActionResult =
        RuntimeActionResult(
            ok = false,
            detail = "$CODE_DB_NOT_SEEDED: " + DATABASES.joinToString() + " missing under " +
                roots.sqliteDatadir + "; no packaged seed transcripts found to bootstrap " +
                "from (the packaged app ships the four transcripts + BUILD_PROVENANCE.json " +
                "in its app dir; a dev checkout seeds with gradlew seedRealmData from desktop/)",
        )

    /** The seed itself; any failure (missing seam DLL, pin mismatch,
     * replay error) is the honest refusal — never a half-admitted start. */
    private fun runBootstrapSeed(): RuntimeActionResult {
        val source = seedSource ?: return missingSeedSourceResult()
        return try {
            synchronized(transitionLock) {
                // The resolver form so the flat packaged layout (no seed/
                // prefix) replays through the one seeder discipline.
                val result = DesktopSqliteSeeder(
                    seedDir = source.provenance.parentFile ?: File("."),
                    provenanceFile = source.provenance,
                    transcriptFor = { database -> source.transcripts.getValue(database) },
                ).seedAll(java.util.UUID.randomUUID().toString(), roots.sqliteDatadir)
                RuntimeActionResult(
                    ok = true,
                    detail = "$CODE_DB_BOOTSTRAP: seeded " + DATABASES.size + " databases from " +
                        source.provenance + " (migrationSealSha256=" +
                        result.getString("migrationSealSha256").take(SEAL_DIGEST_TAIL_CHARS) + ")",
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            RuntimeActionResult(
                ok = false,
                detail = "${CODE_DB_BOOTSTRAP}-FAILED: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    override suspend fun observe(component: RuntimeComponent): ComponentObservation =
        withContext(Dispatchers.IO) {
            when (component) {
                RuntimeComponent.DATABASE ->
                    if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
                        observeMariaDb()
                    } else {
                        database.observe(ownership.getValue(component).current())
                    }
                RuntimeComponent.REALM -> observeRealm()
                RuntimeComponent.WORLD -> observeWorld()
                RuntimeComponent.CLIENT -> observeClient()
            }
        }

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation = withContext(Dispatchers.IO) {
        val slot = ownership.getValue(component)
        slot.claim(owner)
        // R3-1 (round 3): the WHOLE start transition resolves ONE lane —
        // pinned BEFORE any component leg (realmd.conf, the projection,
        // world.conf, the overlay and the provisioning writes all read
        // this pin), so a Home tap during the up-to-25s realm READY window
        // cannot tear one boot across two flavor lanes.
        run {
            val startSnapshot = DesktopSettingsStore(roots.settingsFile).load()
            _bootLane.value = files.bootLaneFlavor(startSnapshot)
            bootLaneSnapshot = startSnapshot
        }
        // R1-2 (code-red round 1): the boot lane must match the loaded
        // native runtime BEFORE any server native starts — the shipped
        // image carries the VANILLA lane's pocket_*.dll, so a non-vanilla
        // boot without its own per-lane runtime present refuses loud naming
        // both lanes, never silently boots vanilla core bytes against a
        // tbc/wotlk database family.
        bootLane?.let { requireRuntimeLaneMatches(it) }

        when (component) {
            RuntimeComponent.DATABASE ->
                if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
                    startMariaDb(slot.current())
                } else {
                    database.start(slot.current())
                }
            // D1: the Android start-time re-checks (AndroidRuntimeBackend
            // REALM/WORLD branches) mirrored on the desktop — the interface
            // is re-proved immediately before each server native starts.
            RuntimeComponent.REALM -> {
                checkLanHostInterfaceAtStart(spec, "realmd")
                startRealm(bindAddressFor(spec))
            }
            RuntimeComponent.WORLD -> {
                checkLanHostInterfaceAtStart(spec, "world")
                startWorld(bindAddressFor(spec), spec)
            }
            RuntimeComponent.CLIENT -> startClient(spec.endpoint)
        }
    }

    /**
     * Design-tunnel §4.4.2: the bind value the conf writers receive. Plain
     * LOCAL/LAN keeps the typed endpoint; a tunnel-advertised start binds
     * the wildcard (0.0.0.0 — the raw conf value, never a RealmEndpoint)
     * because the advertised tunnel address is only reachable through the
     * tunnel adapter, and binding the endpoint's interface alone would
     * refuse the friend's tailnet traffic. A loopback advertisement (the
     * documented cloudflared recipe) binds the wildcard the same way.
     */
    private fun bindAddressFor(spec: RuntimeLaunchSpec): String =
        if (spec.tunnelAdvertising) RealmEndpoint.WILDCARD_BIND else spec.endpoint.address

    /**
     * D1's start-time leg, mode-aware (design-tunnel §10): plain LAN_HOST
     * re-proves the private LAN interface exactly as design-lan specifies;
     * a tunnel-advertised start proves the TUNNEL adapter instead (the
     * advertised address must be live on one) and skips the interface check
     * when the advertisement is loopback (the cloudflared recipe binds
     * 0.0.0.0 with no tunnel adapter to prove). Refusing throws the stage
     * (the supervisor degrades it to the honest ERROR/stop path).
     */
    private fun checkLanHostInterfaceAtStart(spec: RuntimeLaunchSpec, component: String) {
        if (spec.mode != RuntimeMode.LAN_HOST) return
        val advertised = spec.advertisedAddress
        when {
            advertised == null ->
                check(lanInterfaces.isCurrentPrivateInterface(spec.endpoint)) {
                    "LAN host interface disappeared before $component start"
                }
            advertised.isLoopback -> Unit // cloudflared: bind 0.0.0.0, no tunnel adapter to prove
            else -> check(TunnelInterfacePolicy.isCurrentTunnelInterface(advertised)) {
                "the tunnel adapter carrying $advertised disappeared before $component start"
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // any projection failure becomes an honest result, never a crash
    override suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult = projectRealmAdvertisement(databaseOwner, endpoint, endpoint.address)

    /**
     * Design-tunnel §4.4.2/§5 (bind/advertise split): the desktop lane
     * writes the ADVERTISED address into the one realmlist row — the value
     * friends' clients dial — which diverges from the bind endpoint in
     * tunnel mode. Guards, in order: the raw string must fit the realmlist
     * column (varchar(32) upstream; §5's honest refusal instead of
     * truncation), and must parse as a typed [AdvertisedAddress] — canonical
     * numeric IPv4 (loopback/private/link-local/CGNAT in v1), which keeps
     * the interpolated UPDATE's injection guard trivially closed (the same
     * ENDPOINT_ADDRESS dotted-quad shape, widened by the reviewed v2
     * grammar). The projected row is reported back in the result detail.
     */
    @Suppress("TooGenericExceptionCaught") // any projection failure becomes an honest result, never a crash
    override suspend fun projectRealmAdvertisement(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
        advertised: String,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        try {
            check(advertised.length <= AdvertisedAddress.MAX_LENGTH) {
                "realmlist projection refuses an address longer than " +
                    "${AdvertisedAddress.MAX_LENGTH} characters (the realmlist column)"
            }
            val typed = AdvertisedAddress.parse(advertised)
            check(ENDPOINT_ADDRESS.matches(typed.value)) {
                "realmlist projection refuses a non-IPv4 address: ${typed.value}"
            }
            // The row's realmbuilds advertisement rides the same projection
            // on BOTH lanes: the pinned seeds ship an EMPTY build list (the
            // source dump's row), and realmd's ok_build gate would flag the
            // realm offline for every build until the world's own start
            // UPDATE — this write owns the row from database start, before
            // realmd serves a logon. Resolved through the flavor registry
            // like the projected database name below (DESK-93), never a
            // hard-coded build list.
            // R3-1: the pinned boot lane, never the live machine a
            // mid-window switch would flip.
            val builds = (bootLane ?: ServerRuntimeFiles.serverFlavor()).realmdAcceptedBuilds
            check(builds.length <= REALMBUILD_LIST_MAX_CHARS && REALMBUILD_LIST.matches(builds)) {
                "realmlist projection refuses a non-build-list advertisement: $builds"
            }
            // The DESK-47/106 provider selection routes the projection's
            // TARGET the same way every other database verb routes: the
            // MARIADB lane's realmd database lives IN THE ENGINE
            // (the owner-gated projectRealmEndpoint the engine already
            // exposes — its UPDATE+verify runs as the pocket_core user over
            // the pipe), never the SQLite datadir file. The unconditional
            // sqlite file write refused every MariaDB start (the realmd
            // schema was not seeded there) because the sqlite datadir is
            // legitimately empty on that lane.
            if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
                val projected = mariaDbLane().projectRealmEndpoint(
                    databaseOwner,
                    typed.value,
                    com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                    builds,
                )
                if (!projected.optBoolean("ok")) {
                    return@withContext RuntimeActionResult(
                        ok = false,
                        detail = "REALMLIST-PROJECTION-FAILED: " +
                            "${projected.optString("error")}",
                    )
                }
                return@withContext RuntimeActionResult(
                    ok = true,
                    detail = if (typed.value == endpoint.address) {
                        "realmlist -> ${typed.value}"
                    } else {
                        "realmlist -> ${typed.value} (bind ${endpoint.address})"
                    },
                )
            }
            // DESK-93: the projected database resolves through the flavor
            // registry (ServerRuntimeFiles.serverDatabases), never a
            // hard-coded vanilla name — the conf's LoginDatabaseInfo and
            // this row land in the same database by construction.
            val realmdName = ServerRuntimeFiles.serverDatabases(
                bootLane ?: ServerRuntimeFiles.serverFlavor(),
            ).realmd
            val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, realmdName)
            check(realmd.isFile) { "$realmdName is not seeded" }
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                db.exec(
                    "UPDATE realmlist SET address = '${typed.value}', realmbuilds = '$builds' " +
                        "WHERE id = 1;",
                )
                val projected = db.queryText(
                    "SELECT address || ' ' || realmbuilds FROM realmlist WHERE id = 1;",
                )
                check(projected == "${typed.value} $builds") { "realmlist projection did not stick: $projected" }
            }
            RuntimeActionResult(
                ok = true,
                detail = if (typed.value == endpoint.address) {
                    "realmlist -> ${typed.value}"
                } else {
                    "realmlist -> ${typed.value} (bind ${endpoint.address})"
                },
            )
        } catch (failure: Throwable) {
            RuntimeActionResult(ok = false, detail = "REALMLIST-PROJECTION-FAILED: ${failure.message}")
        }
    }

    /**
     * D4 (design-lan §3.1): the PROJECTED advertisement — the
     * `realmlist.address` row the host's world start wrote (§2.2). The
     * invite card renders this, never the stored `lanAddress`, so the card
     * cannot promise an address the realm no longer advertises (and a
     * future tunnel advertise/bind split that rewrites the row is honored
     * automatically). Null when the row is unreadable (datadir missing or
     * not yet seeded) — never a throw into the UI poll path.
     */
    fun projectedRealmAddress(): String? = runCatching {
        val realmdName = ServerRuntimeFiles.serverDatabases(
                bootLane ?: ServerRuntimeFiles.serverFlavor(),
            ).realmd
        val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, realmdName)
        if (!realmd.isFile) return@runCatching null
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.queryText("SELECT address FROM realmlist WHERE id = 1;")
                ?.takeUnless { it.isEmpty() }
        }
    }.getOrNull()

    /**
     * Design-tunnel §6: the realm's NAME for the invite header — the same
     * row's `name` column, the string the friend actually sees in-game.
     * Fail-soft null exactly like [projectedRealmAddress].
     */
    fun projectedRealmName(): String? = runCatching {
        val realmdName = ServerRuntimeFiles.serverDatabases(
                bootLane ?: ServerRuntimeFiles.serverFlavor(),
            ).realmd
        val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, realmdName)
        if (!realmd.isFile) return@runCatching null
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.queryText("SELECT name FROM realmlist WHERE id = 1;")
                ?.takeUnless { it.isEmpty() }
        }
    }.getOrNull()

    override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val slot = ownership.getValue(component)
            slot.verifyOwner(owner)
            when (component) {
                RuntimeComponent.DATABASE ->
                    (if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
                        // The owner-gated release (the Android stopOwned
                        // contract: the claim is the handshake, the seals
                        // stay as the engine left them).
                        val result = mariaDbLane().stopOwned(owner)
                        RuntimeActionResult(
                            ok = result.optBoolean("ok"),
                            detail = if (result.optBoolean("ok")) {
                                "mariadb provider stopped; generation at rest"
                            } else {
                                "${result.optString("errorClass")}: ${result.optString("error")}"
                            },
                        )
                    } else {
                        database.stop()
                    }).also { if (it.ok) slot.release(owner) }
                RuntimeComponent.REALM -> stopRealm().also { if (it.ok) slot.release(owner) }
                RuntimeComponent.WORLD -> stopWorld().also { if (it.ok) slot.release(owner) }
                RuntimeComponent.CLIENT -> stopClient().also { if (it.ok) slot.release(owner) }
            }
        }

    override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val slot = ownership.getValue(component)
            slot.verifyOwner(owner)
            when (component) {
                RuntimeComponent.REALM -> {
                    files.writeLifecycle("realm", false, "forced-stop")
                    val rc = runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                    if (rc == 0) slot.release(owner)
                    RuntimeActionResult(rc == 0, "forced realm stop rc=$rc")
                }
                RuntimeComponent.WORLD -> {
                    files.writeLifecycle("world", false, "forced-stop")
                    val rc = synchronized(transitionLock) {
                        // The admission monitor retires with the forced stop
                        // (best-effort; a force-stop never fails on it).
                        runCatching { botAdmission.stop() }
                        val stopped = runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                        runCatching { worldDataLease?.close() }
                        worldDataLease = null
                        stopped
                    }
                    if (rc == 0) slot.release(owner)
                    RuntimeActionResult(rc == 0, "forced world stop rc=$rc")
                }
                else -> stop(component, owner)
            }
        }

    /** Adoption mirrors the Android contract: refused unless the component
     * is a RUNNING orphan right now (ownerless and not stopped). In-process
     * that state should not occur — every start claims first — but the
     * honest refusal keeps the supervisor's orphan-heal semantics sound. */
    override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val observed = runCatching { observe(component) }.getOrElse {
                return@withContext RuntimeActionResult(
                    false,
                    "adoption observe $component failed: ${it.message ?: it.javaClass.simpleName}",
                )
            }
            val orphan = observed.state != ComponentLifecycle.STOPPED && observed.owner == null
            if (!orphan) {
                RuntimeActionResult(
                    false,
                    "adoption refused: ${component.name.lowercase()} is not an ownerless running component " +
                        "(state=${observed.state})",
                )
            } else {
                ownership.getValue(component).claim(owner)
                RuntimeActionResult(true, "${component.name.lowercase()} adopted under ${owner.sessionId}")
            }
        }

    /**
     * DESK-61/29: an honest presence sample from what the world native
     * already reports — the exact fields the Android backend samples out of
     * the world service status: world lifecycle, playerbotsEnabled +
     * realPlayers from the bot status lane, onlinePlayers from the online
     * count. EMPTY stays reserved for a stopped world (the supervisor never
     * samples a stopped world; a caller that does gets the definitively
     * playerless sample). The test legs sit behind the existing NativeGate
     * skip pattern (assumeNativesStaged; the boot-dependent non-empty leg
     * additionally assumes seeds + prepared data), so machines without the
     * staged DLL lanes skip instead of erroring. A native whose status
     * contract drifted fails like every other observe (the supervisor
     * degrades the throw to EMPTY).
     */
    override suspend fun observeWorldPresence(): WorldPresenceSample = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state == ComponentLifecycle.STOPPED) return@withContext WorldPresenceSample.EMPTY
        val bot = runCatching { WorldNative.botStatusNative() }.getOrElse { LongArray(0) }
        val readable = bot.size == BOT_STATUS_SIZE && bot[0] == ServerRuntimeContract.ABI_VERSION
        WorldPresenceSample(
            state = world.state,
            realPlayers = if (readable) bot[BOT_STATUS_REAL_PLAYERS].toInt().coerceAtLeast(0) else 0,
            playerbotsEnabled = readable && bot[BOT_STATUS_ENABLED] != 0L,
            onlinePlayers = runCatching { WorldNative.onlinePlayersNative() }.getOrDefault(0).coerceAtLeast(0),
        )
    }

    /**
     * DESK-71: the shared ForegroundPromotionPolicy's promote, acting on
     * the client window — the desktop runtime's only foreground surface
     * (world+database live in this process; the Android twin promotes their
     * services' FGS instead). Never fakes success: no client, no visible
     * window, or a refused SetForegroundWindow all report ok=false.
     */
    override suspend fun promoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val pid = observedClientPid()
                ?: return@withContext RuntimeActionResult(
                    ok = false,
                    detail = "client not running; no window to promote to the foreground",
                )
            val failure = Win32ClientWindow.promote(pid)
            if (failure == null) {
                RuntimeActionResult(true, "client window promoted to the foreground (pid $pid)")
            } else {
                RuntimeActionResult(false, failure)
            }
        }

    /**
     * DESK-71: the policy's demote. Nothing-to-demote is a true postcondition
     * (no client window holds the foreground), reported ok=true; a client
     * window that DOES hold the foreground hands focus back to the app
     * console (never minimized), and a refused hand-back reports ok=false.
     */
    override suspend fun demoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val pid = observedClientPid()
                ?: return@withContext RuntimeActionResult(
                    true,
                    "client not running; no window holds the foreground",
                )
            val failure = Win32ClientWindow.demote(pid)
            if (failure == null) {
                RuntimeActionResult(true, "client window demoted from the foreground (pid $pid)")
            } else {
                RuntimeActionResult(false, failure)
            }
        }

    override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val rc = runCatching { WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS) }
                .getOrDefault(-1)
            RuntimeActionResult(rc == 0, "world save rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
        }

    override suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val rc = runCatching { WorldNative.setCompanionModeNative(if (enabled) 1 else 0) }.getOrDefault(-1)
            RuntimeActionResult(rc == 0, "companion mode rc=$rc")
        }

    // ------------------------------------------------------------------
    // DESK-116/122/123/77: the live world-control verbs — the Android
    // world service's ops (WorldRuntimeService.kt:166-278) over the
    // already-compiled WorldNative externals, guarded like provisionAccount
    // (world READY + owned by this session) and returning honest results.
    // The shared RuntimeBackend seam is untouched: these are desktop
    // members, mirroring how worldTelemetry/realmTelemetry extend the
    // backend without widening the shared contract.
    // ------------------------------------------------------------------

    /**
     * The world-READY + owner guard the live-control verbs share (the
     * provisionAccount pattern, honest-refusal form). A native lane that
     * cannot answer (no DLL staged) degrades to the same honest
     * not-ready refusal, so the refusal legs hold on every machine.
     */
    private fun liveWorldRefusal(owner: ComponentOwner): String? {
        val world = runCatching { observeWorld() }.getOrElse { failure ->
            return "WORLD_NOT_READY: world status unavailable " +
                "(${failure.javaClass.simpleName}: ${failure.message})"
        }
        if (world.state != ComponentLifecycle.READY) {
            return "WORLD_NOT_READY: world is ${world.state}"
        }
        if (world.owner != owner) {
            return "WORLD_NOT_READY: world is not owned by this session"
        }
        return null
    }

    /**
     * DESK-122: the Android `setWorldPaused(1)` op — the world pause
     * primitive (WorldNative.pauseWorldNative) with the "pause" lifecycle
     * record and an honest rc result, under the transition lock. Note the
     * user-facing Pause/Resume rides the supervisor's companion mode (the
     * shared RUNNING→PAUSED phase machine, DESK-117); this is the world
     * primitive lane for the phases where the companion transition does
     * not apply (world up, phase not RUNNING/PAUSED).
     */
    suspend fun pauseWorld(owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                liveWorldRefusal(owner)?.let { return@withContext RuntimeActionResult(false, it) }
                val rc = runCatching { WorldNative.pauseWorldNative(1) }.getOrDefault(-1)
                files.writeLifecycle("world", false, "pause", ServerRuntimeContract.errorName(rc.toLong()))
                RuntimeActionResult(
                    rc == 0,
                    "world pause rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})",
                )
            }
        }

    /** DESK-122: the "resume" leg of the pause primitive (setWorldPaused(0)). */
    suspend fun resumeWorld(owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                liveWorldRefusal(owner)?.let { return@withContext RuntimeActionResult(false, it) }
                val rc = runCatching { WorldNative.pauseWorldNative(0) }.getOrDefault(-1)
                files.writeLifecycle("world", false, "resume", ServerRuntimeContract.errorName(rc.toLong()))
                RuntimeActionResult(
                    rc == 0,
                    "world resume rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})",
                )
            }
        }

    /**
     * DESK-116/123: the Android `setBotTarget(target)` op — a direct bot
     * target retarget on a running world, refused while a measured
     * admission profile owns the target (the Android setBotTarget check,
     * WorldRuntimeService.kt:190-193): use [startBotProfile] for those.
     */
    suspend fun setBotTarget(owner: ComponentOwner, target: Int): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                liveWorldRefusal(owner)?.let { return@withContext RuntimeActionResult(false, it) }
                if (botAdmission.active()) {
                    return@withContext RuntimeActionResult(
                        false,
                        "bot admission owns the target while a measured profile is active",
                    )
                }
                val rc = runCatching { WorldNative.setBotTargetNative(target) }.getOrDefault(-1)
                files.writeLifecycle(
                    "world",
                    false,
                    "set-bot-target",
                    "target=$target ${ServerRuntimeContract.errorName(rc.toLong())}",
                )
                RuntimeActionResult(
                    rc == 0,
                    "set-bot-target target=$target rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})",
                )
            }
        }

    /**
     * DESK-116/123: the Android `startBotProfile(profileId)` op, desktop
     * form — a running world's bot population re-targeted mid-session
     * through the RpHost-proven admission-generation handshake (retire the
     * old measured generation, register a fresh one, set the profile's
     * launch target under it, restart the observe loop), with the
     * persisted admission-lowered initial target applied exactly like the
     * next boot's conf would. Honest refusal when the world is not
     * READY/owned or the profile id is unknown.
     */
    suspend fun startBotProfile(owner: ComponentOwner, profileId: String): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                liveWorldRefusal(owner)?.let { return@withContext RuntimeActionResult(false, it) }
                val profile = runCatching { com.pocketrealm.bots.BotProfiles.require(profileId) }
                    .getOrElse { failure ->
                        return@withContext RuntimeActionResult(
                            false,
                            "UNKNOWN_PROFILE: ${failure.message}",
                        )
                    }
                val launchProfile = botAdmission.persistedLaunchProfile(profile)
                val rc = runCatching { botAdmission.retarget(launchProfile) }.getOrElse { failure ->
                    return@withContext RuntimeActionResult(
                        false,
                        "START-BOT-PROFILE-FAILED: ${failure.message}",
                    )
                }
                files.writeLifecycle(
                    "world",
                    false,
                    "start-bot-profile",
                    "${launchProfile.id}@${launchProfile.initialTarget} " +
                        ServerRuntimeContract.errorName(rc.toLong()),
                )
                RuntimeActionResult(
                    rc == 0,
                    "start-bot-profile ${launchProfile.id} " +
                        "target=${launchProfile.initialTarget} rc=$rc " +
                        "(${ServerRuntimeContract.errorName(rc.toLong())})",
                )
            }
        }

    /**
     * DESK-77: the characterPersistence op — one character's persistence
     * facts from the live world. The identity tokens validate first (the
     * Android op's require order), then the native answers its own JSON.
     */
    suspend fun characterPersistence(
        owner: ComponentOwner,
        username: String,
        characterName: String,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        ServerRuntimeContract.requireAccountToken("username", username)
        ServerRuntimeContract.requireCharacterName(characterName)
        worldControl(owner, "character-persistence") {
            org.json.JSONObject(WorldNative.characterPersistenceNative(username, characterName))
                .put("schema", 1).put("ok", true).put("component", "world")
        }
    }

    /**
     * DESK-77: the worldChat op — one chat line injected through the real
     * chat opcode handler. The native owns validation and answers with its
     * own ok verdict (the Android op's documented contract), so only the
     * world guard runs here.
     */
    suspend fun worldChat(
        owner: ComponentOwner,
        characterName: String,
        channel: String,
        target: String,
        text: String,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        synchronized(transitionLock) {
            worldControl(owner, "world-chat") {
                org.json.JSONObject(
                    WorldNative.worldChatNative(
                        characterName, channel, target, text,
                        ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                    ),
                ).put("schema", 1).put("component", "world")
            }
        }
    }

    /** DESK-77: the resetState op — clear one player's LLM assertion memory. */
    suspend fun resetState(owner: ComponentOwner, player: String): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                worldControl(owner, "reset-state") {
                    org.json.JSONObject(WorldNative.resetStateNative(player))
                        .put("schema", 1).put("component", "world")
                }
            }
        }

    /** DESK-77: the llmMemoryState op — one player's LLM memory state. */
    suspend fun llmMemoryState(owner: ComponentOwner, player: String): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                worldControl(owner, "llm-memory-state") {
                    org.json.JSONObject(WorldNative.llmMemoryStateNative(player))
                        .put("schema", 1).put("component", "world")
                }
            }
        }

    /** The shared shape of the DESK-77 ops: world guard, native call, the
     * JSON verdict surfaces as the result (ok = the payload's own "ok"
     * when it carries one). */
    private fun worldControl(
        owner: ComponentOwner,
        operation: String,
        control: () -> org.json.JSONObject,
    ): RuntimeActionResult {
        liveWorldRefusal(owner)?.let { return RuntimeActionResult(false, it) }
        val value = runCatching { control() }.getOrElse { failure ->
            return RuntimeActionResult(
                false,
                "$operation-failed: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        return RuntimeActionResult(ok = value.optBoolean("ok", true), detail = value.toString())
    }

    @Suppress("TooGenericExceptionCaught") // the service seam converts any native failure to an honest result
    override suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY || world.owner != owner) {
            return@withContext AccountProvisionResult(ok = false, code = "WORLD_NOT_READY")
        }
        try {
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            when {
                rc == ERR_ACCOUNT_EXISTS -> {
                    // Never adopt or mutate an existing identity before
                    // proving its password (the Android contract).
                    if (!WorldNative.verifyAccountPasswordNative(username, password)) {
                        AccountProvisionResult(ok = false, code = "ACCOUNT_PASSWORD_MISMATCH")
                    } else {
                        val info = WorldNative.accountInfoNative(username)
                        // R1-arch-2 retry leg: this branch is the host-retry
                        // path — an earlier attempt may have created the row
                        // and died before (or during) the F1-10 expansion
                        // write. Ensure the addon level before answering, so
                        // a retried provisioning always converges to the
                        // active flavor's expansion (never a silently
                        // classic friend on a tbc/wotlk host).
                        // R1-r2-1: the BOOT lane's expansion — a Home switch
                        // after world start must not re-lane the write onto
                        // another flavor's world database.
                        val expansion = (bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion
                        val addon = WorldNative.setAccountAddonNative(
                            username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                        )
                        if (addon != 0) {
                            AccountProvisionResult(
                                ok = false,
                                code = ServerRuntimeContract.errorName(addon.toLong()),
                            )
                        } else {
                            AccountProvisionResult(
                                ok = true,
                                code = "ACCOUNT_VERIFIED",
                                accountId = accountInfoId(info),
                                gmLevel = accountInfoGmLevel(info),
                            )
                        }
                    }
                }
                rc != 0 -> AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(rc.toLong()))
                else -> {
                    // F1-10 (lanes item 12): the fresh row is born with the
                    // seed's expansion default (0) and the core gates
                    // expansion content per account row, so the ACTIVE
                    // flavor's level is written before first login (vanilla
                    // stays 0) — resolved through the same serverFlavor()
                    // gate every per-flavor identity on this backend uses,
                    // and fail-loud like the gmlevel leg below it.
                    // R1-r2-1: the BOOT lane's expansion, never the live machine.
                    val expansion = (bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion
                    val addon = WorldNative.setAccountAddonNative(
                        username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                    )
                    if (addon != 0) {
                        return@withContext AccountProvisionResult(
                            ok = false,
                            code = ServerRuntimeContract.errorName(addon.toLong()),
                            detail = "EXPANSION-SET-FAILED",
                        )
                    }
                    if (gmLevel != 0) {
                        val gm = WorldNative.setAccountGmLevelNative(
                            username, gmLevel, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                        )
                        if (gm != 0) {
                            return@withContext AccountProvisionResult(
                                ok = false,
                                code = ServerRuntimeContract.errorName(gm.toLong()),
                            )
                        }
                    }
                    val info = WorldNative.accountInfoNative(username)
                    AccountProvisionResult(
                        ok = true,
                        code = "ACCOUNT_CREATED",
                        accountId = accountInfoId(info),
                        gmLevel = if (gmLevel != 0) gmLevel else accountInfoGmLevel(info),
                    )
                }
            }
        } catch (failure: Throwable) {
            AccountProvisionResult(
                ok = false,
                code = "ACCOUNT_REJECTED",
                detail = "PROVISION-FAILED: ${failure.message}",
            )
        }
    }

    /**
     * The READ-ONLY credential proof (the Android verifyAccountPassword
     * contract — the join-friend lifecycle's parity seam): never creates,
     * never mutates. A credential saved from a friend-join must never mint
     * a local identity, which the old create-if-missing proof leg did on
     * every LOCAL launch with a stored record the world didn't know.
     * Answers: ACCOUNT_VERIFIED (row exists + password proven),
     * ACCOUNT_PASSWORD_MISMATCH (row exists, password refuted),
     * ACCOUNT_UNKNOWN (the world never knew the identity — the honest
     * read-only answer; no row is fabricated).
     */
    override suspend fun verifyAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
    ): AccountProvisionResult = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY || world.owner != owner) {
            return@withContext AccountProvisionResult(ok = false, code = "WORLD_NOT_READY")
        }
        try {
            val info = WorldNative.accountInfoNative(username)
            val exists = info.size >= 2 && info[0] > 0
            when {
                !exists -> AccountProvisionResult(ok = false, code = "ACCOUNT_UNKNOWN")
                !WorldNative.verifyAccountPasswordNative(username, password) ->
                    AccountProvisionResult(ok = false, code = "ACCOUNT_PASSWORD_MISMATCH")
                else -> AccountProvisionResult(
                    ok = true,
                    code = "ACCOUNT_VERIFIED",
                    accountId = accountInfoId(info),
                    gmLevel = accountInfoGmLevel(info),
                )
            }
        } catch (failure: Throwable) {
            AccountProvisionResult(
                ok = false,
                code = "ACCOUNT_REJECTED",
                detail = "VERIFY-FAILED: ${failure.message}",
            )
        }
    }

    // ------------------------------------------------------------------
    // Remote friend accounts (design-remote-accounts §3/§8/§9/§11): the
    // host-side verbs the Players surface and the approval flow ride. All
    // guard on the world being READY — the LoginDatabase query pools only
    // exist between StartDatabasesEmbedded and teardown (the account_info
    // tombstone_02 guard) — and convert any native failure to an honest
    // refusal, never a crash into the UI path.
    // ------------------------------------------------------------------

    /**
     * The verifier provisioning leg of friend approval (§3): inserts the
     * friend-derived (s, v) verbatim — mirroring AccountMgr::CreateAccount's
     * write WITHOUT a password — so the password never crosses the host
     * process. Name collisions return ACCOUNT_EXISTS and the HOST rejects
     * the request (the friend picks another name); no verify-and-adopt
     * shortcut exists for the verifier path because the host cannot even
     * form a password to verify (§3.5).
     */
    @Suppress("TooGenericExceptionCaught") // any native failure is an honest refusal, never a crash
    override suspend fun provisionAccountVerifier(
        username: String,
        sHex: String,
        vHex: String,
        allowExistingAccount: Boolean,
    ): AccountProvisionResult = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY) {
            return@withContext AccountProvisionResult(ok = false, code = "WORLD_NOT_READY")
        }
        try {
            val verdict = WorldNative.createAccountVerifierNative(username, sHex, vHex)
            val rc = verdict.getOrElse(0) { Long.MAX_VALUE }
            val accountId = verdict.getOrElse(1) { 0L }
            when {
                rc == ERR_ACCOUNT_EXISTS.toLong() -> {
                    // R1-arch-2 (android-core review round 1, serious): the
                    // designed HOST RETRY LANE (approvalFailed → the row
                    // returns to SUBMITTED → the host retries) re-enters
                    // here with ACCOUNT_EXISTS when an earlier attempt
                    // created the row but failed a later leg; the retry's
                    // authorization is the host's attempt bookkeeping
                    // (allowExistingAccount — R2-1, review round 2). A FIRST
                    // approval landing on an existing account is an identity
                    // COLLISION: refused with no addon write on the existing
                    // row (the §3.5 contract — the host rejects, the friend
                    // picks another name).
                    if (!allowExistingAccount) {
                        AccountProvisionResult(ok = false, code = "ACCOUNT_EXISTS")
                    } else {
                    val exists = (WorldNative.accountInfoNative(username)
                        .getOrNull(0) ?: 0L) > 0L
                    if (!exists) {
                        AccountProvisionResult(ok = false, code = "ACCOUNT_EXISTS")
                    } else {
                        // R1-r2-1: the BOOT lane's expansion — a Home switch
                        // after world start must not re-lane the write onto
                        // another flavor's world database.
                        val expansion = (bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion
                        val addon = WorldNative.setAccountAddonNative(
                            username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                        )
                        if (addon != 0) {
                            AccountProvisionResult(
                                ok = false,
                                code = ServerRuntimeContract.errorName(addon.toLong()),
                            )
                        } else {
                            AccountProvisionResult(
                                ok = true,
                                code = "ACCOUNT_CREATED",
                                accountId = accountId,
                                gmLevel = 0, // friends provision at gmlevel 0; the host raises it explicitly (§10)
                            )
                        }
                    }
                    }
                }
                rc != 0L -> AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(rc))
                else -> {
                    // F1-10 parity (R1-arch-2): the fresh row is born at the
                    // seed's expansion default (0) exactly like the Android
                    // seam — the ACTIVE flavor's level is written before
                    // first login, fail-loud on refusal (never a silently
                    // classic provisioned friend).
                    // R1-r2-1: the BOOT lane's expansion, never the live machine.
                    val expansion = (bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion
                    val addon = WorldNative.setAccountAddonNative(
                        username, expansion, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                    )
                    if (addon != 0) {
                        AccountProvisionResult(
                            ok = false,
                            code = ServerRuntimeContract.errorName(addon.toLong()),
                        )
                    } else {
                        AccountProvisionResult(
                            ok = true,
                            code = "ACCOUNT_CREATED",
                            accountId = accountId,
                            gmLevel = 0, // friends provision at gmlevel 0; the host raises it explicitly (§10)
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            AccountProvisionResult(
                ok = false,
                code = "ACCOUNT_REJECTED",
                detail = "VERIFIER-PROVISION-FAILED: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    /** The Players table read (§11): every account with its ban state, the
     * live failed-logins counter, and the last SUCCESSFUL logon. */
    override suspend fun accountRoster(): RosterRead = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY) return@withContext RosterRead("world-not-ready")
        val (reason, accounts) = FriendRosterJson.parseAccounts(
            runCatching { WorldNative.accountListNative() }.getOrNull(),
        )
        RosterRead(reason, accounts)
    }

    /** One friend's characters (§8). */
    override suspend fun accountCharacters(username: String): CharactersRead = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY) return@withContext CharactersRead("world-not-ready")
        val (reason, characters) = FriendRosterJson.parseCharacters(
            runCatching { WorldNative.accountCharactersNative(username) }.getOrNull(),
        )
        CharactersRead(reason, characters)
    }

    /** The shared moderation surface (§9): `ban account <name> <duration>
     * <reason>` through the SEC_CONSOLE relay. Duration presets arrive in
     * TimeStringToSecs shape ("1h"/"1d"; "0" = permanent). */
    override suspend fun banFriendAccount(username: String, duration: String, reason: String): ModerationResult =
        withContext(Dispatchers.IO) {
            val world = observeWorld()
            if (world.state != ComponentLifecycle.READY) return@withContext ModerationResult(false, "world-not-ready")
            val (ok, detail) = FriendRosterJson.parseBanVerdict(
                runCatching {
                    WorldNative.banAccountNative(username, duration, reason, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
                }.getOrNull(),
            )
            ModerationResult(ok, detail)
        }

    /** Revoke (unban) (§9): `unban account <name>`. */
    override suspend fun unbanFriendAccount(username: String): ModerationResult = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY) return@withContext ModerationResult(false, "world-not-ready")
        val (ok, detail) = FriendRosterJson.parseBanVerdict(
            runCatching {
                WorldNative.unbanAccountNative(username, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            }.getOrNull(),
        )
        ModerationResult(ok, detail)
    }

    /** Per-friend gmlevel 0..3 (§10) through the existing set_account_gmlevel. */
    override suspend fun setFriendGmLevel(username: String, level: Int): Boolean = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY) return@withContext false
        if (level !in 0..3) return@withContext false
        runCatching {
            WorldNative.setAccountGmLevelNative(username, level, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        }.getOrDefault(-1) == 0
    }

    /**
     * The desktop engine-twin recovery (the Android
     * recoverDatabase/prepareDatabaseForStart flow): by the time the
     * supervisor calls this it has already force-stopped and released
     * every component, so recovery is the prepare-for-start leg — the
     * runtime at rest, the datadir seeded, every WAL sidecar drained
     * through SQLite's own recovery + checkpoint, every database through
     * the integrity gate (VACUUM INTO rebuild on failure), and an at-rest
     * proof before success. Never fake success: the supervisor commits
     * clean=true on ok and the next start skips recovery entirely.
     */
    override suspend fun recoverDatabase(): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            synchronized(transitionLock) {
                if (selectedDatabaseProvider() == Settings.DatabaseProvider.MARIADB) {
                    // Restore-aware BEFORE the dirty-generation admission:
                    // the supervisor's dirty-journal lane reaches THIS verb
                    // before any preflight arm (recoverDatabaseAfterComponents
                    // runs before the start's ROLLBACK_PENDING_RESTORE arm),
                    // so a crash mid-restore-proof — a durable restore record
                    // over the CLEAN-sealed candidate beginRestore restamped —
                    // must be settled here first; the ordinary recover() below
                    // refuses on that clean seal ("recovery requested for a
                    // clean generation"), wedging every later start until the
                    // journal was fixed by hand.
                    if (mariaDbLane().status().optBoolean("restorePending")) {
                        val settled = mariaDbLane().rollbackPendingRestore()
                        if (!settled.optBoolean("ok")) {
                            return@synchronized RuntimeActionResult(
                                false,
                                "$CODE_DB_RESTORE_PENDING-FAILED: the interrupted-restore " +
                                    "recovery refused: ${settled.optString("errorClass")}: " +
                                    "${settled.optString("error")}",
                            )
                        }
                        // Both settle legs end clean-stopped by construction
                        // (the rollback re-stamps the clean seal, the finished
                        // commit requires it); re-prove it anyway and keep
                        // the ordinary recover() for a generation still dirty.
                        if (mariaDbLane().status().optBoolean("cleanMarker")) {
                            return@synchronized RuntimeActionResult(
                                ok = true,
                                detail = "$CODE_DB_RESTORE_PENDING: interrupted restore " + when {
                                    !settled.optBoolean("pending") -> "already retired"
                                    settled.optBoolean("committed") -> "commit finished"
                                    else -> "rolled back"
                                } + "; generation clean-stopped, no dirty recovery needed",
                            )
                        }
                    }
                    // The MARIADB lane's recovery twin: the service's
                    // guarded recover (the engine's durable ledger
                    // re-proof — the leg the SQLite lane's
                    // recoverDatabaseLocked re-proves its own way).
                    val result = mariaDbLane().recover()
                    RuntimeActionResult(
                        ok = result.optBoolean("ok"),
                        detail = if (result.optBoolean("ok")) {
                            "mariadb provider recovered; ledger re-proved"
                        } else {
                            "${result.optString("errorClass")}: ${result.optString("error")}"
                        },
                    )
                } else {
                    recoverDatabaseLocked()
                }
            }
        }

    // The honest-failure returns ARE the algorithm; the guards mirror the
    // Android twin's step order and refusal copy.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun recoverDatabaseLocked(): RuntimeActionResult {
        // Generation-boundary guard (the desktop analog of the Android
        // "unowned database is still running" refusal): a live runtime or
        // a held DATABASE claim means a connection survives.
        runtimesAtRestRefusal()?.let { return RuntimeActionResult(false, it) }
        // Seeded gate (the INITIALIZE leg's admission; recovery must not
        // silently seed — the desktop initialization lane is the explicit
        // seeder task with its staging + provenance pins).
        val missing = DATABASES.filterNot { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        if (missing.isNotEmpty()) {
            return RuntimeActionResult(
                false,
                "DB-NOT-SEEDED: " + missing.joinToString() + " missing under " +
                    roots.sqliteDatadir + " (run gradlew seedRealmData from desktop/)",
            )
        }
        // Seal re-proof BEFORE the drain/integrity legs (DESK-79): a
        // tampered or stale generation refuses as itself — it must never
        // masquerade as a repairable dirty one. A dirty CLEAN seal is
        // exactly what recovery owns (the clean-stop seal is re-stamped
        // below); every other unprovable state refuses.
        val admission = preparation.evaluate()
        if (!admission.ready && admission.code != CODE_DB_DIRTY_GENERATION) {
            return RuntimeActionResult(false, "${admission.code}: ${admission.detail}")
        }
        // WAL recovery + checkpoint drain: opening each database read-write
        // triggers SQLite's own WAL recovery; checkpoint-on-close deletes
        // the sidecars. A sidecar that survives means a live connection.
        val deadline = System.currentTimeMillis() + WAL_DRAIN_TIMEOUT_MS
        var sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir).filter { it.isFile }
        while (sidecars.isNotEmpty()) {
            if (System.currentTimeMillis() >= deadline) {
                return RuntimeActionResult(
                    false,
                    "DB-SQLITE: WAL sidecars survived " + WAL_DRAIN_TIMEOUT_MS + "ms of engine " +
                        "checkpoints - a realm/world connection is still open: " +
                        sidecars.joinToString(" ") { it.name },
                )
            }
            for (database in DATABASES) {
                val file = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                if (!file.isFile) continue
                runCatching {
                    DesktopSqliteConnection(file.absolutePath).use { connection ->
                        DatabaseSqliteConfigPolicy.renderConnectionPragmas()
                            .forEach(connection::queryText)
                        connection.queryText("PRAGMA wal_checkpoint(TRUNCATE);")
                    }
                }
            }
            Thread.sleep(WAL_DRAIN_POLL_MS)
            sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir).filter { it.isFile }
        }
        // Integrity gate + VACUUM INTO rebuild (the sqliteRecover core).
        val gate = DATABASES.associateWith { database ->
            verifyOrRebuildSqlite(DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database))
        }
        // At-rest proof: no sidecar survives recovery (the desktop's
        // clean-stop seal — existence is ours, walSidecars lists candidates).
        val survivors = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir).filter { it.isFile }
        if (survivors.isNotEmpty()) {
            return RuntimeActionResult(
                false,
                "DB-SQLITE: WAL sidecars survived recovery: " +
                    survivors.joinToString(" ") { it.name },
            )
        }
        val rebuiltAny = gate.values.any { it == "rebuilt" }
        // The real revision re-proof leg (replaces the "arrives with the
        // engine twin" stub, DESK-79/98): the corpus identity pins the
        // TRANSCRIPTS, not the datadir bytes, so a VACUUM INTO rebuild
        // never invalidates the seal — but the proof is re-run here, and
        // a rebuild that DID leave the record mismatched fails recovery
        // instead of sailing through. The at-rest datadir then ends
        // clean-stopped with a freshly stamped seal (Android writes
        // clean LAST).
        val postRecord = DesktopSqliteSeals.revisionRecord(
            readMarker(DesktopSqliteSealMarkers.generationMarkerFile(roots.sqliteDatadir)),
        )
        if (postRecord == null || !DesktopSqliteSeals.sealMatchesRecord(
                readMarker(DesktopSqliteSealMarkers.migrationMarkerFile(roots.sqliteDatadir)),
                postRecord,
            )
        ) {
            return RuntimeActionResult(
                false,
                "$CODE_DB_MIGRATION_PENDING: post-recovery re-proof failed (migration seal does " +
                    "not match the generation marker's record); re-seed the datadir",
            )
        }
        DesktopSqliteSealMarkers.restampCleanSeal(roots.sqliteDatadir)
        return RuntimeActionResult(
            ok = true,
            detail = "database transactions recovered and stopped generation prepared " +
                "(integrityGate: " + gate.entries.joinToString { (key, value) -> "$key=$value" } + ")" +
                "; revision re-proof ok (migration seal current)" +
                (if (rebuiltAny) "; rebuilt databases re-proved" else "") +
                "; clean-stop seal re-stamped",
        )
    }

    /**
     * The runtimes-at-rest guard shared by every datadir-mutating verb
     * (recovery AND the DESK-6/18/37/100 snapshot legs): realm/world must be
     * STOPPED-or-FAILED and no live DATABASE claim may exist — the exact
     * recoverDatabaseLocked guards, extracted so the snapshot verbs gate on
     * the same proof. Null = at rest; non-null = the honest refusal copy.
     */
    private fun runtimesAtRestRefusal(): String? {
        val realmState = currentRealmState()
        val worldState = currentWorldState()
        if (realmState != ServerRuntimeContract.STOPPED && realmState != ServerRuntimeContract.FAILED) {
            return "realm is still " + ServerRuntimeContract.stateName(realmState) +
                "; recovery requires the runtime at rest"
        }
        if (worldState != ServerRuntimeContract.STOPPED && worldState != ServerRuntimeContract.FAILED) {
            return "world is still " + ServerRuntimeContract.stateName(worldState) +
                "; recovery requires the runtime at rest"
        }
        if (ownership.getValue(RuntimeComponent.DATABASE).current() != null) {
            return "database is still claimed by a runtime session"
        }
        return null
    }

    /** quick_check then integrity_check; both verdicts must be exactly "ok". */
    @Suppress("ReturnCount") // the two early verdicts are the gate's shape
    private fun integrityOk(file: File): Boolean {
        if (!file.isFile) return false
        DesktopSqliteConnection(file.absolutePath).use { connection ->
            DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach(connection::queryText)
            val quick = connection.queryText(DatabaseSqliteControlPlane.QUICK_CHECK)
            if (quick != DatabaseSqliteControlPlane.INTEGRITY_OK) return false
            val full = connection.queryText(DatabaseSqliteControlPlane.INTEGRITY_CHECK)
            return full == DatabaseSqliteControlPlane.INTEGRITY_OK
        }
    }

    /** The Android verifyOrRebuildSqlite twin: integrity-verifies, and on
     * failure salvages through VACUUM INTO into a WAL-mode replacement. */
    private fun verifyOrRebuildSqlite(file: File): String {
        if (integrityOk(file)) return "ok"
        val rebuilt = File(file.parentFile, file.name + ".rebuilt")
        rebuilt.delete()
        val target = rebuilt.absolutePath.replace("'", "''")
        DesktopSqliteConnection(file.absolutePath).use { connection ->
            DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach(connection::queryText)
            connection.exec("VACUUM INTO '$target';")
        }
        if (!integrityOk(rebuilt)) {
            rebuilt.delete()
            error(
                "DB-SQLITE: " + file.name + " failed integrity_check and the " +
                    "VACUUM INTO rebuild is also not clean",
            )
        }
        // VACUUM INTO output carries a DELETE journal, not WAL - restore
        // WAL before publishing.
        DesktopSqliteConnection(rebuilt.absolutePath).use { connection ->
            check(connection.queryText("PRAGMA journal_mode=WAL;") == "wal") {
                "DB-SQLITE: " + rebuilt.name + " did not restore journal_mode=WAL"
            }
        }
        check(!File(rebuilt.parentFile, rebuilt.name + "-wal").isFile &&
            !File(rebuilt.parentFile, rebuilt.name + "-shm").isFile) {
            "DB-SQLITE: " + rebuilt.name + " left WAL sidecars behind"
        }
        check(rebuilt.renameTo(file) || (file.delete() && rebuilt.renameTo(file))) {
            "DB-SQLITE: cannot replace " + file.name + " with the rebuilt copy"
        }
        // No directory fsync: Windows cannot open a directory through
        // FileChannel and NTFS journals the rename (the seeder's posture).
        return "rebuilt"
    }

    override fun close() {
        // Supervisor stop order: client first while the world is still up,
        // then world, then realm. DATABASE is stateless on the SQLite lane
        // (no daemon; its clean-stop seal is stopDatabase's sidecar drain,
        // which the supervisor issues separately); the MariaDB lane's
        // daemon service and the embedded-LLM sidecar stop here when this
        // session ever routed through them.
        runCatching { stopClient() }
        synchronized(transitionLock) {
            // The admission monitor retires with the process (best-effort).
            runCatching { botAdmission.stop() }
            runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
            runCatching { worldDataLease?.close() }
            worldDataLease = null
            runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
            files.stopLlmSidecar()
            runCatching { mariaDbService?.close() }
            mariaDbService = null
        }
    }

    // ---------------- client component ----------------

    /**
     * DESK-114 client observation: the self-spawned process is observed
     * directly; any OTHER WoW.exe under the configured client directory (a
     * user-launched client, or a survivor of a crashed previous app
     * instance) is observed as a live client whose owner is the ownership
     * slot — null while un-adopted, so a found-but-unowned process reports
     * an ownerless RUNNING observation and the shared supervisor's
     * adoption/orphan semantics engage instead of the client staying
     * invisible while it plays against a stack the app is about to stop.
     */
    private fun observeClient(): ComponentObservation {
        journalClientExitIfDead()
        val owner = ownership.getValue(RuntimeComponent.CLIENT).current()
        val owned = client?.takeIf { it.isAlive }
        val external = if (owned == null) findClientProcess() else null
        return when {
            owned != null -> ComponentObservation(
                component = RuntimeComponent.CLIENT,
                state = ComponentLifecycle.READY,
                ready = true,
                owner = owner,
                pid = owned.pid().toInt(),
                detail = "WoW.exe running (pid ${owned.pid()})",
            )
            external != null -> ComponentObservation(
                component = RuntimeComponent.CLIENT,
                state = ComponentLifecycle.READY,
                ready = true,
                owner = owner,
                pid = external.pid,
                detail = "WoW.exe running (pid ${external.pid}) outside this session's launch",
            )
            else -> ComponentObservation(
                component = RuntimeComponent.CLIENT,
                state = ComponentLifecycle.STOPPED,
                ready = false,
                owner = owner,
                pid = null,
                detail = "client not running",
            )
        }
    }

    /** The client pid the foreground verbs act on: the self-spawned
     * process, else the scan hit under the client directory; null when no
     * client is observable. */
    private fun observedClientPid(): Int? {
        client?.takeIf { it.isAlive }?.let { return it.pid().toInt() }
        return findClientProcess()?.pid
    }

    /**
     * DESK-112: the self-spawned client died WITHOUT a stop (a crash, or
     * the user closing WoW.exe themselves) — capture its exit code into
     * the durable client lifecycle record on the first observation that
     * sees the death. A code 0 death is still recorded (clean=true); the
     * abnormal ones are the crash-triage evidence. The stop path sets
     * [drainingClient] so its own kills never journal as abnormal deaths.
     */
    private fun journalClientExitIfDead() {
        synchronized(clientExitLock) {
            // The guard INSIDE the lock (the stop path sets the flag under
            // this same lock before killing): a pre-lock check would let an
            // observer that raced the set read the just-killed process and
            // journal a stop as an abnormal client death — DESK-112's
            // stated non-goal.
            if (drainingClient) return
            val exited = client?.takeIf { !it.isAlive }
            if (exited != null) journalClientExit(exited)
        }
    }

    /** One exit record: pid + exit code, clean iff zero (the abnormal
     * ones are the crash-triage evidence). Consumes the dead handle. */
    private fun journalClientExit(exited: Process) {
        val pid = exited.pid().toInt()
        val exitCode = runCatching { exited.exitValue() }.getOrDefault(-1)
        val clean = exitCode == 0
        files.writeClientExit(
            pid = pid,
            exitCode = exitCode,
            clean = clean,
            detail = if (clean) "client exited" else "client died abnormally (exit code $exitCode)",
        )
        if (clean) {
            DesktopLog.i(CLIENT_LOG_TAG, "WoW.exe exited (pid $pid, code 0)")
        } else {
            DesktopLog.w(CLIENT_LOG_TAG, "WoW.exe died abnormally (pid $pid, exit code $exitCode)")
        }
        client = null
    }

    /** DESK-112: the durable last-client-exit record (null before the
     * first journalled client death this storage root has seen) — the
     * Diagnostics "last client exit" line and a support-bundle member. */
    fun lastClientExit(): org.json.JSONObject? = files.readClientExit()

    /** DESK-127: the world status telemetry as the shared ABI's status
     * JSON (heartbeatMs/tickCount/lastTickMs/maxTickMs/activeSessions);
     * null when the native lane is not staged — never a throw into the
     * UI poll path. */
    fun worldTelemetry(): org.json.JSONObject? = runCatching {
        val lane = bootLane?.flavorId ?: "vanilla"
        ServerStatusJson.world(
            WorldNative.statusNative(), WorldNative.detailNative(),
            runtimeBuildId = BuildConfig.nativeRuntimeBuildIdFor(lane),
            nativeCmangosCommit = BuildConfig.nativeCmangosCommitFor(lane),
        )
    }.getOrNull()

    /** DESK-127: the realm status telemetry (heartbeatCount et al). */
    fun realmTelemetry(): org.json.JSONObject? = runCatching {
        val lane = bootLane?.flavorId ?: "vanilla"
        ServerStatusJson.realm(
            RealmNative.statusNative(), RealmNative.detailNative(),
            runtimeBuildId = BuildConfig.nativeRuntimeBuildIdFor(lane),
            nativeCmangosCommit = BuildConfig.nativeCmangosCommitFor(lane),
        )
    }.getOrNull()

    /**
     * Cheap public liveness probe for the in-game settings editor (DESK-41/96:
     * direct edit while stopped vs queue-while-running) and the gamepad
     * bridge's foreground gate (DESK-4). Same scan observe(CLIENT) uses, at
     * UI frequency — not on the supervisor's serialization path.
     */
    fun clientAlive(): Boolean = observedClientPid() != null

    /** The observable client pid, or null (the gamepad bridge re-probes at
     * ~1 Hz and only injects while this pid owns the OS foreground). */
    fun clientPid(): Int? = observedClientPid()

    /** The first WoW.exe found under the resolved client directory, or
     * null (no directory configured means nothing can be attributed).
     * Attribution is existence-based on purpose: a user-launched client is
     * observed and drained regardless of whether its identity would pass
     * the launch gate (DESK-114's posture). */
    private fun findClientProcess(): ClientProcessScan? {
        val dir = clientDir
            ?: runCatching { resolveManagedClient()?.root }.getOrNull()
            ?: legacyClientDirCandidate()
        val target = dir?.let { resolved ->
            runCatching { File(resolved.absolutePath).canonicalFile.toPath() }.getOrNull()
        }
        val rows = if (target == null) {
            emptyList()
        } else {
            runCatching { clientProcessScanner.scan() }.getOrDefault(emptyList())
        }
        return rows.firstOrNull { scan ->
            val exe = scan.exePath
            exe != null && runCatching {
                File(exe).canonicalFile.toPath().startsWith(target)
            }.getOrDefault(false)
        }
    }

    /** Launch WoW.exe natively in its own directory. The directory resolves
     * per launch (DESK-67/97): constructor override (LaunchClient's
     * -DclientDir) → the ACTIVE MANAGED GENERATION through the fail-closed
     * reader → the stored client folder (the Home screen's picker / import)
     * → the conventional default, with the DESK-21/36/52/68 identity gate
     * refusing a wrong-build folder BEFORE any client-directory side effect.
     * After the gates the launch boundary applies, in order: the addon
     * projection (DESK-11/40/54), the vanilla-tweaks patch lane + Config.wtf
     * overlay + session-aware realmlist projection (DESK-27/58/89, DESK-50,
     * DESK-51), the spawn, and the per-launch audio mode (DESK-139). */
    @Suppress("TooGenericExceptionCaught") // any launch failure surfaces as an honest throw
    private fun startClient(endpoint: RealmEndpoint): ComponentObservation = synchronized(transitionLock) {
        val resolution = resolveClientForLaunch()
        val dir = resolution.dir
        val pristine = File(dir, "WoW.exe")
        // LVF-flavor-import-003: the pristine-existence refusal belongs to the
        // MANAGED lane only — its generation was already validated fail-closed
        // by the store reader, so a missing WoW.exe there is broken internal
        // state this check reports honestly. The folder lane deliberately does
        // NOT check existence here: a client-less folder is exactly the DESK-21
        // scanner gate's VAL-01 case below, and running this check first made
        // the pinned "VAL-01: direct WoW.exe is absent" refusal unreachable
        // dead code at the launch boundary (a bare check crash preempted it).
        if (resolution is ClientResolution.Managed) {
            check(pristine.isFile) { "WoW.exe not found under $dir" }
        }
        // Refuse BEFORE projecting the realmlist: a refused launch must
        // have no side effects on the client directory. The guard reads the
        // same scan observe() does (DESK-114): a user-launched WoW.exe must
        // not get a duplicate twin launched over it.
        client?.let { if (it.isAlive) error("client already running (pid ${it.pid()})") }
        findClientProcess()?.let {
            error("client already running (pid ${it.pid}; launched outside this session)")
        }
        // DESK-21/36/52/68 identity gate — still before any side effect, and
        // AFTER the duplicate-launch precondition so that refusal keeps its
        // pinned copy. The managed lane was already verified fail-closed by
        // the store reader; the folder lane runs the full VAL scan here, which
        // also owns the missing-WoW.exe refusal (VAL-01) for a client-less
        // folder — the check above must not preempt it.
        if (resolution is ClientResolution.Folder) {
            val scan = DesktopClientScanner().scan(dir)
            if (!scan.supported) {
                error("client identity refused: " + scan.failures.joinToString("; "))
            }
        }
        // DESK-11/40/54/83/84: the launch-boundary addon projection. After
        // every refusal gate (a refused launch must have no side effects on
        // the client directory), BEFORE the spawn: the shared migrator
        // normalizes pre-0.6.0 device data over the client WTF tree, the
        // built-in Android Port folder is re-staged from the bundled assets
        // with its toc re-verified, installed packages are applied, and the
        // input safe-mode setting projects none of them. A projection failure
        // fails the launch closed (the Android contract).
        val snapshot = DesktopSettingsStore(roots.settingsFile).load()
        val inputSafeMode = snapshot.inputSafeMode
        // The projector warm-up (the model's app-start warm-up's launch-path
        // twin): constructing the repository here seeds the fresh-install
        // built-in before the projector below reads its registry — a
        // failure must surface in the log, never pass silently (the
        // documented fresh-install seed purpose).
        runCatching { com.pocketrealm.addons.DesktopAddonRepository.get(roots) }
            .onFailure {
                DesktopLog.w(CLIENT_LOG_TAG, "addon repository warm-up failed: ${it.message}")
            }
        val projectedAddons = com.pocketrealm.addons.DesktopAddonRuntimeProjector(
            File(roots.root, "addons"),
        ).project(dir, inputSafeMode)
        // DESK-27/58/89: the vanilla-tweaks lane publishes `WoW.exe.patched`
        // through the lockfile-pinned staged patcher plus the nearby-loot
        // companion patch, AUTHORIZED_CLIENT_SHA256-gated, and the launch
        // runs that binary; an unpatched request keeps the pristine exe.
        // A patch lane that cannot be honored refuses the launch honestly
        // (the Android prepare contract).
        val patch = DesktopVanillaTweaksPatcher.publishForLaunch(dir, snapshot.tweaks)
        val exe = patch.executable
        // DESK-50: the app-owned Config.wtf overlay, before spawn — the
        // EULA/TOS/intro gates die here so the DESK-138/134 auto-login never
        // types into the gate screens. The audio condition rides the
        // persisted DESK-139 audio mode; the sound-channels condition rides
        // the launch's EFFECTIVE tweak config.
        DesktopConfigWtf.enforceAtLaunch(
            dir,
            DesktopConfigWtf.conditions(
                audioMode = if (snapshot.audioMode == Settings.AudioMode.OFF) "off" else "on",
                endpoint = endpoint,
                effectiveTweaks = patch.effectiveConfig,
            ),
        )
        // DESK-51: the user's realmlist is backed up (first projection of the
        // session) and MERGED — only the `set realmlist` line moves; the
        // original bytes return on the teardown paths.
        DesktopRealmlist.projectForSession(dir, endpoint, roots, realmlistSession)
        launchedClientDir = dir
        // The no-manifest 1.12 client trips Windows' installer-detection
        // heuristic and would come up ELEVATED: UIPI then eats every
        // synthetic input (the app's own auto-login included). RUNASINVOKER
        // disables the heuristic for this launch only.
        val spawned = ProcessBuilder(exe.absolutePath)
            .directory(dir)
            .apply { environment()["__COMPAT_LAYER"] = "RUNASINVOKER" }
            .start()
        client = spawned
        // DESK-139: the persisted audio mode applies per launch. The client's
        // audio session appears once DirectSound initializes, so the mute
        // polls OFF the transition gate (the READY observation never waits)
        // and reports honestly when no session ever appears.
        if (snapshot.audioMode == Settings.AudioMode.OFF) {
            scheduleAudioMute(spawned.pid().toInt())
        }
        ComponentObservation(
            component = RuntimeComponent.CLIENT,
            state = ComponentLifecycle.READY,
            ready = true,
            owner = ownership.getValue(RuntimeComponent.CLIENT).current(),
            pid = spawned.pid().toInt(),
            detail = "${exe.name} launched at ${endpoint.address} " +
                "(${resolution.describe()}; realmlist projected; addons " +
                "${if (inputSafeMode) "safe mode" else "${projectedAddons.size} folder(s) projected"}" +
                if (patch.patched) "; tweaks patched)" else ")",
        )
    }

    /** DESK-139: the per-launch audio mute, polled off the transition gate. */
    private fun scheduleAudioMute(pid: Int) {
        Thread {
            val failure = Win32AudioMute.applyLaunchMode(pid, mute = true, muter = audioMuter)
            if (failure == null) {
                DesktopLog.i(CLIENT_LOG_TAG, "game audio muted (pid $pid)")
            } else {
                DesktopLog.w(CLIENT_LOG_TAG, "game audio not muted (pid $pid): $failure")
            }
        }.apply {
            name = "client-audio-mute"
            isDaemon = true
        }.start()
    }

    /** Where WoW.exe would launch from right now; fails honest when a
     * published generation exists but the fail-closed reader refuses it
     * (never silently downgraded to the raw folder). */
    private fun resolveClientForLaunch(): ClientResolution {
        clientDir?.let { return ClientResolution.Folder(it) }
        val managed = runCatching { resolveManagedClient() }.getOrElse { failure ->
            error("managed client generation refused: ${failure.message}")
        }
        if (managed != null) return ClientResolution.Managed(managed)
        val candidate = legacyClientDirCandidate()
            ?: error("client directory not configured (choose the game folder on Home)")
        return ClientResolution.Folder(candidate)
    }

    /** DESK-67/97: the active managed generation through the fail-closed
     * reader, or null when no per-flavor pointer is published. A pointer
     * whose generation fails validation THROWS (the reader's contract) —
     * launch is refused, never re-pointed at an unverified folder.
     *
     * GAP-3 (owner census UIUX-3): the lane resolves through the
     * [ActiveFlavor] machine — the switcher's selection drives the pointer
     * namespace at the second qualification; the machine's ambiguous-state
     * refusal surfaces through the callers' honest launch refusal copy
     * instead of a bare `.single()` crash. */
    private fun resolveManagedClient(): ManagedClientStore.ManagedClient? {
        val store = ClientGenerationStore(roots)
        val flavor = ActiveFlavor.resolve(MANAGED_CLIENT_LANE)
        store.activeGeneration(flavor) ?: return null
        return ManagedClientStore(roots).load(flavor.clientId)
    }

    /**
     * DESK-52/68/97: the resolved client directory for status and launch —
     * the active managed generation's root first, then the stored-or-default
     * folder WHEN ITS IDENTITY RESOLVES (a WoW.exe whose PE version resource
     * does not match the qualified flavor is refused here, not at spawn).
     * Null when nothing usable resolves (an honest refusal beats launching
     * from a wrong path).
     */
    fun resolveClientDir(): File? {
        clientDir?.let { return it.takeIf { dir -> File(dir, "WoW.exe").isFile } }
        val managed = runCatching { resolveManagedClient() }.getOrNull()
        if (managed != null) return managed.root
        // A PRESENT but broken generation must not silently fall through to
        // the legacy folder: resolveManagedClient threw inside runCatching,
        // so re-derive the difference (pointer exists → fail closed).
        // GAP-3: same ACTIVE-lane resolution as resolveManagedClient — the
        // former `.single()` here sat OUTSIDE the runCatching and crashed
        // the status/preflight paths at the second qualification.
        // W4 flip (R6-2): the render/status path resolves through the
        // machine's canonical displayedId — no persisted pick under the
        // flipped registry is the honest "no client" state (fail closed as
        // no client), never an ambiguous-registry crash.
        val flavor = ActiveFlavor.displayedId?.let { laneId ->
            ClientFlavors.ALL.first { it.flavorId == laneId }
        }
        // no pick yet → no managed generation exists → straight to the
        // legacy folder candidate (the same shape the pre-pick state had
        // under the sole-qualified degrade).
        if (flavor == null || ClientGenerationStore(roots).activeGeneration(flavor) != null) {
            return null
        }
        return legacyClientDirCandidate()?.takeIf { dir -> DesktopClientScanner().scan(dir).supported }
    }

    /** The STORED client folder (the Home picker); null when unset or when
     * it holds no WoW.exe (existence only — identity is applied by the
     * callers). Deliberately NO conventional/dev-box default: the stored
     * folder is the only implicit source (DesktopClientLauncherTest's
     * contract — a conventional default made resolution machine-dependent
     * and launched clients the user never chose; the dev lane passes its
     * own -DclientDir, see LaunchClient). */
    private fun legacyClientDirCandidate(): File? {
        val stored = DesktopSettingsStore(roots.settingsFile).load().clientDir
        return stored.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { File(it, "WoW.exe").isFile }
    }

    /**
     * DESK-58: the world conf's PocketRealm.NearbyInteract knob verdict —
     * the AUTHORIZED-layout EFFECTIVE resolution of the requested tweaks
     * over the user's actual client executable ([nearbyUseEnabledForExecutable]),
     * not the requested consent alone: on a non-vanilla-build layout the
     * launch runs unpatched, so the server must not promise nearby-use the
     * unpatched client cannot complete (the original DESK-58 failure
     * shape). No resolvable client executable at world start fails closed
     * to 0 (a world can run without any client installed).
     */
    private fun nearbyUseEnabledForConf(): Boolean = runCatching {
        val dir = resolveClientDir() ?: return false
        val exe = File(dir, "WoW.exe")
        if (!exe.isFile) return false
        nearbyUseEnabledForExecutable(
            DesktopSettingsStore(roots.settingsFile).load().tweaks,
            com.pocketrealm.fs.FileDigests.sha256(exe),
        )
    }.getOrDefault(false)

    /** One launch-resolution verdict, carrying the status-line wording. */
    private sealed class ClientResolution {
        abstract val dir: File

        abstract fun describe(): String

        class Managed(val managed: ManagedClientStore.ManagedClient) : ClientResolution() {
            override val dir: File = managed.root
            override fun describe(): String = "managed generation ${managed.generation}"
        }

        class Folder(override val dir: File) : ClientResolution() {
            override fun describe(): String = "folder"
        }
    }

    /**
     * The client drain (DESK-114): destroy by pid — the self-spawned
     * process when it lives, else the scan hit under the client directory,
     * so an ownerless/user-launched client dies WITH the session instead of
     * surviving to play against a world the app just stopped. DESK-121: the
     * drain is a TREE drain — taskkill /PID <pid> /T /F sweeps descendants
     * first (the Android contract's whole-tree drain), with the direct
     * destroy escalation kept as the honest fallback whenever the tree
     * kill could not run or could not confirm. The process field is
     * cleared only once exit is proven.
     */
    private fun stopClient(): RuntimeActionResult {
        // The flag set is visible to journalClientExitIfDead under the same
        // lock it checks (see there): once the set lands, no observer can
        // journal this stop's kill as an abnormal death.
        synchronized(clientExitLock) { drainingClient = true }
        try {
            val owned = client?.takeIf { it.isAlive }
            val handle = owned?.toHandle()
                ?: findClientProcess()?.let { ProcessHandle.of(it.pid.toLong()).orElse(null) }
            if (handle == null) {
                client = null
                restoreRealmlistAfterTeardown()
                return RuntimeActionResult(ok = true, detail = "client not running")
            }
            val treeFailure = runCatching { treeKiller.kill(handle.pid()) }
                .getOrElse { failure -> "tree kill failed: ${failure.message}" }
            if (treeFailure != null) {
                DesktopLog.w(
                    CLIENT_LOG_TAG,
                    "client tree kill not confirmed (pid ${handle.pid()}): $treeFailure",
                )
            }
            val exited = destroyHandle(handle)
            val detail = when {
                exited -> "client exited"
                owned != null -> "client survived destroyForcibly (pid ${owned.pid()})"
                else -> "client survived destroyForcibly (pid ${handle.pid()})"
            }
            if (exited) {
                client = null
                restoreRealmlistAfterTeardown()
            }
            return RuntimeActionResult(ok = exited, detail = detail)
        } finally {
            synchronized(clientExitLock) { drainingClient = false }
        }
    }

    /**
     * DESK-51: the session's realmlist restore on the teardown paths (a
     * proven exit, the not-running early verdict, and close()). The user's
     * original bytes return and the backup slot retires — a session that
     * never projected (no backup) is the honest not-restored no-op.
     */
    private fun restoreRealmlistAfterTeardown() {
        val dir = launchedClientDir
        if (dir != null) {
            DesktopRealmlist.restoreForSession(dir, roots, realmlistSession)
            launchedClientDir = null
        }
    }

    /** destroy() with the bounded destroyForcibly escalation; true only
     * when exit is proven. Windows destroy() is TerminateProcess — the
     * escalation buys bounded waiting, not politeness. */
    private fun destroyHandle(handle: ProcessHandle): Boolean {
        runCatching { handle.destroy() }
        awaitExit(handle)
        if (handle.isAlive) {
            runCatching { handle.destroyForcibly() }
            awaitExit(handle)
        }
        return !handle.isAlive
    }

    private fun awaitExit(handle: ProcessHandle) {
        runCatching {
            handle.onExit().get(CLIENT_STOP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    // ---------------- database component ----------------

    /**
     * DESK-49/92: whether every seeded realm database is present in the
     * datadir — the first-run tutorial's prepare-world-data status (pure
     * file-existence check, no natives).
     */
    fun isDatadirSeeded(): Boolean = DATABASES.all { database ->
        DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
    }

    // ---------------- DESK-6/18/37/100: database snapshots ----------------
    //
    // The named-backup lane, through the SHARED DatabaseSnapshotStore
    // (stopped-state, hash-verified, manifest+sha256 publication — the
    // Android engine's exact format, so a snapshot is portable):
    // create/list/verify/retainNewest gated on the same runtimes-at-rest
    // proof as recovery, plus the transactional restore's
    // begin/commit/rollback legs the app model drives (begin swaps a
    // hash-verified candidate into the datadir; the model boots the stack to
    // world-ready, clean-stops, then commits — or force-stops and rolls
    // back, mirroring the Android RealmService.restoreBackup transaction).
    //
    // The provider boundary (LV-server-db-37's explicitness, made ACTIVE
    // now that the MariaDB provider is staged): the DESK-47/106 selection
    // decides WHICH datadir a named backup covers — the SQLITE selection
    // (the dev lane) keeps the seeded-sqlite-datadir arm; the MARIADB
    // selection backs up roots.mariadbDatadir in the engine's sanctioned
    // at-rest form (the PHYSICAL datadir copy the engine's own
    // pre-migration snapshot leg takes through this same store — no
    // logical dump), gated on the engine's clean-stop proof. A MARIADB
    // backup that covered only sqlite would be a silent wrong backup, so
    // the arm is chosen by name and every snapshot carries its provider
    // in the manifest's compatibility object.

    private val snapshotStore by lazy {
        com.pocketrealm.database.DatabaseSnapshotStore(
            roots.databaseSnapshots,
            // W4 flip (D86 routing law): the compatibility gate resolves
            // the lane from the pinned boot lane (the persisted pick), the
            // same routing the engine's store uses — a headless backend
            // never populates the :ui holder.
            persistedActiveFlavorId = { bootLane?.flavorId },
        )
    }

    /** One listed named backup (the Diagnostics card's row). [provider] is
     * the lane whose datadir the backup covered — the manifest's
     * compatibility stamp; the pre-boundary snapshots the SQLite-only lane
     * wrote carry no stamp and read as the SQLITE lane that took them. */
    data class BackupEntry(
        val snapshotId: String,
        val digest: String,
        val createdAtMs: Long,
        val provider: String,
    )

    /** One create verb's honest outcome: the refusal copy, plus — on
     * success — the created snapshot's identity (the archive export's
     * zipped directory and the restore target). */
    data class CreatedBackup(
        val ok: Boolean,
        val detail: String,
        val snapshotId: String? = null,
        val digest: String? = null,
    )

    /** A begun restore: the token the commit/rollback legs consume, plus
     * the R2-R4-3 disclosure — the qualified lanes whose migration seals
     * exist in the LIVE datadir but are ABSENT from the snapshot, i.e.
     * lanes this whole-datadir rollback rolls back to backup time. */
    class BegunRestore internal constructor(
        val token: String,
        val snapshotId: String,
        val digest: String,
        val rollsBackLanes: List<String> = emptyList(),
    )

    /** One in-flight restore's durable-ish footprint (the Android engine's
     * restore record, held in memory: the app is single-instance and the
     * legs refuse leftover trees from an earlier crash at begin time). The
     * SQLITE arm holds this backend's own candidate/quarantine pair; the
     * MARIADB arm delegates to the engine's durable restore transaction —
     * its record owns the trees — so it carries only the shared token. */
    private sealed class PendingRestore {
        class Sqlite(val candidate: File, val quarantine: File) : PendingRestore()
        class Mariadb : PendingRestore()
    }

    private val pendingRestores = mutableMapOf<String, PendingRestore>()

    /**
     * Runs [block] holding the transition lock — the same mutual exclusion
     * [createNamedBackup]/[beginSnapshotRestore] (and every start/stop leg)
     * take. The Guests-card ban lane (DesktopAppModel banGuest/unbanGuest/
     * suspendGuest) wraps its store transaction in this so a ban's open
     * SQLite handle can never overlap the at-rest datadir swaps: without it,
     * a ban transaction in flight during beginSnapshotRestore/
     * createNamedBackup makes the directory move fail on the open handle
     * (or a transient -wal sidecar trip the backup's no-sidecar gate) —
     * violating the at-rest assumption those paths document. The ban either
     * lands fully before the swap starts or waits the swap out.
     */
    fun <T> withDatadirQuiesced(block: () -> T): T = synchronized(transitionLock) { block() }

    /** One arm's admission verdict: the datadir a named backup would
     * cover, or the honest refusal that owns the verb. */
    private class BackupAdmission(val datadir: File?, val refusal: String?)

    /** The SQLITE arm (the dev lane): the seeded datadir with no WAL
     * sidecar — the shipped v1 gate, unchanged. */
    private fun sqliteBackupAdmission(): BackupAdmission {
        val missing = DATABASES.filterNot { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        if (missing.isNotEmpty()) {
            return BackupAdmission(
                null,
                "DB-NOT-SEEDED: " + missing.joinToString() + " missing under " + roots.sqliteDatadir,
            )
        }
        val sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir).filter { it.isFile }
        if (sidecars.isNotEmpty()) {
            return BackupAdmission(
                null,
                "DB-SQLITE: WAL sidecars present (" + sidecars.joinToString(" ") { it.name } +
                    "); run Recover first - a snapshot of an unrestored datadir would be torn",
            )
        }
        return BackupAdmission(roots.sqliteDatadir, null)
    }

    /** The MARIADB arm's admission: the engine's own status decides —
     * provider staged, mariadbd observably down (STOPPED/FAILED), and the
     * generation initialized AND sealed by a valid clean-stop marker (the
     * same durable proof the engine's start admission runs; the physical
     * copy is exactly what its pre-migration snapshot leg takes). A live
     * daemon or a dirty generation refuses — the remedy is the engine's
     * stop/recover verbs, never a torn datadir copy. */
    @Suppress("ReturnCount") // each refusal is one admission gate's verdict
    private fun mariadbBackupAdmission(): BackupAdmission {
        val status = mariaDbLane().status()
        if (!status.optBoolean("providerReady")) {
            return BackupAdmission(
                null,
                "DB-LINK: pinned MariaDB provider is not staged; stage the " +
                    "distribution (tools/build_win_realm_runtime.py --database-backend " +
                    "mariadb) or select the SQLITE provider",
            )
        }
        val engineState = status.optString("state")
        if (engineState != "STOPPED" && engineState != "FAILED") {
            return BackupAdmission(
                null,
                "DB-SNAPSHOT: mariadbd is still $engineState; the named backup needs the " +
                    "daemon cleanly stopped (clean-stop, then back up)",
            )
        }
        if (!status.optBoolean("initialized")) {
            return BackupAdmission(
                null,
                "DB-NOT-INITIALIZED: the MariaDB datadir has no initialized generation under " +
                    roots.mariadbDatadir,
            )
        }
        if (!status.optBoolean("cleanMarker")) {
            return BackupAdmission(
                null,
                "DB-RECOVERY: the MariaDB generation is not sealed by a valid clean-stop " +
                    "marker; recover the database (Diagnostics) before the named backup",
            )
        }
        // The pending-restore gate the Android engine twin enforces
        // (DatabaseEngine.kt:2543 refuses createNamedBackup while a restore
        // record exists): a pending record means the lane is mid-transaction
        // over the swapped-in candidate, and the interrupted restore's own
        // settle leg still needs the record's snapshot — never back that
        // state up or let a backup taken now shadow it.
        if (status.optBoolean("restorePending")) {
            return BackupAdmission(
                null,
                "DB-SNAPSHOT: restore verification is pending; settle the interrupted restore " +
                    "first (start the app - the preflight rolls it back or finishes its " +
                    "commit) before the named backup",
            )
        }
        return BackupAdmission(roots.mariadbDatadir, null)
    }

    /** The compatibility stamp every named backup carries: the ACTIVE
     * flavor through the flavor machine (GAP-3, owner census UIUX-3 — the
     * switcher's selection at the second qualification, replacing the
     * `.single()` that threw inside the transition lock and refused every
     * named backup) plus the provider lane whose datadir the snapshot
     * covers (the LV-server-db-37 boundary, readable by every later
     * list/verify/restore). */
    private fun backupCompatibility(provider: Settings.DatabaseProvider): org.json.JSONObject =
        org.json.JSONObject()
            .put("flavor", ActiveFlavor.resolve(NAMED_BACKUP_LANE).flavorId)
            .put("provider", provider.name)

    /** The provider lane a snapshot's manifest stamps itself with (the
     * SQLite-only lane wrote no stamp — its snapshots read as SQLITE). */
    private fun snapshotProviderOf(manifest: File): String =
        runCatching {
            org.json.JSONObject(manifest.readText())
                .optJSONObject("compatibility")
                ?.optString("provider")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: Settings.DatabaseProvider.SQLITE.name

    /**
     * Create a named backup of the ACTIVE provider's datadir
     * (`manual-<name>-<millis>`, the Android createNamedBackup shape).
     * Refuses unless the runtimes are at rest and the selected lane's
     * datadir is provably at rest too: the SQLITE arm needs the seeded
     * datadir with no WAL sidecar (a copied sidecar is a torn state — the
     * remedy is the Recover verb, which drains sidecars through SQLite's
     * own checkpoints); the MARIADB arm needs the engine clean-stopped
     * over an initialized generation and covers roots.mariadbDatadir (the
     * engine's sanctioned at-rest form — the physical copy its own
     * pre-migration snapshot leg takes). The success copy names the
     * provider and the datadir it covered, honestly.
     */
    fun createNamedBackup(name: String): CreatedBackup = synchronized(transitionLock) {
        runtimesAtRestRefusal()?.let { return@synchronized CreatedBackup(false, it) }
        val provider = selectedDatabaseProvider()
        val admission = when (provider) {
            Settings.DatabaseProvider.SQLITE -> sqliteBackupAdmission()
            Settings.DatabaseProvider.MARIADB -> mariadbBackupAdmission()
        }
        admission.refusal?.let { return@synchronized CreatedBackup(false, it) }
        val datadir = requireNotNull(admission.datadir)
        if (!BACKUP_NAME.matches(name)) {
            return@synchronized CreatedBackup(false, "DB-SNAPSHOT: invalid backup name")
        }
        val id = "manual-$name-${System.currentTimeMillis()}"
        val compatibility = backupCompatibility(provider)
        runCatching {
            snapshotStore.create(datadir, id, databaseStopped = true, compatibility = compatibility)
        }.fold(
            { snapshot ->
                DesktopLog.i(SNAPSHOT_LOG_TAG, "snapshot ${snapshot.id} created")
                CreatedBackup(
                    true,
                    "snapshot ${snapshot.id} created over the ${provider.name} datadir " +
                        datadir.absolutePath,
                    snapshot.id,
                    snapshot.digest,
                )
            },
            { failure ->
                DesktopLog.w(SNAPSHOT_LOG_TAG, "backup failed: ${failure.message}")
                CreatedBackup(false, "DB-SNAPSHOT: create failed: ${failure.message}")
            },
        )
    }

    /** The named backups (manual-* only, newest first — the Android
     * listBackups shape). Listing validates every manifest, so a corrupted
     * snapshot disappears from the list instead of breaking it. Every row
     * names the provider lane whose datadir the snapshot covered. */
    fun listBackups(): List<BackupEntry> = snapshotStore.list()
        .filter { it.id.startsWith("manual-") }
        .map { snapshot ->
            val createdAt = runCatching {
                org.json.JSONObject(snapshot.manifest.readText()).getLong("createdAt")
            }.getOrDefault(0L)
            BackupEntry(snapshot.id, snapshot.digest, createdAt, snapshotProviderOf(snapshot.manifest))
        }

    /** Re-verify one backup's digest seal + per-file hashes (DESK-37's
     * verify verb; load() validates both). The verdict names the provider
     * lane the snapshot covered. */
    fun verifyBackup(snapshotId: String): RuntimeActionResult = runCatching {
        val snapshot = snapshotStore.load(snapshotId)
        snapshot.id to snapshotProviderOf(snapshot.manifest)
    }.fold(
        { (id, provider) ->
            RuntimeActionResult(true, "snapshot $id verified (provider $provider)")
        },
        { failure -> RuntimeActionResult(false, "DB-SNAPSHOT: verify failed: ${failure.message}") },
    )

    /** Keep the newest [count] snapshots (the Android retainNewest lane). */
    fun retainNewestBackups(count: Int): RuntimeActionResult {
        // The pending-restore gate (the backup admission's twin, for the
        // retention door): a durable MariaDB restore record names the
        // snapshot it is restoring, and the engine's requireRestore
        // re-loads that snapshot on every settle — retiring it here would
        // strand the record so every rollback dies and every start refuses
        // DB-RESTORE-PENDING-FAILED permanently. Provider-agnostic on
        // purpose: the snapshot store is shared across the lanes (and the
        // provider selection is a free mid-pending toggle), so the record's
        // own snapshot must survive regardless of which lane is selected.
        if (mariaDbLane().status().optBoolean("restorePending")) {
            return RuntimeActionResult(
                false,
                "DB-SNAPSHOT: restore verification is pending; settle the interrupted restore " +
                    "first (start the app - the preflight rolls it back or finishes its " +
                    "commit) before retiring snapshots - the pending record's own backup " +
                    "must survive to settle with",
            )
        }
        return runCatching {
            snapshotStore.retainNewest(count)
        }.fold(
            { RuntimeActionResult(true, "retained the newest $count snapshots") },
            { failure -> RuntimeActionResult(false, "DB-SNAPSHOT: retain failed: ${failure.message}") },
        )
    }

    /**
     * The restore transaction's BEGIN leg: hash-restore the snapshot into a
     * candidate directory, integrity-gate every candidate database through
     * the recovery machinery, then swap it in while quarantining the live
     * datadir. The COMMIT leg (after the world-ready proof) retires the
     * quarantine; the ROLLBACK leg puts the original back. Leftover
     * candidate/quarantine trees from an interrupted earlier restore refuse
     * here — a human inspects them, the datadir itself was already swapped
     * or not by that interrupted run.
     *
     * The MARIADB arm routes through the engine's own restore transaction
     * (the Android beginRestore twin): the engine owns that datadir's
     * daemon-drain proofs, durable restore record, quarantine/candidate
     * swap and clean-seal/generation restamp, so this verb contributes only
     * the shared at-rest/leftover/name/provider gates and the token
     * bookkeeping the model's COMMIT/ROLLBACK legs key on.
     */
    fun beginSnapshotRestore(snapshotId: String): BegunRestore = synchronized(transitionLock) {
        // W4 flip: the restore targets the PERSISTED lane — pin the boot
        // lane before the engine's compatibility gate resolves it (a
        // restore is an operation leg like start; a headless or idle
        // backend never pinned it).
        pinBootLaneFromSettings()
        runtimesAtRestRefusal()?.let { refusal -> error(refusal) }
        val leftovers = roots.database.listFiles { file ->
            file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                file.name.startsWith("restore-original-"))
        }.orEmpty()
        check(leftovers.isEmpty()) {
            "DB-SNAPSHOT: an earlier restore left " + leftovers.joinToString(" ") { it.name } +
                " under " + roots.database + " - inspect and delete them before restoring again"
        }
        val snapshot = snapshotStore.load(snapshotId)
        check(snapshot.id.startsWith("manual-")) {
            "DB-SNAPSHOT: only named backups may be restored"
        }
        // The restore's provider boundary (LV-server-db-37's explicitness,
        // active): each arm swaps a candidate into ITS OWN provider's
        // datadir — SQLITE through this backend's integrity-gated swap,
        // MARIADB through the engine's restore transaction — so a snapshot
        // the OTHER lane took must refuse HERE, by name, on either
        // selection; never swap foreign bytes into a live datadir.
        val selected = selectedDatabaseProvider()
        val stamped = snapshotProviderOf(snapshot.manifest)
        check(stamped == selected.name) {
            "DB-SNAPSHOT: backup provider $stamped does not match the selected provider " +
                "${selected.name}; pick a ${selected.name} backup or switch the selection"
        }
        if (selected == Settings.DatabaseProvider.MARIADB) {
            val begun = beginMariaDbSnapshotRestore(snapshot.id)
            return@synchronized BegunRestore(
                begun.token, begun.snapshotId, begun.digest,
                lanesRolledBackBy(snapshot.id, snapshot.root),
            )
        }
        val token = java.util.UUID.randomUUID().toString()
        val candidate = File(roots.database, "restore-candidate-$token")
        val quarantine = File(roots.database, "restore-original-$token")
        try {
            check(candidate.mkdir()) { "DB-SNAPSHOT: could not create the restore candidate" }
            snapshotStore.restore(snapshot, candidate, databaseStopped = true)
            // Integrity gate BEFORE anything is swapped (DESK-100: reuse the
            // integrityOk machinery): the boot proof is the real verdict,
            // but a corrupt candidate must be refused before the live
            // datadir moves at all.
            DATABASES.forEach { database ->
                check(integrityOk(DatabaseSqliteControlPlane.databaseFile(candidate, database))) {
                    "DB-SNAPSHOT: restored candidate " + database + " failed the integrity gate"
                }
            }
            swapDirectories(roots.sqliteDatadir, quarantine, candidate)
        } catch (failure: Throwable) {
            candidate.deleteRecursively()
            throw failure
        }
        pendingRestores[token] = PendingRestore.Sqlite(candidate, quarantine)
        BegunRestore(token, snapshot.id, snapshot.digest)
    }

    /** The MARIADB arm's BEGIN: the engine's restore transaction owns the
     * datadir (daemon-drain proofs, durable restore record,
     * quarantine/candidate swap, clean-seal/generation restamp — the
     * rollbackToSnapshot machinery, exposed claim-less under the claim law
     * like the Android maintenance verbs: this verb's at-rest gate and the
     * engine's clean-stopped demand are the discipline, because the claim
     * stopOwned released on the way to rest cannot cover the COMMIT that
     * follows the verification stop); the engine's own
     * restore token becomes this transaction's token. */
    /**
     * R2-R4-3 (W4 code-red round 4): the snapshot store snapshots the
     * WHOLE shared datadir while seals/ledgers are lane-scoped — restoring
     * an older backup on lane A silently rolls lane B's family back to
     * backup time (its seal vanishes; its next boot re-applies, and
     * post-backup accounts/characters are gone). The cross-flavor LABELED
     * restore is already refused; this is the residual same-provider
     * shape, surfaced as an explicit disclosure instead of a silent
     * rollback: every lane sealed in the LIVE datadir whose seal is absent
     * from the snapshot.
     */
    private fun lanesRolledBackBy(
        snapshotId: String,
        snapshotRoot: File,
    ): List<String> {
        val liveLanes = roots.mariadbDatadir
            .listFiles { file -> file.name.startsWith(".pocketrealm-migrations") }
            .orEmpty()
            .mapNotNull { file ->
                lanedSealRegex.find(file.name)?.groupValues?.getOrNull(1) ?: "vanilla"
            }
            .toSet()
        val snapshotLanes = snapshotRoot
            .listFiles { file -> file.name.startsWith(".pocketrealm-migrations") }
            .orEmpty()
            .mapNotNull { file ->
                lanedSealRegex.find(file.name)?.groupValues?.getOrNull(1) ?: "vanilla"
            }
            .toSet()
        val rolledBack = liveLanes - snapshotLanes
        if (rolledBack.isNotEmpty()) {
            DesktopLog.w(
                "Snapshots",
                "RESTORE-DISCLOSURE: restoring " + snapshotId + " rolls back the " +
                    rolledBack.sorted() + " database family/families to backup time " +
                    "(their migration seals are absent from the snapshot)",
            )
        }
        return rolledBack.sorted()
    }

    private val lanedSealRegex = Regex(
        "^\\.pocketrealm-migrations(?:-(\\w+))?\\.json$",
    )

    private fun beginMariaDbSnapshotRestore(snapshotId: String): BegunRestore {
        val begun = mariaDbLane().beginRestore(snapshotId)
        check(begun.optBoolean("ok")) {
            "DB-SNAPSHOT: the MariaDB engine restore refused: " +
                "${begun.optString("errorClass")}: ${begun.optString("error")}"
        }
        val token = begun.getString("restoreToken")
        pendingRestores[token] = PendingRestore.Mariadb()
        return BegunRestore(token, begun.getString("snapshotId"), begun.getString("snapshotDigest"))
    }

    /** The COMMIT leg: the restored candidate proved itself world-ready and
     * clean-stopped; the quarantine copy is retired (the engine's own
     * transaction on the MARIADB arm). */
    fun commitSnapshotRestore(token: String): RuntimeActionResult = synchronized(transitionLock) {
        when (val pending = pendingRestores.remove(token)) {
            null -> return@synchronized RuntimeActionResult(false, "DB-SNAPSHOT: unknown restore token")
            is PendingRestore.Mariadb -> {
                val committed = mariaDbLane().commitRestore(token)
                if (!committed.optBoolean("ok")) {
                    pendingRestores[token] = pending
                    RuntimeActionResult(
                        false,
                        "DB-SNAPSHOT: commit failed: ${committed.optString("errorClass")}: " +
                            committed.optString("error"),
                    )
                } else {
                    RuntimeActionResult(true, "restore committed; quarantine copy retired")
                }
            }
            is PendingRestore.Sqlite -> {
                check(pending.quarantine.deleteRecursively()) {
                    "DB-SNAPSHOT: could not retire the quarantine copy " + pending.quarantine.name
                }
                RuntimeActionResult(true, "restore committed; quarantine copy retired")
            }
        }
    }

    /**
     * The ROLLBACK leg: any failure after the swap puts the original datadir
     * back. Refuses (honestly, leaving both trees for a human) when the
     * runtimes are no longer at rest — moving the datadir under a live
     * stack would corrupt it worse than the failed restore. The MARIADB arm
     * delegates the swap-back to the engine's restore transaction (its
     * record owns the trees and the restamp).
     */
    fun rollbackSnapshotRestore(token: String): RuntimeActionResult = synchronized(transitionLock) {
        val pending = pendingRestores.remove(token)
            ?: return@synchronized RuntimeActionResult(false, "DB-SNAPSHOT: unknown restore token")
        runtimesAtRestRefusal()?.let { refusal ->
            pendingRestores[token] = pending
            return@synchronized RuntimeActionResult(false, "DB-SNAPSHOT: rollback refused: $refusal")
        }
        when (pending) {
            is PendingRestore.Mariadb -> {
                val rolledBack = mariaDbLane().rollbackRestore(token)
                if (!rolledBack.optBoolean("ok")) {
                    pendingRestores[token] = pending
                    RuntimeActionResult(
                        false,
                        "DB-SNAPSHOT: rollback failed: ${rolledBack.optString("errorClass")}: " +
                            rolledBack.optString("error") + " (inspect restore-original-* under " +
                            roots.database + ")",
                    )
                } else {
                    RuntimeActionResult(
                        true,
                        "original datadir restored from the quarantined pre-restore copy",
                    )
                }
            }
            is PendingRestore.Sqlite -> {
                swapDirectories(roots.sqliteDatadir, pending.candidate, pending.quarantine)
                pending.candidate.deleteRecursively()
                RuntimeActionResult(true, "original datadir restored from " + pending.quarantine.name)
            }
        }
    }

    /** live ← from, and from ← to, as same-parent renames (the atomic-move
     * publication posture; Windows has no directory fsync — NTFS journals
     * the rename). The two-step swap is windowed: rollback covers the
     * second rename failing by moving the original back. */
    private fun swapDirectories(live: File, first: File, second: File) {
        java.nio.file.Files.move(
            live.toPath(), first.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        try {
            java.nio.file.Files.move(
                second.toPath(), live.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (failure: Throwable) {
            java.nio.file.Files.move(
                first.toPath(), live.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            throw failure
        }
    }

    // ---------------- realm + world components ----------------

    private fun nativeLifecycle(state: Long): ComponentLifecycle = when (state) {
        ServerRuntimeContract.STOPPED -> ComponentLifecycle.STOPPED
        ServerRuntimeContract.STARTING -> ComponentLifecycle.STARTING
        ServerRuntimeContract.READY -> ComponentLifecycle.READY
        ServerRuntimeContract.STOPPING -> ComponentLifecycle.STOPPING
        ServerRuntimeContract.FAILED -> ComponentLifecycle.FAILED
        else -> ComponentLifecycle.UNKNOWN
    }

    private fun observeRealm(): ComponentObservation {
        val status = RealmNative.statusNative()
        check(status.size == REALM_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "realm native status contract mismatch"
        }
        val state = nativeLifecycle(status[1])
        return ComponentObservation(
            component = RuntimeComponent.REALM,
            state = state,
            ready = state == ComponentLifecycle.READY,
            owner = ownership.getValue(RuntimeComponent.REALM).current(),
            pid = null,
            detail = "${RealmNative.detailNative().take(DETAIL_MAX_CHARS)} " +
                "(${ServerRuntimeContract.errorName(status[2])})" + realmTelemetrySummary(status),
        )
    }

    private fun observeWorld(): ComponentObservation {
        val status = WorldNative.statusNative()
        check(status.size == WORLD_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "world native status contract mismatch"
        }
        val state = nativeLifecycle(status[1])
        return ComponentObservation(
            component = RuntimeComponent.WORLD,
            state = state,
            ready = state == ComponentLifecycle.READY,
            owner = ownership.getValue(RuntimeComponent.WORLD).current(),
            pid = null,
            detail = "${WorldNative.detailNative().take(DETAIL_MAX_CHARS)} " +
                "(${ServerRuntimeContract.errorName(status[2])})" + worldTelemetrySummary(status),
        )
    }

    /** DESK-127: the statusNative telemetry indices the observation detail
     * used to discard (3..) ride the detail string, so the supervisor's
     * journal/log trail carries the world-load facts too. */
    private fun realmTelemetrySummary(status: LongArray): String =
        " heartbeatMs=${status[STATUS_INDEX_HEARTBEAT_MS]} " +
            "heartbeatCount=${status[STATUS_INDEX_HEARTBEAT_COUNT]}"

    private fun worldTelemetrySummary(status: LongArray): String =
        " heartbeatMs=${status[STATUS_INDEX_HEARTBEAT_MS]} " +
            "tickCount=${status[STATUS_INDEX_TICK_COUNT]} " +
            "lastTickMs=${status[STATUS_INDEX_LAST_TICK_MS]} " +
            "maxTickMs=${status[STATUS_INDEX_MAX_TICK_MS]} " +
            "activeSessions=${status[STATUS_INDEX_ACTIVE_SESSIONS]}"

    /** The bind value (design-tunnel §4.4.2): the typed endpoint address,
     * or the wildcard literal when the start tunnel-advertises. */
    private fun startRealm(bindAddress: String): ComponentObservation = synchronized(transitionLock) {
        files.prepareRealmLogsForStart(currentRealmState())
        files.writeLifecycle("realm", false, "start", bindAddress)
        val rc = RealmNative.startNative(
            files.realmdConfig(bindAddress, bootLaneSnapshot).absolutePath,
        )
        if (rc != 0) {
            files.writeLifecycle("realm", false, "start-failed", ServerRuntimeContract.errorName(rc.toLong()))
            error("realm start failed: ${ServerRuntimeContract.errorName(rc.toLong())} (rc=$rc)")
        }
        awaitComponentReady("realm", REALM_READY_WAIT_MS) { observeRealm() }
    }

    @Suppress("TooGenericExceptionCaught") // config/data failures surface as the honest DATA_MISSING posture
    private fun startWorld(bindAddress: String, spec: RuntimeLaunchSpec): ComponentObservation = synchronized(transitionLock) {
        // DESK-8/23/43/99: the Android ops' `check(stopAdmissionMonitor())`
        // start precondition — a start requires the previous monitor gone.
        check(runCatching { botAdmission.stop() }.getOrElse { false }) {
            "previous bot admission monitor did not stop"
        }
        var resolved: com.pocketrealm.bots.BotProfile? = null
        // Design-timeframe §8: the active stage rides the world-start
        // lifecycle record ("... stage=<id>"), the support-evidence pattern.
        var timeframeStageForLifecycle = ""
        // worldConfig is the honest gate: without prepared data it fails
        // with the prepared-data copy (the DATA_MISSING posture).
        val config = try {
            files.prepareWorldLogsForStart(currentWorldState())
            resolved = launchBotProfile(spec)
            // One blocking settings read feeds the nearby debounce leg and
            // the lifecycle-recorded timeframe stage (design §8: the active
            // stage rides the lifecycle/support evidence).
            // R3-1: the PINNED snapshot (pinned at start() entry before the
            // realm leg) — never a fresh disk load a mid-window write could
            // straddle. The fallback covers direct startWorld calls in tests.
            val snapshot = bootLaneSnapshot ?: DesktopSettingsStore(roots.settingsFile).load()
            // R1-r3-1: the stage resolves through the PINNED boot lane
            // (confValueFor — the android D47-10 twin); the bare confValue(
            // delegates to the LIVE holder machine and tears the stage leg
            // (conf emission, overlay desiredStage, lifecycle record) off
            // the boot lane at a multi-qualified flip.
            timeframeStageForLifecycle = com.pocketrealm.client.TimeframeStages.confValueFor(
                snapshot.timeframeStage,
                checkNotNull(bootLane) { "the boot lane must be pinned before the world leg" }.flavorId,
            )
            files.worldConfig(
                bindAddress,
                // DESK-132: the persisted nearby-use debounce reaches the
                // conf's PocketRealm.NearbyInteractCooldownMs (the Android
                // WorldRuntimeService threading twin).
                nearbyInteractTriggerGuardMs = snapshot.nearbyInteractTriggerGuardMs,
                // DESK-58: the conf knob follows the AUTHORIZED-layout
                // effective resolution over the user's actual client exe,
                // not the requested consent alone.
                nearbyUseEnabled = nearbyUseEnabledForConf(),
                // DESK-99: the launch profile carries the persisted
                // admission-lowered initial target — the next
                // `PocketRealm.BotTarget` conf knob.
                botProfile = resolved?.let { botAdmission.persistedLaunchProfile(it) },
                // The SAME snapshot feeds the conf write and the overlay's
                // desiredStage below: one stage resolution per start, so a
                // settings write can never land between the two reads and
                // boot a world whose conf names a stage the DB/ledger were
                // not brought to.
                settingsSnapshot = snapshot,
            )
        } catch (failure: Throwable) {
            files.writeLifecycle("world", false, "start-failed-data", failure.message ?: "config failure")
            error("world start refused: ${failure.message}")
        }
        // Design-timeframe §4.3: the stage overlay, applied at world start
        // BEFORE native boot (after the conf write, so a world whose data
        // cannot even produce a conf never touches the DB). Desired vs
        // applied resolves against the durable meta ledger; ANY overlay
        // failure refuses the start loudly - never boot a world whose DB
        // state contradicts the conf's PocketRealm.TimeframeStage.
        // PROVIDER-AWARE: a durable MARIADB active-provider marker (the
        // default lane) routes the overlay through the MariaDB engine
        // (same shared plan, MariaDB dialect, its own stage ledger); the
        // SQLite datadir leg runs only for the dev opt-in lane.
        try {
            com.pocketrealm.database.DesktopTimeframeOverlay.prepareForStart(
                roots.sqliteDatadir,
                desiredStage = timeframeStageForLifecycle,
                mariadbLeg = { stage ->
                    val result = mariaDbLane().applyTimeframeOverlay(stage)
                    check(result.getBoolean("ok")) {
                        "the MariaDB engine leg refused: " +
                            "${result.optString("errorClass")}: ${result.optString("error")}"
                    }
                    com.pocketrealm.database.DesktopTimeframeOverlay.AppliedState(
                        appliedStage = result.getString("appliedStage"),
                        overlaySha256 = result.optString("overlaySha256"),
                        appliedAtMs = result.optLong("appliedAtMs"),
                    )
                },
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
        // Pin the active generation for the world's lifetime: while this
        // lease is held, a concurrent publication cannot swap the data
        // under the running world (the Android twin's lease contract).
        worldDataLease = files.acquireNormalDataLease()
        files.writeLifecycle("world", false, "start", "$bindAddress stage=$timeframeStageForLifecycle")
        val rc = WorldNative.startNative(config.absolutePath)
        if (rc != 0) {
            runCatching { worldDataLease?.close() }
            worldDataLease = null
            files.writeLifecycle("world", false, "start-failed", ServerRuntimeContract.errorName(rc.toLong()))
            error("world start failed: ${ServerRuntimeContract.errorName(rc.toLong())} (rc=$rc)")
        }
        try {
            val ready = awaitComponentReady("world", worldReadyWaitMs()) { observeWorld() }
            // DESK-25: READY spent the process's one world lifetime (the
            // native pinned m_ever_ready) — every later start in this
            // process refuses WRONG_STATE; the app model offers the
            // app-relaunch action once this realm stops.
            worldLifetimeSpent = true
            // The admission monitor rides the world lifetime. It starts only
            // after READY (so a DLL-less lane never touches it) and only for
            // a bot-profile boot; a failed registration degrades honestly —
            // the world keeps its static conf target and the verdict surface
            // stays empty (the Android startAdmissionMonitor throw, op-level).
            if (resolved != null) {
                try {
                    botAdmission.start(resolved)
                } catch (failure: Throwable) {
                    files.writeLifecycle(
                        "world",
                        false,
                        "start-admission-failed",
                        failure.message ?: "admission start failed",
                    )
                    DesktopLog.w(
                        WORLD_LOG_TAG,
                        "bot admission monitor failed to start: ${failure.message}",
                    )
                }
            }
            ready
        } catch (failure: Throwable) {
            // The wait failed (native died mid-boot or never reached READY):
            // release the generation lease this start pinned.
            runCatching { worldDataLease?.close() }
            worldDataLease = null
            throw failure
        }
    }

    /**
     * The shared supervisor's start contract is the Android services':
     * `backend.start` returns the component READY. The desktop natives
     * surface STARTING at first and flip to READY on their own threads
     * (the bring-up gates poll the port; the supervisor demands the READY
     * observation immediately - the app's first Start-realm click failed
     * exactly there). The backend owns the wait so every consumer sees
     * the Android contract. FAILED fails fast with the native's detail;
     * the deadline must stay inside the supervisor's stage timeout
     * (realmStartMs 30s / worldStartMs 120s).
     */
    private fun awaitComponentReady(
        component: String,
        timeoutMs: Long,
        observe: () -> ComponentObservation,
    ): ComponentObservation {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // A transient bad statusNative poll (a JNI hiccup during heavy
            // bot provisioning) must not abort a long boot; only a settled
            // FAILED verdict or the deadline ends the wait.
            runCatching { observe() }.onSuccess { observation ->
                if (observation.state == ComponentLifecycle.READY) return observation
                if (observation.state == ComponentLifecycle.FAILED) {
                    error("$component start failed: ${observation.detail}")
                }
                if (System.currentTimeMillis() >= deadline) {
                    error("$component never reached READY within ${timeoutMs}ms: " +
                        "${observation.state} (${observation.detail})")
                }
            }.onFailure { failure ->
                if (System.currentTimeMillis() >= deadline) {
                    error("$component never reached READY within ${timeoutMs}ms: " +
                        "${failure.javaClass.simpleName}: ${failure.message}")
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun stopWorld(): RuntimeActionResult = synchronized(transitionLock) {
        // DESK-8/23/43/99: the admission monitor retires with the world (a
        // hung worker never blocks the stop — the retired generation dies
        // with the native, and the worker breaks on its next cadence).
        val admissionStopped = runCatching { botAdmission.stop() }.getOrDefault(false)
        if (!admissionStopped) {
            DesktopLog.w(WORLD_LOG_TAG, "bot admission monitor did not stop cleanly with the world")
        }
        val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        runCatching { worldDataLease?.close() }
        worldDataLease = null
        // R1-r2-1: the boot-lane pin dies with the world it served.
        _bootLane.value = null
        bootLaneSnapshot = null
        // The embedded-LLM lane's sidecar stops with the world it served
        // (DESK-48/80/103; the Android supervisor's :llm stop contract).
        files.stopLlmSidecar()
        files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "world stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
    }

    private fun stopRealm(): RuntimeActionResult = synchronized(transitionLock) {
        val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "realm stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
    }

    private fun currentRealmState(): Long {
        val status = RealmNative.statusNative()
        check(status.size == REALM_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "realm native status contract mismatch"
        }
        return status[1]
    }

    private fun currentWorldState(): Long {
        val status = WorldNative.statusNative()
        check(status.size == WORLD_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "world native status contract mismatch"
        }
        return status[1]
    }

    private fun accountInfoId(info: LongArray): Long =
        if (info.size == ACCOUNT_INFO_WIDTH) info[0] else 0L

    private fun accountInfoGmLevel(info: LongArray): Int =
        if (info.size == ACCOUNT_INFO_WIDTH) info[1].toInt() else 0

    private companion object {
        const val REALM_STATUS_WIDTH = 5
        const val WORLD_STATUS_WIDTH = 8

        /** GAP-3 (owner census UIUX-3): the [ActiveFlavor] lane suffixes
         * naming this backend's flavor-resolving gates. */
        const val MANAGED_CLIENT_LANE = "the managed client generation resolves the qualified lane"
        const val NAMED_BACKUP_LANE = "named backups resolve the qualified lane"

        /** The bootstrap success detail's seal-digest tail. */
        const val SEAL_DIGEST_TAIL_CHARS = 16

        /** The shared ABI's statusNative field indices (the Android
         * services' status JSON contract; DESK-127 renders 3..). */
        const val STATUS_INDEX_HEARTBEAT_MS = 3
        const val STATUS_INDEX_HEARTBEAT_COUNT = 4
        const val STATUS_INDEX_TICK_COUNT = 4
        const val STATUS_INDEX_LAST_TICK_MS = 5
        const val STATUS_INDEX_MAX_TICK_MS = 6
        const val STATUS_INDEX_ACTIVE_SESSIONS = 7

        /** WorldNative.botStatusNative widths/indices (the Android world
         * service's addBotStatus contract): enabled flag at 2, real
         * players at 9. */
        const val BOT_STATUS_SIZE = 19
        const val BOT_STATUS_ENABLED = 2
        const val BOT_STATUS_REAL_PLAYERS = 9
        const val FORCE_STOP_TIMEOUT_MS = 5_000L
        const val DETAIL_MAX_CHARS = 512
        const val CLIENT_STOP_TIMEOUT_MS = 10_000L
        const val WAL_DRAIN_TIMEOUT_MS = 15_000L
        const val WAL_DRAIN_POLL_MS = 250L
        const val ACCOUNT_INFO_WIDTH = 2

        /** The client lifecycle log tag (ring/file lane; DESK-110/112). */
        const val CLIENT_LOG_TAG = "Client"

        /** The world lifecycle log tag (the admission monitor's lane). */
        const val WORLD_LOG_TAG = "World"

        /** The snapshot-lane log tag (DESK-6/18/37/100). */
        const val SNAPSHOT_LOG_TAG = "Snapshots"

        /** Backup-name rule (the Android createNamedBackup's BACKUP_NAME). */
        val BACKUP_NAME = Regex("[A-Za-z0-9._-]{1,32}")

        /** Ready-wait bounds, mirroring the supervisor's bot-aware stage
         * contract (RuntimeContracts.stageTimeoutMs: world 120s plain,
         * 600s with a bot profile - the first world tick carries the
         * boot's deferred legs and honest-READY now waits for it). The
         * backend OWNS this wait (no outer stage enforces it here), so
         * the budget is the contract. */
        const val REALM_READY_WAIT_MS = 25_000L
        const val WORLD_READY_WAIT_MS = 110_000L
        const val BOT_WORLD_READY_WAIT_MS = 590_000L
        const val POLL_INTERVAL_MS = 200L

        /** The desktop seam's exec carries no bind parameters (it mirrors
         * the framework's execSQL), so the realmlist UPDATE interpolates.
         * v1 is loopback/LAN-IPv4 only: a strict dotted-quad guard keeps
         * the interpolation closed. */
        val ENDPOINT_ADDRESS = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** The realmbuilds advertisement's shape (ClientFlavor.
         * realmdAcceptedBuilds: space-separated build numbers, e.g. the
         * vanilla lane's accepted-build set — the numbers live in the
         * registry), bounded by the realmlist
         * column's varchar(64). The same no-bind-parameters stance as
         * ENDPOINT_ADDRESS: the guard keeps the interpolation closed. */
        val REALMBUILD_LIST = Regex("""^[0-9]{1,5}( [0-9]{1,5})*$""")
        const val REALMBUILD_LIST_MAX_CHARS = 64

        /** ServerRuntimeContract error index 10 (the errors table is
         * name-indexed, not constant-exported). */
        const val ERR_ACCOUNT_EXISTS = 10
    }
}
