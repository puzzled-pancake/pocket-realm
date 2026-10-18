// W6-N2 iroh spike — desktop echo harness (GATE, not a demo).
//
// Dependency pin: computer.iroh:iroh 1.1.0 — the NEWEST iroh Kotlin artifact
// actually published to Maven Central (metadata verified 2026-10-15:
// versions 1.0.0-rc.1 / 1.0.0 / 1.1.0, lastUpdated 2026-07-17; the plan's
// "1.3.x" Maven pin does not exist on Central — 1.3.0 exists only as the Rust
// crate). 1.1.0 still satisfies the campaign's "iroh pinned to 1.x" ruling.
// Pinned alongside: iroh-ffi git tag v1.1.0 (5e451092db) for the Android NDK
// leg so both legs measure the same ffi surface.
//
// Kotlin 2.2.x is REQUIRED here (not the desktop lane's 2.0.21): the iroh
// 1.1.0 artifact ships Kotlin 2.2.21 metadata, which a 2.0.x compiler
// refuses to read. Gradle 9.5.0 comes from the E:-resident wrapper dists.

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.pocketrealm.spike"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("computer.iroh:iroh:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

// The iroh 1.1.0 artifact's Gradle module metadata declares JVM 21 minimum
// (its class files are Java 21 bytecode) — same resolution-class issue W6-N1
// hit with secp256k1-kmp 0.17.x. The spike therefore runs on a JDK 21 that is
// E:-resident (E:\pocket_realm_toolchain\jdk\jdk-21.0.12.1+1); the desktop
// lane's pinned JDK 17 toolchain is untouched.
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pocketrealm.spike.iroh.EchoKt")
    // JNA extracts the bundled win32-x86-64 native from the jar at load time;
    // pin the extraction dir to E: so nothing lands in the OS temp dir.
    applicationDefaultJvmArgs = listOf("-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna")
}
