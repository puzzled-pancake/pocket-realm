package com.pocketrealm.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correction C1's Android pin: the realmlist row's realmbuilds
 * advertisement is WRITTEN by the projection seam in BOTH provider lanes.
 *
 * No repo-owned seed carries a realmbuilds value — the pinned realmd seed
 * (MariaDB) and the replayed sqlite seed transcript both ship the row with
 * realmbuilds='' — so without a write at the seam realmd answers every
 * logon challenge ok_build=false until the world's own start UPDATE. The
 * seam is the lane's one owner of the row before realmd serves a logon,
 * and the list it writes is the session flavor's
 * ClientFlavor.realmdAcceptedBuilds (never a hard-coded id, never a
 * gamebuild column).
 *
 * r2 (process-visibility fix, the DesktopDatabaseService twin): the flavor
 * is resolved ONCE in the seam's caller — AndroidRuntimeBackend — and the
 * accepted-build list is THREADED across Binder as a value. The engine
 * never re-resolves it: the Home switcher's ActiveFlavor selection is
 * UI-process-local in-memory state (HomeScreen is the sole select caller;
 * :database and :supervisor are separate processes), so a resolve inside
 * :database could only see the sole-qualified default and would fail loud
 * the moment a second flavor qualifies.
 *
 * r3 (the same blindness fixed at the caller): :supervisor is a separate
 * process TOO, so resolving the in-memory ActiveFlavor holder there had
 * exactly the same blindness the r2 move left :database with. The
 * selection now truly crosses the process boundary: the Home switcher
 * PERSISTS its pick (the multi-process settings store's
 * selected_flavor_id key), and the backend resolves that persisted pick
 * through the same fail-loud ActiveFlavorState machine — an empty pick
 * falls to the sole-qualified default and refuses once a second flavor
 * qualifies; a stale pick refuses; never a silent lane downgrade.
 *
 * r4 (the projected DATABASE's flavor routing): the MariaDB projection
 * leg resolves its TARGET DATABASE through the flavor registry at
 * projection time — the ServerRuntimeFiles.serverDatabases() gate's twin,
 * the desktop leg's ServerRuntimeFiles.serverDatabases() resolution —
 * never a hard-coded vanilla name, so the conf's LoginDatabaseInfo and
 * this row land in the same database by construction. Registry-only
 * resolution honors r2/r3: ClientFlavors is static state identical in
 * every process (nothing UI-local is consulted), and the gate refuses
 * loud at zero or several qualified flavors instead of guessing the
 * vanilla row.
 *
 * DatabaseEngine needs a live datadir (a booted mariadbd or a staged
 * sqlite seed) the pure-JVM unit lane cannot host, so the cross-lane
 * wiring is pinned as a source contract over the shipped source — the
 * same pattern as LanTunnelWiringContractTest for this file's advertised
 * -address grammar. The registry values themselves are pinned in
 * ClientFlavorTest and FlavorManifestParityTest.
 */
class RealmBuildsProjectionContractTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/$relative"),
            File("app/src/main/java/com/pocketrealm/$relative"),
            File("android/app/src/main/java/com/pocketrealm/$relative"),
            File("../android/app/src/main/java/com/pocketrealm/$relative"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun bothProviderLanesConsumeTheThreadedAcceptedBuilds() {
        val engine = mainSource("database/DatabaseEngine.kt")
        assertEquals(
            "each provider lane's projection must take the caller-threaded " +
                "accepted-builds value (correction C1) — exactly one consumer " +
                "per lane, never a hard-coded build list",
            2,
            occurrences(engine, "val builds = realmbuilds"),
        )
        assertTrue(
            "the MariaDB lane's UPDATE must set realmbuilds beside the endpoint",
            "realmbuilds='\$builds'" in engine,
        )
        assertTrue(
            "the sqlite lane's bound-parameter UPDATE must set realmbuilds too",
            "UPDATE realmlist SET address = ?, port = ?, realmbuilds = ? WHERE id = 1" in engine,
        )
    }

    @Test
    fun theEngineResolvesThePersistedLaneNeverTheUiHolder() {
        // flip (retirement) the engine resolves the PERSISTED
        // Home selection through ActiveFlavorState (the same machine the
        // desktop twin uses) — never the :ui-local ActiveFlavor holder this
        // process cannot see, and never a silent vanilla guess.
        val engine = mainSource("database/DatabaseEngine.kt")
        assertEquals(
            "the engine must not resolve the active flavor through the :ui " +
                "holder — the persisted pick crosses the process boundary instead",
            0,
            occurrences(engine, "ActiveFlavor.resolve"),
        )
        assertEquals(
            "the engine keeps no flavor import either (the value crosses Binder)",
            0,
            occurrences(engine, "import com.pocketrealm.client.ActiveFlavor"),
        )
        assertTrue(
            "the engine must resolve the persisted lane through ActiveFlavorState",
            "ActiveFlavorState(persisted)" in engine,
        )
    }

    @Test
    fun theAndroidLedgerScopeRefusesNonVanillaPicksBeforeProvisioning() {
        // R4-1b (W4 code-red round 4): the android migration ledger is
        // VANILLA-only — provisioning the persisted lane's tbc*/wotlk*
        // family would strand the datadir (the vanilla-only ledger can
        // never serve it, and re-picking vanilla cannot recover a family
        // that was never provisioned). The bootstrap resolver must refuse
        // LOUD, naming the scope + the remedy, BEFORE it can return any
        // non-vanilla database names. built the host-side per-flavor
        // native lanes; the on-device packaging routing is the recorded
        // open follow-up (decision D90) — the refusal cites it, never a
        // wave name.
        val engine = mainSource("database/DatabaseEngine.kt")
        val gate = engine.substringAfter("private fun bootstrapDatabaseNames")
            .substringBefore("private fun realmdDatabaseName")
        assertTrue(
            "the bootstrap resolves the persisted pick first (the R1-1 law)",
            "ActiveFlavorState(persisted)" in gate,
        )
        assertTrue(
            "the android lane-scope refusal must fire for non-vanilla picks",
            "ANDROID-LANE-SCOPE" in gate &&
                "recorded as open follow-up work in the decision log, D90" in gate &&
                "Re-pick Vanilla on Home" in gate,
        )
        assertTrue(
            "the refusal must not name a wave as the pending owner (the " +
                "R6-r4-1 class — W5 exited with the packaging follow-up open)",
            "W5 work" !in gate && "(W5)" !in gate,
        )
        // ordering: the refusal check must precede the bootstrap names the
        // provisioning consumes
        assertTrue(
            "the refusal must fire BEFORE the lane's database names are " +
                "returned to the provisioning",
            gate.indexOf("ANDROID-LANE-SCOPE") <
                gate.indexOf("return MariaDbBootstrap.databaseNames(flavor)"),
        )
    }

    @Test
    fun theBackendResolvesTheSessionFlavorOnceAndThreadsItAcrossBinder() {
        val backend = mainSource("supervisor/AndroidRuntimeBackend.kt")
        assertEquals(
            "exactly one resolve feeds the projection — in the backend, seeded " +
                "from the PERSISTED Home-switcher selection (the multi-process " +
                "settings store is the one selection state :supervisor can see; " +
                "the desktop twin resolves in DesktopRuntimeBackend and threads " +
                "the list into the engine)",
            1,
            occurrences(
                backend,
                ".resolve(ActiveFlavor.qualifiedFlavors, REALMD_BUILDS_LANE).realmdAcceptedBuilds",
            ),
        )
        assertTrue(
            "the resolve must seed the machine from the persisted selection key",
            "ActiveFlavorState(Settings(appContext).flow.first().selectedFlavorOrNull())" in backend,
        )
        assertEquals(
            "the backend must not resolve through the in-memory ActiveFlavor " +
                "holder — its selection is UI-process-local state that never " +
                "crosses into :supervisor (r3: the r2 'flavor-visible endpoint' " +
                "claim was true only for the single-process desktop twin)",
            0,
            occurrences(backend, "ActiveFlavor.resolve"),
        )
        val aidl = aidlSource()
        assertTrue(
            "the AIDL seam carries the accepted-build list as a value",
            "String projectRealmEndpoint(String instanceToken, String address, int worldPort, String realmbuilds)" in aidl,
        )
        val service = mainSource("database/DatabaseService.kt")
        assertTrue(
            "the Binder stub passes the threaded list straight to the engine",
            "engine.projectRealmEndpoint(address, worldPort, realmbuilds)" in service,
        )
    }

    @Test
    fun theHomeSwitcherPersistsTheSelectionTheSupervisorProjectionReads() {
        val home = mainSource("ui/HomeScreen.kt")
        assertTrue(
            "the switcher's tap must persist the pick beside the in-memory " +
                "select — :supervisor reads this key when it projects the " +
                "session's realmlist row",
            "settings.update { it.copy(selectedFlavorId = chip.flavor.flavorId) }" in home,
        )
        assertTrue(
            "a fresh UI process must re-apply the persisted pick to its " +
                "in-memory machine (cold-start hydration), or the chips and " +
                "the UI-process gates would disagree with what :supervisor " +
                "projects",
            "settingsSnapshotState?.selectedFlavorOrNull()" in home,
        )
    }

    @Test
    fun bothProviderLanesVerifyAndShapeGuardTheAdvertisement() {
        val engine = mainSource("database/DatabaseEngine.kt")
        assertEquals(
            "each lane's verify read must prove the whole advertisement row",
            2,
            occurrences(engine, "realmbuilds FROM"),
        )
        assertEquals(
            "each lane must shape-guard the build list before it crosses the store",
            2,
            occurrences(engine, "realm builds advertisement is not a build list"),
        )
    }

    @Test
    fun theMariaadbProjectionTargetsTheFlavorRoutedRealmdDatabase() {
        val engine = mainSource("database/DatabaseEngine.kt")
        val leg = engine.substringAfter("private fun mariadbProjectRealmEndpoint")
            .substringBefore("private fun realmdDatabaseName")
        assertTrue(
            "the MariaDB projection's UPDATE must target the flavor-resolved " +
                "database, never a hard-coded vanilla name",
            "UPDATE \$realmdDatabase.realmlist" in leg,
        )
        assertTrue(
            "the verify read must prove the same flavor-resolved row",
            "SELECT address,port,realmbuilds FROM \$realmdDatabase.realmlist WHERE id=1 LIMIT 1;" in leg,
        )
        assertFalse(
            "the hard-coded vanilla database name must be gone from the projection leg",
            "classicrealmd" in leg,
        )
        assertTrue(
            "the interpolated name must be shape-guarded like every crossing literal",
            "realmd database name is not a plain identifier" in leg,
        )
        // The ServerRuntimeFiles.serverDatabases() gate's twin: registry-only
        // resolution (static ClientFlavors state, byte-identical in every
        // process — NOT the UI-local selection the r2/r3 pins keep out of
        // this process), loud at zero or several qualified flavors, and
        // vanilla-only while the conf templates seed only that lane.
        // W1.2: the law moved into bootstrapDatabaseNames() and the MariaDB
        // bootstrap's CREATE DATABASE literals route through the same
        // resolver (MariaDbBootstrap.initialAuthSql), so the gate pin slices
        // the law's new home and the bootstrap leg must consume it.
        val gate = engine.substringAfter("private fun bootstrapDatabaseNames")
            .substringBefore("\n    }")
        assertTrue(
            "the gate resolves the registry's qualified lane",
            "ClientFlavors.qualified()" in gate,
        )
        // flip (retirement) the ambiguous no-pick state still refuses
        // loud (the ActiveFlavorState resolve), but the vanilla-only
        // requirement is GONE — each qualified lane provisions its own
        // database family.
        assertTrue(
            "the gate resolves the persisted pick through ActiveFlavorState",
            "ActiveFlavorState(persisted)" in gate,
        )
        assertTrue(
            "the android-twin routing marker must be present on the android twin",
            "The android twin of" in gate,
        )
        assertTrue(
            "the projection must route through the one bootstrap resolver (W1.2)",
            "private fun realmdDatabaseName(): String = bootstrapDatabaseNames().realmd" in engine,
        )
        val bootstrapLeg = engine.substringAfter("val setup = fixedSql(")
            .substringBefore("runClient(\"root\"")
        assertTrue(
            "the initial-auth bootstrap must compose from MariaDbBootstrap " +
                "over the resolved lane's names",
            "MariaDbBootstrap.initialAuthSql(" in bootstrapLeg &&
                "bootstrapDatabaseNames()" in bootstrapLeg,
        )
        assertFalse(
            "the bare vanilla CREATE DATABASE literal must be gone from the bootstrap leg",
            "CREATE DATABASE IF NOT EXISTS classicrealmd" in bootstrapLeg,
        )
    }

    private fun aidlSource(): String {
        val candidates = listOf(
            File("src/main/aidl/com/pocketrealm/database/IDatabaseControl.aidl"),
            File("app/src/main/aidl/com/pocketrealm/database/IDatabaseControl.aidl"),
            File("android/app/src/main/aidl/com/pocketrealm/database/IDatabaseControl.aidl"),
            File("../android/app/src/main/aidl/com/pocketrealm/database/IDatabaseControl.aidl"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("IDatabaseControl.aidl not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }
}
