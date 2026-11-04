package com.pocketrealm.database

import java.io.File

/**
 * The runtime bootstrap's seed-source resolution: a fresh
 * packaged install ships the four seed transcripts + BUILD_PROVENANCE.json
 * FLAT in the jpackage app dir (packageApp copies them through --input;
 * the app dir is exactly what `-Djava.library.path=$APPDIR` exposes), and
 * a dev checkout keeps the staging-tree layout the seedRealmData task
 * uses (`native/.build-o09-x86_64/realm-staging-sqlite`, transcripts under
 * assets/seed/). The same walk-up discipline as
 * ServerRuntimeFiles.resolveBundledAsset: packaged runs resolve from
 * java.library.path entries, dev runs walk up to the checkout.
 *
 * SOURCES ONLY: a previously seeded datadir is never a seed source.
 */
object DesktopSeedLocator {

    /** One complete, verified-on-disk seed source. */
    data class SeedSource(val provenance: File, val transcripts: Map<String, File>) {
        fun complete(): Boolean =
            provenance.isFile && DatabaseSqliteControlPlane.DATABASES.all { database ->
                transcripts[database]?.isFile == true
            }
    }

    /** The first complete source found, or null (the honest
     * truly-missing case — nothing bundled, nothing in the checkout). */
    fun locate(): SeedSource? = candidates().firstOrNull { it.complete() }

    private fun candidates(): List<SeedSource> {
        val flat = (System.getProperty("java.library.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { appDir ->
                val dir = File(appDir)
                SeedSource(
                    provenance = File(dir, "BUILD_PROVENANCE.json"),
                    transcripts = DatabaseSqliteControlPlane.DATABASES.associateWith { database ->
                        File(dir, "$database.sqlz")
                    },
                )
            }
        val staging = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(WALK_UP_LIMIT)
            .map { checkout ->
                val stagingRoot = checkout.resolve("native/.build-o09-x86_64/realm-staging-sqlite")
                SeedSource(
                    provenance = File(stagingRoot, "BUILD_PROVENANCE.json"),
                    transcripts = DatabaseSqliteControlPlane.DATABASES.associateWith { database ->
                        File(stagingRoot, DatabaseSqliteControlPlane.seedAsset(database))
                    },
                )
            }
        return flat + staging
    }

    private const val WALK_UP_LIMIT = 6
}
