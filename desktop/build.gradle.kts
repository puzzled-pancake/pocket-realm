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

kotlin {
    jvmToolchain(17)
}

// Shared android-tree sources join the compilation at the task level: a
// filtered fileTree as a srcDir gets its matched FILES treated as source
// directories on Gradle 8.10, while source() accepts them correctly.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    source(sharedAndroidSources)
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
    // Inno Setup LZMA decoder (same codec the Android app's importer uses).
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass = "com.pocketrealm.desktop.MainKt"
}
