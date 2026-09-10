package com.pocketrealm.desktop

import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * DLL guard for native-dependent tests: assumeTrue (clean skip) when the
 * DLL lane is optional — the default dev/CI posture — and a HARD failure
 * under -Dpocketrealm.requireNatives (gradlew test -PrequireNatives), the
 * lane that builds the DLLs first and must never silently lose coverage
 * to a skip.
 */
internal fun requireNativeIfDemanded(label: String, dll: File) {
    if (System.getProperty("pocketrealm.requireNatives") != null && !dll.isFile) {
        fail("$label not built but requireNatives demanded: $dll")
    }
    assumeTrue("$label not built: $dll", dll.isFile)
}
