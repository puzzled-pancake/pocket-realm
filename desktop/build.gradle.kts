/*
 * Pocket Realm desktop (Windows) build.
 *
 * Coexistence contract with the Android app (see the approved Windows-port
 * plan): shared domain sources are NOT copied or moved — they are compiled
 * straight from the Android app tree, restricted to the pinned file list in
 * shared-sources.json (enforced android-free by
 * tests/test_desktop_shared_manifest.py). Desktop twins for Android-coupled
 * files (same package + name, JVM implementations) live in
 * src/main/kotlin so the shared sources compile unmodified.
 *
 * Version pins mirror the Android build where shared code is compiled:
 * Kotlin 2.0.21 (AGP 9.3.1 built-in), kotlinx-coroutines 1.8.1, detekt
 * 1.23.7, org.json 20240303 (the exact artifact the Android JVM test suite
 * runs shared code against). Compose Multiplatform 1.7.1 is the release
 * aligned with Kotlin 2.0.21. The wrapper is pinned to Gradle 8.10.2 —
 * the newest line KGP 2.0.x is certified for (the Android build's Gradle
 * 9.5.0 wrapper is NOT reused for that reason).
 */
import groovy.json.JsonSlurper

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    application
}

group = "com.pocketrealm"
version = "0.103.0-alpha"

val sharedManifestFile = file("shared-sources.json")
val sharedManifest: Map<*, *> =
    (JsonSlurper().parse(sharedManifestFile) as Map<*, *>)
val androidTreeRoot = rootProject.file("../" + sharedManifest["android_tree_root"])
val sharedPaths: List<String> = (sharedManifest["shared"] as List<*>).map { it.toString() }
val sharedAndroidSources = fileTree(androidTreeRoot) { include(sharedPaths) }
val androidTestTreeRoot = rootProject.file("../" + sharedManifest["android_test_tree_root"])
val sharedTestPaths: List<String> = (sharedManifest["shared_tests"] as List<*>).map { it.toString() }
val sharedAndroidTestSources = fileTree(androidTestTreeRoot) { include(sharedTestPaths) }
// Debug-source-set synthetic client archive fixtures (proprietary-free zip/7z/
// Inno generators) shared by both builds' JVM tests.
val androidDebugTreeRoot = rootProject.file("../" + sharedManifest["android_debug_tree_root"])
val sharedDebugPaths: List<String> = (sharedManifest["shared_debug"] as List<*>).map { it.toString() }

// The native lanes' DLL output dirs (realm runtimes + the desktop SQLite
// seam), for every JVM entry point that loads them by name.
val nativeLibraryPath: String = listOf(
    "../native/.build-win-x86_64/pocket-runtime-build",
    "../native/.build-win-x86_64/sqlite-seam-build",
).joinToString(File.pathSeparator) { rootProject.projectDir.resolve(it).normalize().toString() }

kotlin {
    jvmToolchain(17)
}

// Shared android-tree sources join the compilation at the task level: a
// filtered fileTree as a srcDir gets its matched FILES treated as source
// directories on Gradle 8.10, while source() accepts them correctly.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    source(sharedAndroidSources)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    source(sharedAndroidTestSources)
    // Synthetic fixtures compile into the test source set.
    source(fileTree(androidDebugTreeRoot) { include(sharedDebugPaths) })
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("detekt.yml")
    // Only desktop-owned sources. Shared android-tree files are detekt-gated
    // by the Android build (:app:detekt, with its baseline); each file is
    // linted by exactly one build.
    source.setFrom("src/main/kotlin")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")
    // Inno Setup LZMA decoder + synthetic archive fixtures (same codecs the
    // Android app's importer uses).
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass = "com.pocketrealm.desktop.MainKt"
}

tasks.named<Test>("test") {
    // The shared JNI shims loadLibrary() by name; the native lanes' DLL
    // output dirs must be searchable. Tests skip cleanly when absent.
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}

// Phase-3 bring-up: seed the four realm databases from the pinned
// transcripts into %LOCALAPPDATA% (see SeedRealmData.kt).
tasks.register<JavaExec>("seedRealmData") {
    group = "bring-up"
    description = "Seed the four realm databases into %LOCALAPPDATA% from the pinned transcripts."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SeedRealmDataKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    if (project.hasProperty("stagingRoot")) {
        args(project.property("stagingRoot"))
    }
}

// Phase-3 gate: boot realmd in-process, prove 127.0.0.1:3724 accepts a
// connection, stop cleanly (see BootRealmd.kt).
tasks.register<JavaExec>("bootRealmd") {
    group = "bring-up"
    description = "Boot realmd in-process and verify the 3724 listener + clean stop."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.BootRealmdKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}

// Dev/probe lane: boot realmd and HOLD it up for external probes.
tasks.register<JavaExec>("realmdHold") {
    group = "bring-up"
    description = "Boot realmd and hold it up (60s) for external probes."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.RealmdHoldKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}

// Phase-3 gate: protocol-level SRP6 authentication against the live
// realmd (verifier row seeded directly into classicrealmd.sqlite).
tasks.register<JavaExec>("authGate") {
    group = "bring-up"
    description = "Boot realmd and prove a 1.12 SRP6 logon handshake end-to-end."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.AuthGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}
// Phase-4 gate: full world boot against the prepared data (run
// tools/win_prepare_data.py first), 8085 listener + clean save/stop.
tasks.register<JavaExec>("bootWorld") {
    group = "bring-up"
    description = "Boot database+realm+world in-process; verify READY, 8085, save/stop."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.BootWorldKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}
// Phase-4d bring-up: boot the full stack + launch WoW.exe against it
// (interactive; press Enter in the console to save + stop).
tasks.register<JavaExec>("launchClient") {
    group = "bring-up"
    description = "Boot database+realm+world, then launch WoW.exe at loopback."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.LaunchClientKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    standardInput = System.`in`
    if (project.hasProperty("clientDir")) {
        jvmArgs("-DclientDir=${project.property("clientDir")}")
    }
}


