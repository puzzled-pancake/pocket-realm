// iroh gateway — standalone single-module build (the spike
// pattern: this build MUST NOT join the desktop lane's Gradle tree — the
// toolchains differ by design, K2.2.21/JDK21 here vs K2.0.21/JDK17 there).
rootProject.name = "pocketrealm-gateway"

// W6-N4 code-red R4-5/R5-2: concurrent seats on the shared checkout must
// not share ANY build output — the results-dir-only seat redirect left
// the jar/fatJar tasks writing the same files (two seats' concurrent
// fatJar runs produced a half-written jar and subprocess-spawn failures
// in BOTH seats). The desktop lane's -Ppocketrealm.buildDir pattern,
// keyed on the same seat flag the test task reads: `-Ppocket.seat=<id>`
// moves the WHOLE build directory (build-seat-<id>), so one writer per
// seat owns every byte it produces. Inert unless the property is passed
// (default runs keep the canonical build/ layout).
gradle.beforeProject {
    providers.gradleProperty("pocket.seat").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { seat -> layout.buildDirectory.set(file("build-seat-$seat")) }
}
