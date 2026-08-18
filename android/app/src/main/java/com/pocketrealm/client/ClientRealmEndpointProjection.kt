package com.pocketrealm.client

import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption

/** App-owned realmlist projection. Every launch replaces stale topology before Wine starts. */
internal object ClientRealmEndpointProjection {
    fun text(endpoint: RealmEndpoint): String = "set realmlist ${endpoint.address}\r\n"

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
