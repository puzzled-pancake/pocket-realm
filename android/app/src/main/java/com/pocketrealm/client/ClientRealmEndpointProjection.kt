package com.pocketrealm.client

import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption

/** App-owned realmlist projection. Every launch replaces stale topology before Wine starts. */
internal object ClientRealmEndpointProjection {
    fun text(endpoint: RealmEndpoint): String = "set realmlist ${endpoint.address}\r\n"

    /**
     * Projects [endpoint] into every realmlist the client may read: the root
     * `realmlist.wtf` (vanilla, TBC) and `Data/<locale>/realmlist.wtf` in
     * each locale folder (WotLK 3.3.5a reads ONLY that one — a client
     * shipped with `Data/enUS/realmlist.wtf` aimed at another server would
     * otherwise dial it). Returns the files written.
     */
    fun projectAll(clientRoot: File, endpoint: RealmEndpoint): List<File> {
        val targets = listOf(File(clientRoot, "realmlist.wtf")) + localeRealmlists(clientRoot)
        targets.forEach { project(it, endpoint) }
        return targets
    }

    /** `Data/<locale>/realmlist.wtf` for every locale folder (a four-letter
     * folder holding its locale MPQ or an existing realmlist). */
    fun localeRealmlists(clientRoot: File): List<File> {
        val data = clientRoot.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.equals("Data", true) }
            ?: return emptyList()
        return data.listFiles().orEmpty()
            .filter { dir -> dir.isDirectory && LOCALE_DIR.matches(dir.name) }
            .mapNotNull { dir ->
                val inner = dir.listFiles().orEmpty()
                val existing = inner.firstOrNull { it.isFile && it.name.equals("realmlist.wtf", true) }
                when {
                    existing != null -> existing
                    inner.any { it.isFile && it.name.lowercase().startsWith("locale-") } -> File(dir, "realmlist.wtf")
                    else -> null
                }
            }
            .sortedBy { it.path }
    }

    private val LOCALE_DIR = Regex("^[A-Za-z]{4}$")

    fun project(target: File, endpoint: RealmEndpoint) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(text(endpoint).toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
