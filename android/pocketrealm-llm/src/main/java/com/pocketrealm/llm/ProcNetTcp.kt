package com.pocketrealm.llm

/**
 * Pure parser for the kernel's /proc/net/tcp table, kept free of filesystem
 * access so the row contract is unit-testable with a fixture — on Android 10+
 * proc_net is SELinux-neverallowed to app domains, so the parser can never be
 * exercised on-device against real data. Rows are whitespace-separated with
 * column 1 the local ADDRESS:PORT (port as four uppercase hex digits),
 * column 3 the state (0A = TCP_LISTEN) and column 9 the socket inode; the
 * file's header row self-filters because its state column is "st", never
 * "0A". Malformed short rows are ignored, not fatal.
 */
internal object ProcNetTcp {
    private val ROW_SPLIT = Regex("\\s+")

    /** Socket inodes of the LISTEN sockets bound to [port]. */
    fun listenInodes(table: String, port: Int): List<String> =
        table.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(ROW_SPLIT) }
            .filter { cols -> cols.size > 9 && cols[3] == "0A" && cols[1].endsWith(":%04X".format(port)) }
            .map { cols -> cols[9] }
            .toList()
}
