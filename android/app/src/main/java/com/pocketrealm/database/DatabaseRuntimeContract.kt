package com.pocketrealm.database

object DatabaseRuntimeContract {
    const val X86_PROVIDER_ID = "mariadb-11.5.2-termux-glibc"
    const val X86_PROVIDER_VERSION = "11.5.2"
    const val ARM_PROVIDER_ID = "mariadb-12.3.2-termux-bionic-arm64"
    const val ARM_PROVIDER_VERSION = "12.3.2"

    /**
     * The in-tree SQLite provider's identity (the window APK
     * built with -PsqliteProvider). The version tracks the pinned
     * amalgamation (sources.json sqlite-amalgamation-3460100 = SQLite
     * 3.46.1); the ID names the provider family the seals record. The
     * MariaDB constants stay: the window ships BOTH closures and the
     * old provider must keep booting to serve the user-state
     * translation export.
     */
    const val SQLITE_PROVIDER_ID = "sqlite-3.46.1-in-tree"
    const val SQLITE_PROVIDER_VERSION = "3.46.1"
}
