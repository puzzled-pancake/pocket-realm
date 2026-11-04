// iroh gateway — the desktop transport process (host + join).
//
// WHY A SEPARATE PROCESS (and not code inside the desktop lane): the
// computer.iroh:iroh 1.1.0 artifact ships Kotlin 2.2.21 metadata and JVM 21
// bytecode; the desktop lane pins Kotlin 2.0.21 + JDK 17 and its
// compiler refuses to even READ the artifact. The gateway is a standalone
// Gradle build (the proven spike pattern) that the app spawns as a
// child process; the app↔gateway line protocol lives in the SHARED
// GatewayContract (compiled by all three trees), so the boundary cannot
// drift. The vanilla lanes stay untouched: nothing here changes their
// classpaths, toolchains, or outputs.
//
// Dependency pin: computer.iroh:iroh 1.1.0 — the newest iroh Kotlin artifact
// published to Maven Central (the plan's "1.3.x" exists only as the
// Rust crate). Kotlin 2.2.21 is REQUIRED (2.0.x refuses the artifact's
// metadata); JDK 21 is REQUIRED (the artifact's class files are Java 21).
// The E:-resident JDK 21 is selected by JAVA_HOME when building/running;
// the desktop lane's pinned JDK 17 toolchain is untouched.
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.pocketrealm"
version = "0.112.0"

// W6-N4 code-red R4-r3-1: the -Ppocket.seat whole-build redirect is
// applied HERE (build-script body — always executed, configuration-cache
// or not) IN ADDITION to settings.gradle.kts's gradle.beforeProject hook:
// a seat run once wrote the CANONICAL class tree when the settings-only
// hook failed to apply under a split daemon/configuration state. Seat
// runs also compile IN-PROCESS — shared Kotlin daemon sessions were the
// concurrent-corruption root (NoClassDefFoundError mid-run). Default
// runs (no property) keep the canonical build/ layout and the daemon.
val pocketSeat = providers.gradleProperty("pocket.seat").orNull?.takeIf { it.isNotBlank() }
if (pocketSeat != null) {
    layout.buildDirectory.set(file("build-seat-$pocketSeat"))
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerExecutionStrategy.set(
            org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy.IN_PROCESS,
        )
    }
}

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
    "discovery/iroh/InviteCode.kt",
    "discovery/iroh/JoinAdmission.kt",
    "discovery/iroh/PortMux.kt",
    "discovery/iroh/RealmListRewrite.kt",
    "discovery/iroh/SignupProtocol.kt",
    "discovery/nostr/NostrJson.kt",
)
sourceSets.main {
    kotlin.source(sharedCoreSources)
}

// The artifact's Gradle module metadata declares JVM 21 minimum — build and
// run on the E:-resident JDK 21 (JAVA_HOME selects it; the launch posture is
// documented in the module doc and exercised by GatewaySubprocessE2E — the
// cite fix: the old "see scripts/iroh_n3_*" pointed at
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

// the docker reconnect leg's fake-stack jar — the TEST source
// set's entrypoint (FakeRealmServer/FakeEchoServer + the container
// main), never a production Main-Class. The ReconnectDockerE2E mounts
// BOTH jars into the host container (/app, read-only).
val fakeStackJar = tasks.register<Jar>("fakeStackJar") {
    archiveBaseName.set("gateway-fakestack")
    archiveClassifier.set("test-tools")
    archiveVersion.set("") // the container classpath names the exact file
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.pocketrealm.gateway.FakeStackMain" }
    from(sourceSets.test.get().output)
}

tasks.named("assemble") { dependsOn(fatJar) }

tasks.test {
    // The docker reconnect leg mounts the CURRENT fake-stack jar.
    dependsOn(fakeStackJar)
    dependsOn(fatJar) // the subprocess E2E launches the CURRENT jar
    // W6-N4 code-red R5-N4-r5-2 (the desktop lane's R6-N4-r3-2 ruling,
    // gateway twin): the archived logs' counts markers must come from a
    // TRACKED emitter — never appended text. doLast aggregates the
    // focused/canonical JUnit XML into the SAME marker shapes the exit
    // record quotes: the hermetic/flags-ON suite marker (with the
    // opt-in flags state) and the reader-pin counts marker.
    doLast {
        val resultsDir = reports.junitXml.outputLocation.get().asFile
        val xmls = resultsDir.walkTopDown()
            .filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
            .toList()
        if (xmls.isEmpty()) return@doLast
        var tests = 0
        var failures = 0
        var skipped = 0
        var classes = 0
        for (xml in xmls.sortedBy { it.name }) {
            try {
                val head = xml.useLines { lines ->
                    lines.firstOrNull { it.contains("<testsuite") }
                } ?: continue
                fun attr(name: String): Int =
                    Regex("$name=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                tests += attr("tests")
                failures += attr("failures") + attr("errors")
                skipped += attr("skipped")
                classes += 1
            } catch (_: Exception) {
                // an unreadable XML counts as absent, never as a pass
            }
        }
        val flags = when {
            System.getProperty("pocket.reconnect.relay") == "true" ||
                System.getProperty("pocket.reconnect.docker") == "true" -> "on"
            else -> "off"
        }
        println(
            "# N4-GATEWAY-SUITE-COUNTS tests=$tests skipped=$skipped " +
                "failures=$failures flags=$flags",
        )
        println("# N3-GATEWAY-COUNTS tests=$tests classes=$classes failures=$failures")
    }
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // The in-process E2E binds loopback ports; give it room.
    systemProperty("jna.tmpdir", "E:/pocket_realm_toolchain/tmp/jna")
    // the forked test workers pin java.io.tmpdir to E: too (the
    // posture — the sibling lanes pin the same).
    // The payload-capture evidence run (-Ppocket.payload.dump=<dir> on the
    // gradle command is NOT visible to test workers — propagate it).
    System.getProperty("pocket.payload.dump")?.let {
        systemProperty("pocket.payload.dump", it)
    }
    // the reconnect proofs' opt-in flags ride the same channel
    // (absent = the suite stays hermetic and the legs SKIP honestly).
    listOf("pocket.reconnect.relay", "pocket.reconnect.docker").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    // Code-red R4-5/R5-2: concurrent seats on the shared checkout must
    // not delete each other's in-flight binary results (the
    // NoSuchFileException in-progress-results-generic.bin class) — a
    // seat flag redirects BOTH output locations the way -Ppocket.focus
    // does on the desktop lane. ONE WRITER per (module, results dir):
    // seats running concurrently pass distinct -Ppocket.seat=<id> values
    // (their port-bound E2Es then SKIP honestly while the other seat
    // holds 3724/8085 — see gamePortsFree in the test tree).
    providers.gradleProperty("pocket.seat").orNull?.takeIf { it.isNotBlank() }?.let { seat ->
        val seatResults = layout.buildDirectory.dir("test-results-seat-$seat")
        binaryResultsDirectory.set(seatResults.map { it.dir("binary") })
        reports.junitXml.outputLocation.set(seatResults)
        // locateFatJar's seat awareness reads this (a seat run spawns
        // the jar its OWN build-seat-<id> produced).
        systemProperty("pocket.seat", seat)
    }
    systemProperty("java.io.tmpdir", "E:/pocket_realm_toolchain/tmp")
}
