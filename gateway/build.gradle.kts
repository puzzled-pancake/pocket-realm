// W6-N3 iroh gateway — the desktop transport process (host + join).
//
// WHY A SEPARATE PROCESS (and not code inside the desktop lane): the
// computer.iroh:iroh 1.1.0 artifact ships Kotlin 2.2.21 metadata and JVM 21
// bytecode (D92 §0); the desktop lane pins Kotlin 2.0.21 + JDK 17 and its
// compiler refuses to even READ the artifact. The gateway is a standalone
// Gradle build (the proven W6-N2 spike pattern) that the app spawns as a
// child process; the app↔gateway line protocol lives in the SHARED
// GatewayContract (compiled by all three trees), so the boundary cannot
// drift. The vanilla lanes stay untouched: nothing here changes their
// classpaths, toolchains, or outputs.
//
// Dependency pin: computer.iroh:iroh 1.1.0 — the newest iroh Kotlin artifact
// published to Maven Central (D92 §0; the plan's "1.3.x" exists only as the
// Rust crate). Kotlin 2.2.21 is REQUIRED (2.0.x refuses the artifact's
// metadata); JDK 21 is REQUIRED (the artifact's class files are Java 21).
// The E:-resident JDK 21 is selected by JAVA_HOME when building/running;
// the desktop lane's pinned JDK 17 toolchain is untouched.
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.pocketrealm"
version = "0.110.0-alpha"

repositories {
    mavenCentral()
}

dependencies {
    implementation("computer.iroh:iroh:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // The shared core (compiled below straight from the Android tree) uses
    // org.json — the exact artifact the Android JVM tests run it against.
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// The shared pure-Kotlin core compiles here straight from the Android app
// tree (the desktop lane's shared-sources.json mechanism, restated for the
// gateway). The list mirrors desktop/shared-sources.json's discovery/iroh
// tree; a drift fails tests/test_gateway_shared_sources_manifest.py.
val sharedAndroidRoot = rootProject.file("../android/app/src/main/java/com/pocketrealm")
val sharedCoreSources = objects.sourceDirectorySet("sharedCore", "pocket realm shared core")
sharedCoreSources.srcDir(sharedAndroidRoot)
sharedCoreSources.include(
    "client/ClientFlavor.kt",
    "discovery/iroh/AuthStreamFilter.kt",
    "discovery/iroh/GatewayContract.kt",
    "discovery/iroh/IrohJoinDescriptor.kt",
    "discovery/iroh/PortMux.kt",
    "discovery/iroh/RealmListRewrite.kt",
    "discovery/nostr/NostrJson.kt",
)
sourceSets.main {
    kotlin.source(sharedCoreSources)
}

// The artifact's Gradle module metadata declares JVM 21 minimum — build and
// run on the E:-resident JDK 21 (JAVA_HOME selects it; the launch posture is
// documented in the module doc and exercised by GatewaySubprocessE2E — the
// round-6 R3-r6-2 cite fix: the old "see scripts/iroh_n3_*" pointed at
// files that never existed).
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pocketrealm.gateway.GatewayMainKt")
    // JNA extracts the bundled win32-x86-64 native from the jar at load
    // time; pin the extraction dir to E: so nothing lands in the OS temp dir.
    applicationDefaultJvmArgs = listOf("-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna")
}

// One self-contained jar: the app spawns `java -jar gateway-all.jar <args>`
// with the E:-resident JDK 21's java.exe. No shadow plugin — a plain Jar
// task over the runtime classpath (unzipped into the jar) is enough here.
val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("gateway")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.pocketrealm.gateway.GatewayMainKt" }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

tasks.named("assemble") { dependsOn(fatJar) }

tasks.test {
    dependsOn(fatJar) // the subprocess E2E launches the CURRENT jar
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // The in-process E2E binds loopback ports; give it room.
    systemProperty("jna.tmpdir", "E:/pocket_realm_toolchain/tmp/jna")
    // R3-r3-1: the forked test workers pin java.io.tmpdir to E: too (the
    // D56 posture — the sibling lanes pin the same).
    // The payload-capture evidence run (-Ppocket.payload.dump=<dir> on the
    // gradle command is NOT visible to test workers — propagate it).
    System.getProperty("pocket.payload.dump")?.let {
        systemProperty("pocket.payload.dump", it)
    }
    systemProperty("java.io.tmpdir", "E:/pocket_realm_toolchain/tmp")
}
