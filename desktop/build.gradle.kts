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
    // Desktop-owned main AND test sources. Shared android-tree files are
    // detekt-gated by the Android build (:app:detekt, with its baseline);
    // each file is linted by exactly one build — desktop tests are owned
    // here (the Android :app:detekt never sees them).
    source.setFrom("src/main/kotlin", "src/test/kotlin")
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
    // Win32 auto-login (SendInput into the launched WoW.exe): the JVM has
    // no FFI at 17, and JNA keeps the credentials in-process (no helper
    // exe command line). JDK 22+ would allow the FFM API instead.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass = "com.pocketrealm.desktop.MainKt"
}

tasks.named<Test>("test") {
    // The shared JNI shims loadLibrary() by name; the native lanes' DLL
    // output dirs must be searchable. Tests skip cleanly when absent —
    // unless -PrequireNatives demands them (the CI native lane), which
    // flips every DLL-guarded assumeTrue into a hard failure.
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    if (project.hasProperty("requireNatives")) {
        systemProperty("pocketrealm.requireNatives", "true")
    }
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

// Phase-5 gate: the world-chat injection bridge surface (honest
// failures without a client; the conversational half is interactive).
tasks.register<JavaExec>("whisperGate") {
    group = "bring-up"
    description = "Boot the world and prove the chat-injection bridge contract."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.WhisperGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
}

// Phase-6 packaging: jpackage app image carrying the native lanes
// (realm DLLs + sqlite seam), the pinned seed transcripts, and the
// build provenance. Inputs are machine-local build outputs — the task
// skips honestly (not fails) when the native lanes have not been built.

// Fat jar: jpackage's single -- input classpath needs the whole app
// (Compose + coroutines + json) in one artifact. module-info.class files
// (root and multi-release) never merge correctly; signatures and index
// lists are per-jar artifacts that would poison the merged manifest.
// Multi-Release: true keeps the META-INF/versions/9+ classes several
// dependencies carry actually loadable instead of inert dead weight.
val appJar = tasks.register<Jar>("appJar") {
    archiveBaseName.set("pocketrealm-desktop-app")
    manifest {
        attributes("Main-Class" to "com.pocketrealm.desktop.MainKt")
        attributes("Multi-Release" to "true")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.named("main").get().output)
    from({
        configurations.named("runtimeClasspath").get().map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude(
        "module-info.class",
        "META-INF/versions/**/module-info.class",
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "META-INF/*.EC", "META-INF/INDEX.LIST",
    )
}

tasks.register("packageApp") {
    group = "bring-up"
    description = "Build the jpackage app image with DLLs, seeds, and provenance."
    dependsOn(appJar)
    doLast {
        val runtimeDir = rootProject.projectDir.resolve("../native/.build-win-x86_64/pocket-runtime-build")
        val seamDir = rootProject.projectDir.resolve("../native/.build-win-x86_64/sqlite-seam-build")
        val stagingRoot = rootProject.projectDir.resolve("../native/.build-o09-x86_64/realm-staging-sqlite")
        val jarFile = appJar.get().outputs.files.singleFile
        val seeds = listOf("classicrealmd", "classiccharacters", "classiclogs", "classicmangos")
            .map { stagingRoot.resolve("assets/seed/$it.sqlz") }
        // LLM-lane assets the conf resolves from the app dir at runtime
        // (ServerRuntimeFiles.resolveBundledAsset searches java.library.path
        // first — the jpackage $APPDIR): the Mozilla CA bundle for the
        // native HTTPS client's TLS verification and the lore card index.
        val llmAssets = listOf(
            "llm/cacert.pem",
            "lore/lore_cards_v112.jsonl",
        ).map { rootProject.projectDir.resolve("../android/app/src/main/assets/$it").normalize() }
        val allInputs = listOf(
            jarFile,
            runtimeDir.resolve("pocket_realmd_runtime.dll"),
            runtimeDir.resolve("pocket_world_runtime.dll"),
            seamDir.resolve("pocket_sqlite.dll"),
            stagingRoot.resolve("BUILD_PROVENANCE.json"),
        ) + seeds + llmAssets + vcRuntimeDlls()
        val missing = allInputs.filter { !it.isFile }
        if (missing.isNotEmpty()) {
            val listing = missing.joinToString(System.lineSeparator()) { "  $it" }
            logger.lifecycle("packageApp SKIPPED (missing inputs):$listing")
            return@doLast
        }
        val imageInput = layout.buildDirectory.dir("package/input").get().asFile
        imageInput.deleteRecursively()
        imageInput.mkdirs()
        allInputs.forEach { it.copyTo(imageInput.resolve(it.name), overwrite = true) }
        val outDir = layout.buildDirectory.dir("package").get().asFile
        val image = outDir.resolve("PocketRealm")
        // jpackage refuses an existing app-image destination; a stale
        // image from a previous run (or a locked exe) must be cleared,
        // with an actionable message when Windows still holds it open.
        if (image.exists() && !image.deleteRecursively()) {
            throw GradleException(
                "cannot remove stale app image $image — close PocketRealm.exe and retry",
            )
        }
        outDir.mkdirs()
        exec {
            commandLine(
                "${System.getProperty("java.home")}/bin/jpackage.exe",
                "--type", "app-image",
                "--name", "PocketRealm",
                "--input", imageInput.absolutePath,
                "--main-jar", jarFile.name,
                "--main-class", "com.pocketrealm.desktop.MainKt",
                "--dest", outDir.absolutePath,
                // $APPDIR is jpackage's launcher cfg placeholder expanded
                // to the absolute app dir at launch. The Windows launcher
                // does NOT chdir, so "." resolved against the caller's cwd
                // and every loadLibrary-by-name failed in the image.
                "--java-options", "-Djava.library.path=\$APPDIR",
                "--win-console",
            )
        }
        applyLongPathAwareManifest(image.resolve("PocketRealm.exe"))
        logger.lifecycle("app image: $image")
    }
}


/**
 * The VC runtime DLLs the realm DLLs import (MSVCP140/VCRUNTIME140[_1]) —
 * app-local deployment from the VS redist tree, so the image runs on
 * machines without the VC redistributable installed. Returns an empty
 * list when the redist tree cannot be located (the DLLs stay missing and
 * packageApp reports them as missing inputs, never silently unpackaged).
 */
fun vcRuntimeDlls(): List<File> {
    val vswhere = File(
        System.getenv("ProgramFiles(x86)") ?: return emptyList(),
        "Microsoft Visual Studio/Installer/vswhere.exe",
    )
    if (!vswhere.isFile) return emptyList()
    val install = providers.exec {
        commandLine(vswhere.absolutePath, "-latest", "-products", "*", "-property", "installationPath")
    }.standardOutput.asText.get().trim().lineSequence().firstOrNull() ?: return emptyList()
    val redistRoot = File(install, "VC/Redist/MSVC")
    val crt = redistRoot.listFiles()
        ?.mapNotNull { version -> version.resolve("x64").listFiles()?.firstOrNull { dir -> dir.name.startsWith("Microsoft.VC") } }
        ?.firstOrNull() ?: return emptyList()
    return listOf("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll", "concrt140.dll")
        .map { crt.resolve(it) }
        .filter { it.isFile }
}


/**
 * longPathAware for the launcher exe (data preparation and storage live
 * under %LOCALAPPDATA%; deep generation trees can exceed MAX_PATH for
 * native code). jpackage's launcher embeds its own manifest and is created
 * READ-ONLY, so mt.exe -outputresource fails; tools/win_manifest_longpath.py
 * merges the setting through kernel32's UpdateResource and restores the
 * attribute. Honest skip when python/pefile are unavailable.
 */
fun applyLongPathAwareManifest(exe: File) {
    val helper = rootProject.projectDir.resolve("../tools/win_manifest_longpath.py")
    val result = runCatching {
        ProcessBuilder("python", helper.absolutePath, exe.absolutePath)
            .redirectErrorStream(true)
            .start()
    }
    val process = result.getOrElse { failure ->
        logger.lifecycle("packageApp: longPathAware skipped (no python: ${failure.message})")
        return
    }
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    logger.lifecycle("packageApp: ${output.trim()}")
}
