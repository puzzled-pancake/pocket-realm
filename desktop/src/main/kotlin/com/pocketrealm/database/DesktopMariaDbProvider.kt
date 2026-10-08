package com.pocketrealm.database

import com.pocketrealm.database.DatabaseRuntimeContract.WINDOWS_PROVIDER_ID
import com.pocketrealm.fs.FileDigests
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The desktop MariaDB provider's staged closure (DESK-5/47/78): the native
 * Windows MariaDB distribution the engine lane launches, STAGED LIKE THE
 * REALM DLLS — a staging directory plus a committed lockfile pinning every
 * staged byte (the win realm lane's
 * schemas/realm-runtime-lockfile-sqlite-win.json contract, mirrored at
 * schemas/database-provider-lockfile-win.json).
 *
 * Staged layout (written by `tools/build_win_realm_runtime.py
 * --database-backend mariadb`, the desktop twin of the Android
 * `stage_mariadb_runtime.py` asset tree — same manifest keys so the
 * staging toolchain keeps one shape):
 *
 * ```
 * mariadb-provider/
 *   runtime-manifest.json      (schema 1, provider, abi, executables,
 *                               bootstrap_sha256 — the Android asset
 *                               manifest's exact fields)
 *   bootstrap.sql              (the pinned system-tables bootstrap)
 *   migration-manifest.json    (the pinned migration set: entries[],
 *                               source_commits, expected_revisions,
 *                               app_build_id — the Android
 *                               database/migrations/manifest.json schema)
 *   bin/mariadbd.exe  bin/mariadb.exe
 *   share/...  lib/...  plugin/...  migrations/<migration_id>.sqlz
 * ```
 *
 * Verification is the Android loadAndVerifyProviderIdentity posture,
 * byte-for-byte: the staged runtime-manifest must hash to the lockfile's
 * manifest pin, every executable must exist with the pinned size AND
 * sha256, and the bootstrap/migration-manifest digests must match. The
 * verified closure is what mints the durable [DatabaseDurableState.Identity]
 * the seals record — an unverified provider can never own a datadir.
 *
 * The DESK-78 posture stands until the distribution is staged: resolution
 * returns null and every engine verb refuses with the honest DB-LINK copy
 * ("pinned MariaDB provider is not staged") — never a silent SQLite
 * fallback over a MariaDB datadir.
 */
internal class DesktopMariaDbProvider(
    /** Test/staging seam: the staged provider root. Null resolves. */
    private val stagedRootOverride: File? = null,
    /** Test seam: the repo root carrying the committed lockfile. Null
     * resolves by walking up from user.dir (dev run inside the checkout). */
    private val repoRootOverride: File? = null,
) {

    /** One verified staged provider: every file re-proven against the
     * runtime manifest and the committed lockfile. */
    data class Closure(
        val root: File,
        val mariadbd: File,
        val mariadbClient: File,
        val bootstrap: File,
        val migrationManifest: JSONObject,
        /** sha256(runtime-manifest.json) — the Android
         * providerClosureSha256 input, verbatim. */
        val manifestSha256: String,
        val bootstrapSha256: String,
        val migrationManifestSha256: String,
        val migrationCount: Int,
    ) {
        /** The durable identity the seals and transactions record. */
        fun identity(): DatabaseDurableState.Identity = DatabaseDurableState.Identity(
            providerId = WINDOWS_PROVIDER_ID,
            providerClosureSha256 = manifestSha256,
            bootstrapSha256 = bootstrapSha256,
            migrationManifestSha256 = migrationManifestSha256,
            migrationCount = migrationCount,
        )

        /** A migration entry's staged .sqlz file (the Android asset path's
         * staging twin: migrations/<migration_id>.sqlz). */
        fun migrationAsset(migrationId: String): File = File(root, "$MIGRATIONS_DIR/$migrationId.sqlz")
    }

    class ProviderNotStaged(message: String) : IllegalStateException(message)

    /** The resolved staged root; null = not staged on this machine (the
     * DESK-78 honest-skip posture — never an exception from a lookup). */
    fun stagedRoot(): File? {
        stagedRootOverride?.let { return if (it.isDirectory) it else null }
        return candidateRoots().firstOrNull { it.isDirectory }
    }

    /**
     * Verify the staged closure end-to-end and return it. Throws
     * [ProviderNotStaged] (the DB-LINK family) on every unprovable state:
     * nothing staged, lockfile drift, digest/size mismatch, unsafe entry.
     */
    @Suppress("LongMethod", "ThrowsCount")
    // The fail-loud verification chain: each throw is one distinct
    // staged-state refusal (the Android loadAndVerifyProviderIdentity
    // posture).
    fun verify(): Closure {
        val root = stagedRoot() ?: throw ProviderNotStaged(
            "DB-LINK: pinned MariaDB provider is not staged (run tools/" +
                "build_win_realm_runtime.py --database-backend mariadb; the provider " +
                "distribution itself must be provisioned by the lane's staging step)",
        )
        val lockfile = readLockfile()
        val manifestFile = File(root, RUNTIME_MANIFEST_NAME)
        checkFile(manifestFile)
        val manifestText = manifestFile.readText(Charsets.UTF_8)
        checkDigest(
            FileDigests.sha256(manifestText),
            lockfile.optString("manifest_sha256"),
            "runtime-manifest.json",
        ) {
            "staged manifest bytes do not match the committed lockfile pin"
        }
        val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
            throw ProviderNotStaged("DB-LINK: staged runtime-manifest.json is not parseable JSON")
        }
        check(manifest.getInt("schema") == 1) { "DB-LINK: unsupported staged manifest schema" }
        check(manifest.getString("provider") == WINDOWS_PROVIDER_ID) {
            "DB-LINK: staged provider manifest does not match $WINDOWS_PROVIDER_ID"
        }
        check(manifest.getString("abi") == EXPECTED_ABI) {
            "DB-LINK: staged provider manifest ABI does not match $EXPECTED_ABI"
        }
        val bootstrap = File(root, BOOTSTRAP_NAME)
        checkFile(bootstrap)
        val bootstrapSha256 = FileDigests.sha256(bootstrap)
        check(bootstrapSha256 == manifest.optString("bootstrap_sha256")) {
            "DB-LINK: staged bootstrap digest mismatch against the runtime manifest"
        }
        checkDigest(bootstrapSha256, lockfile.optString("bootstrap_sha256"), "bootstrap.sql") {
            "staged bootstrap does not match the committed lockfile pin"
        }
        val executables = manifest.getJSONObject("executables")
        check(executables.length() == 2) { "DB-LINK: staged manifest must name exactly two executables" }
        val mariadbd = verifyExecutable(root, executables.getJSONObject("mariadbd"), lockfile, "mariadbd")
        val mariadbClient = verifyExecutable(root, executables.getJSONObject("mariadb"), lockfile, "mariadb")
        val migrationManifestFile = File(root, MIGRATION_MANIFEST_NAME)
        checkFile(migrationManifestFile)
        val migrationManifestText = migrationManifestFile.readText(Charsets.UTF_8)
        val migrationManifest = runCatching { JSONObject(migrationManifestText) }.getOrElse {
            throw ProviderNotStaged("DB-LINK: staged migration-manifest.json is not parseable JSON")
        }
        check(migrationManifest.getJSONArray("entries").length() >= 0) {
            "DB-LINK: staged migration manifest carries no entries array"
        }
        val migrationManifestSha256 = FileDigests.sha256(migrationManifestText)
        checkDigest(
            migrationManifestSha256,
            lockfile.optString("migration_manifest_sha256"),
            "migration-manifest.json",
        ) { "staged migration manifest does not match the committed lockfile pin" }
        return Closure(
            root = root,
            mariadbd = mariadbd,
            mariadbClient = mariadbClient,
            bootstrap = bootstrap,
            migrationManifest = migrationManifest,
            manifestSha256 = FileDigests.sha256(manifestText),
            bootstrapSha256 = bootstrapSha256,
            migrationManifestSha256 = migrationManifestSha256,
            migrationCount = migrationManifest.getJSONArray("entries").length(),
        )
    }

    /** mariadbd/mariadb.exe byte-verified against manifest + lockfile rows
     * (the Android verifyRecord leg, one record at a time). */
    private fun verifyExecutable(
        root: File,
        record: JSONObject,
        lockfile: JSONObject,
        which: String,
    ): File {
        val relative = record.getString("name")
        check(SAFE_RELATIVE.matches(relative) && !relative.contains("..")) {
            "DB-LINK: unsafe staged executable path for $which"
        }
        val file = File(root, relative)
        checkFile(file)
        check(file.length() == record.getLong("size")) { "DB-LINK: $which size mismatch" }
        val digest = FileDigests.sha256(file)
        check(digest == record.getString("sha256")) { "DB-LINK: $which hash mismatch" }
        val pins = lockfile.optJSONArray("artifacts") ?: JSONArray()
        // The lockfile records each artifact under BOTH path forms (the
        // python writer emits both): `name` = staging-relative
        // ("bin/mariadbd.exe", the staged manifest's `name` shape) and
        // `path` = repo-relative ("native/.build-win-x86_64/mariadb-
        // provider/bin/mariadbd.exe", the provenance form). The staged
        // manifest's short name must match either — matching the repo-
        // relative form only would refuse a REAL staged distribution.
        val pinned = (0 until pins.length()).mapNotNull { pins.getJSONObject(it) }.firstOrNull {
            it.optString("name") == relative ||
                it.optString("path") == relative ||
                it.optString("path").endsWith("/$relative")
        }
        check(pinned != null) { "DB-LINK: $which is not pinned by the committed lockfile" }
        check(pinned.getLong("size") == record.getLong("size") && pinned.getString("sha256") == digest) {
            "DB-LINK: $which staged bytes disagree with the committed lockfile pin"
        }
        return file
    }

    private fun readLockfile(): JSONObject {
        val lockfile = repoRootOverride?.let { File(it, LOCKFILE_REPOSITORY_PATH) }
            ?: bundledLockfile()
            ?: candidateRepoRoots().map { File(it, LOCKFILE_REPOSITORY_PATH) }.firstOrNull { it.isFile }
            ?: throw ProviderNotStaged(
                "DB-LINK: the MariaDB provider lockfile is missing (expected " +
                    LOCKFILE_REPOSITORY_PATH + " in the checkout)",
            )
        return runCatching { JSONObject(lockfile.readText(Charsets.UTF_8)) }.getOrElse {
            throw ProviderNotStaged("DB-LINK: the provider lockfile is not parseable JSON")
        }.also { record ->
            check(record.optString("abi") == EXPECTED_ABI && record.optString("database_backend") == "mariadb") {
                "DB-LINK: the provider lockfile does not describe the windows mariadb lane"
            }
        }
    }

    /**
     * The jpackage app dir's flat copy of the committed lockfile (packageApp
     * stages it beside the DLLs; $APPDIR is on java.library.path): an
     * install carries no schemas/ checkout to walk up to, and the image's
     * own pin must win over any walked-up checkout's newer lockfile so the
     * bundled closure verifies against the bytes it shipped with — the
     * same packaged-first posture as ServerRuntimeFiles.resolveBundledAsset.
     */
    private fun bundledLockfile(): File? =
        javaLibraryPathRoots().map { File(it, LOCKFILE_BUNDLED_NAME) }.firstOrNull { it.isFile }

    /** Staged-root candidates, the realm-DLL resolution posture: the dev
     * checkout's win staging area first (walked up from user.dir), then the
     * packaged app dir (java.library.path) siblings. */
    private fun candidateRoots(): List<File> =
        candidateRepoRoots().map { File(it, STAGED_ROOT_REPOSITORY_PATH) } +
        javaLibraryPathRoots().map { File(it, "mariadb-provider") }

    private fun candidateRepoRoots(): List<File> = generateSequence(File(System.getProperty("user.dir"))) {
        it.parentFile
    }.take(MAX_WALK_UP).toList()

    private fun javaLibraryPathRoots(): List<File> = System.getProperty("java.library.path")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.map(::File)
        .orEmpty()

    private fun checkFile(file: File) {
        check(file.isFile) { "DB-LINK: staged provider file is missing: ${file.name}" }
    }

    private fun checkDigest(
        actual: String,
        expected: String?,
        what: String,
        detail: () -> String,
    ) {
        check(expected != null && SHA256.matches(expected)) {
            "DB-LINK: the lockfile has no usable pin for $what"
        }
        check(actual == expected) { "DB-LINK: ${detail()}" }
    }

    companion object {
        const val LOCKFILE_REPOSITORY_PATH = "schemas/database-provider-lockfile-win.json"

        /** The lockfile's flat name as packageApp bundles it into the
         * jpackage app dir (see [bundledLockfile]). */
        const val LOCKFILE_BUNDLED_NAME = "database-provider-lockfile-win.json"
        const val STAGED_ROOT_REPOSITORY_PATH = "native/.build-win-x86_64/mariadb-provider"
        const val RUNTIME_MANIFEST_NAME = "runtime-manifest.json"
        const val BOOTSTRAP_NAME = "bootstrap.sql"
        const val MIGRATION_MANIFEST_NAME = "migration-manifest.json"
        const val MIGRATIONS_DIR = "migrations"
        const val EXPECTED_ABI = "windows-x86_64"

        /** The Windows named pipe the datadir's my.cnf and the shared
         * databaseInfo socket form both name (the Android mariadb.sock
         * name, kept byte-identical). */
        const val PIPE_NAME = "mariadb.sock"

        private const val MAX_WALK_UP = 7
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SAFE_RELATIVE = Regex("[A-Za-z0-9._/+-]{1,255}")
    }
}
