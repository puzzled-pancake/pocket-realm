package com.pocketrealm.database

import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DatabaseRuntimeContract.SQLITE_PROVIDER_ID
import com.pocketrealm.fs.FileDigests
import java.io.File
import org.json.JSONObject

/**
 * The desktop SQLite lane's seal identities — the DatabaseDurableState
 * Identity leg for the in-tree SQLite provider, built on the SHARED
 * [DatabaseMigrationSeal] and [DatabaseGenerationSeal] validators (both
 * compiled desktop-side from shared-sources.json).
 *
 * Identity inputs, mirroring the Android loadAndVerifySqliteIdentity
 * posture exactly:
 *  - `providerId` is the shared in-tree SQLite provider id;
 *  - `bootstrapSha256` ALIASES the provider-closure digest: the desktop
 *    provenance (BUILD_PROVENANCE.json) minus its `seed_transcripts`
 *    object, hashed (ownership fields must be corpus-stable — hashing
 *    the whole provenance would change ownership on every corpus
 *    advance, exactly what the MariaDB lane forbids);
 *  - `manifestSha256` is the DESKTOP CORPUS identity: the pinned
 *    transcript digests over the shared control plane's schema list
 *    ([DATABASES] order), canonicalized. The desktop seed folds the
 *    whole corpus at provision time — there is no separate manifest
 *    asset, so the pins themselves are the manifest;
 *  - `migrationCount` is the pinned schema-list member count (the
 *    desktop's manifest-entry-count analog).
 *
 * Marker layout inside the datadir (same names as the Android SQLite
 * lane): `.pocketrealm-migrations.json` carries the shared migration
 * seal; `.pocketrealm-clean-stop.json` the clean-stop seal (validated by
 * [DatabaseGenerationSeal.clean]); `.pocketrealm-generation.json` the
 * generation marker extended with the revision record (identity fields
 * + the migration seal's digest — the tamper cross-check).
 */
internal object DesktopSqliteSeals {

    const val MIGRATION_MARKER_NAME = ".pocketrealm-migrations.json"
    const val CLEAN_MARKER_NAME = ".pocketrealm-clean-stop.json"
    const val GENERATION_MARKER_NAME = ".pocketrealm-generation.json"

    /** The desktop trim of DatabaseDurableState.Identity (no
     * providerClosureSha256 field: the desktop never boots a second
     * provider, so closure ownership rides the record in the marker). */
    data class Identity(
        val providerId: String,
        val bootstrapSha256: String,
        val manifestSha256: String,
        val migrationCount: Int,
    )

    /** One datadir's durable revision record, parsed from the generation
     * marker. `null` = legacy/unprovable datadir (seeded before the
     * seals existed, or a corrupt marker) — the refusal path. */
    data class RevisionRecord(
        val identity: Identity,
        val generationUuid: String,
        val migrationSealSha256: String,
    )

    fun identityFromProvenance(provenanceText: String): Identity {
        val provenance = JSONObject(provenanceText)
        val pins = provenance.getJSONObject("seed_transcripts")
        return Identity(
            providerId = SQLITE_PROVIDER_ID,
            bootstrapSha256 = closureSha256(provenance),
            manifestSha256 = corpusSha256(pins),
            migrationCount = DATABASES.size,
        )
    }

    /** The provider-closure digest: the provenance MINUS its
     * seed_transcripts object (the DatabaseDurableState.sqliteClosureDigest
     * mirror — corpus-stable ownership). Works on a copy; the caller's
     * object is never mutated. */
    fun closureSha256(provenance: JSONObject): String {
        val closure = JSONObject(provenance.toString())
        closure.remove("seed_transcripts")
        return FileDigests.sha256(closure.toString())
    }

    /** The corpus identity: canonical `database=sha256` lines over the
     * shared schema list order, hashed. Changes exactly when a pinned
     * transcript changes — the "seeded by an older app version" detector. */
    fun corpusSha256(pins: JSONObject): String {
        val canonical = DATABASES.joinToString("\n") { database ->
            "$database=${pins.getJSONObject(database).getString("sha256")}"
        }
        return FileDigests.sha256(canonical)
    }

    /** The shared migration seal stamped at seed time. */
    fun migrationSeal(identity: Identity, completedAt: Long): String =
        DatabaseMigrationSeal.create(
            providerId = identity.providerId,
            bootstrapSha256 = identity.bootstrapSha256,
            manifestSha256 = identity.manifestSha256,
            migrationCount = identity.migrationCount,
            completedAt = completedAt,
        )

    /** The shared migration-seal proof against [identity]. */
    fun migrationSealCurrent(markerText: String?, identity: Identity): Boolean =
        DatabaseMigrationSeal.current(
            markerText = markerText,
            expectedProviderId = identity.providerId,
            expectedBootstrapSha256 = identity.bootstrapSha256,
            expectedManifestSha256 = identity.manifestSha256,
            expectedMigrationCount = identity.migrationCount,
        )

    /** The clean-stop seal: {schema, provider, stoppedAt} — exactly the
     * shape [DatabaseGenerationSeal.clean] validates. */
    fun cleanSeal(stoppedAt: Long): String =
        JSONObject()
            .put("schema", 1)
            .put("provider", SQLITE_PROVIDER_ID)
            .put("stoppedAt", stoppedAt)
            .toString()

    fun cleanSealCurrent(markerText: String?): Boolean =
        DatabaseGenerationSeal.clean(markerText, SQLITE_PROVIDER_ID)

    /** The generation marker extended with the revision record (DESK-98:
     * the seed revision + migration-seal digest persist beside the
     * generation uuid). */
    fun generationMarker(
        generationUuid: String,
        identity: Identity,
        migrationSealSha256: String,
    ): String = JSONObject()
        .put("schema", 1)
        .put("generationUuid", generationUuid)
        .put("provider", identity.providerId)
        .put("bootstrapSha256", identity.bootstrapSha256)
        .put("manifestSha256", identity.manifestSha256)
        .put("migrationCount", identity.migrationCount)
        .put("migrationSealSha256", migrationSealSha256)
        .toString()

    /** Parse the revision record out of the generation marker; null when
     * the marker is missing, legacy (revision fields absent — the
     * pre-seal desktop layout), or structurally invalid. */
    fun revisionRecord(generationMarkerText: String?): RevisionRecord? = runCatching {
        val marker = JSONObject(requireNotNull(generationMarkerText))
        check(marker.getInt("schema") == 1)
        val identity = Identity(
            providerId = marker.getString("provider"),
            bootstrapSha256 = marker.getString("bootstrapSha256"),
            manifestSha256 = marker.getString("manifestSha256"),
            migrationCount = marker.getInt("migrationCount"),
        )
        check(identity.migrationCount >= 0)
        RevisionRecord(
            identity = identity,
            generationUuid = marker.getString("generationUuid"),
            migrationSealSha256 = marker.getString("migrationSealSha256"),
        )
    }.getOrNull()

    /**
     * The tamper cross-check: the migration marker's BYTES must
     * hash to the digest recorded in the generation marker, and the
     * marker must prove current against the record's own identity. A
     * hand-edited or stale ledger can never pass both.
     */
    fun sealMatchesRecord(migrationMarkerText: String?, record: RevisionRecord): Boolean =
        migrationMarkerText != null &&
            FileDigests.sha256(migrationMarkerText) == record.migrationSealSha256 &&
            migrationSealCurrent(migrationMarkerText, record.identity)
}

/**
 * The seal markers' on-disk I/O (names + atomic write + the recovery
 * re-stamp), split from the identity/validation object above to keep
 * each object to its one job.
 */
internal object DesktopSqliteSealMarkers {

    fun migrationMarkerFile(sqliteDatadir: File): File = File(sqliteDatadir, DesktopSqliteSeals.MIGRATION_MARKER_NAME)

    fun cleanMarkerFile(sqliteDatadir: File): File = File(sqliteDatadir, DesktopSqliteSeals.CLEAN_MARKER_NAME)

    fun generationMarkerFile(sqliteDatadir: File): File = File(sqliteDatadir, DesktopSqliteSeals.GENERATION_MARKER_NAME)

    /** pid-temp + fsync + atomic rename — the one marker write
     * discipline (the seeder's stamp and the runtime's clean-stop
     * re-stamp share it; same posture as the supervisor journal twin:
     * ATOMIC_MOVE first, plain-replace fallback, no directory-entry
     * fsync — Windows cannot open a directory through FileChannel). */
    fun atomicWriteMarker(sqliteDatadir: File, name: String, text: String) {
        val target = File(sqliteDatadir, name)
        val temp = File(sqliteDatadir, "$name.${ProcessHandle.current().pid()}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (move: java.io.IOException) {
            // never strand a per-pid temp in the datadir on a failed move
            temp.delete()
            throw move
        }
    }

    /** Re-stamp the clean-stop seal with a fresh stoppedAt (recovery's
     * "stopped generation prepared" ending; Android writes clean LAST). */
    fun restampCleanSeal(sqliteDatadir: File) {
        atomicWriteMarker(
            sqliteDatadir,
            DesktopSqliteSeals.CLEAN_MARKER_NAME,
            DesktopSqliteSeals.cleanSeal(System.currentTimeMillis()),
        )
    }
}
