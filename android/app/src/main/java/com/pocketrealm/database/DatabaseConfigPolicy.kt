package com.pocketrealm.database

/**
 * Fixed MariaDB policy. ARM-only values are intentionally limited to options
 * that retain the existing durability contract and match Android flash/socket
 * operation. They remain explicit so device A/B evidence can be compared to
 * the x86 qualification lane without hidden server defaults.
 */
internal object DatabaseConfigPolicy {
    fun render(
        abi: String,
        datadir: String,
        socket: String,
        pidFile: String,
        errorLog: String,
        secureFileDirectory: String,
    ): String {
        require(abi == "arm64-v8a" || abi == "x86_64") { "unsupported database ABI" }
        val lines = mutableListOf(
            "[mariadbd]",
            "datadir=$datadir",
            "socket=$socket",
            "pid-file=$pidFile",
            "log-error=$errorLog",
            "skip-networking=1",
            "skip-name-resolve=1",
            "character-set-server=utf8mb4",
            "collation-server=utf8mb4_unicode_ci",
            "max-connections=24",
            "performance-schema=OFF",
            "innodb-buffer-pool-size=128M",
            "innodb-buffer-pool-instances=1",
            "innodb-log-file-size=32M",
            "innodb-flush-log-at-trx-commit=1",
            "sync-binlog=0",
        )
        if (abi == "arm64-v8a") {
            lines += "innodb-flush-neighbors=0"
            lines += "host-cache-size=0"
        }
        lines += "secure-file-priv=$secureFileDirectory"
        return lines.joinToString(separator = "\n", postfix = "\n")
    }
}
