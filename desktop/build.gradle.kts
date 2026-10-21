/*
 * Pocket Realm desktop (Windows) build.
 *
 * Coexistence contract with the Android app: shared domain sources are NOT
 * copied or moved — they are compiled
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
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    application
}

group = "com.pocketrealm"
version = "0.110.0"

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
// seam), for every JVM entry point that loads them by name. The runtimes
// are the MARIADB lane's build (the backend the desktop ships): that lane
// builds in its own -mariadb-suffixed root (tools/build_win_realm_runtime.
// py select_win_flavor), so a sqlite dev-lane rebuild can never swap the
// DLLs under the JVM's library path.
val nativeLibraryPath: String = listOf(
    "../native/.build-win-x86_64-mariadb/pocket-runtime-build",
    "../native/.build-win-x86_64/sqlite-seam-build",
).joinToString(File.pathSeparator) { rootProject.projectDir.resolve(it).normalize().toString() }

// The mariadb relink made both staged realm runtime DLLs import
// libmysql.dll (their PE import tables pin it in
// schemas/realm-runtime-lockfile-mariadb-win.json), and Windows
// dependent-DLL resolution IGNORES -Djava.library.path — the loader
// searches PATH. Every JVM entry point below therefore also carries the
// runtime dir on the child process PATH, so the documented bare
// invocations boot without a manual PATH prepend; -Djava.library.path
// stays for the by-name loadLibrary() lookups themselves.
val nativeRuntimeDir: File =
    rootProject.projectDir.resolve("../native/.build-win-x86_64-mariadb/pocket-runtime-build").normalize()

fun nativeRuntimePathEnv(): Map<String, String> = mapOf(
    "PATH" to nativeRuntimeDir.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""),
)

/**
 * flip (retirement) the per-flavor MariaDB win runtime roots — the
 * vanilla lane keeps its historical dir; tbc/wotlk get their own
 * `native/.build-win-x86_64-<flavor>-mariadb/pocket-runtime-build` (the
 * tools/build_win_realm_runtime.py --flavor <f> --database-backend mariadb
 * output, lane lockfiles
 * schemas/realm-runtime-lockfile-mariadb-win-<flavor>.json). The rpHost
 * harness task routes via -PrpFlavor=<tbc|wotlk>; every default (no flag)
 * stays byte-identical to the vanilla lane.
 */
val flavorRuntimeDirs: Map<String, String> = mapOf(
    "tbc" to "../native/.build-win-x86_64-tbc-mariadb/pocket-runtime-build",
    "wotlk" to "../native/.build-win-x86_64-wotlk-mariadb/pocket-runtime-build",
)

fun flavorNativeLibraryPath(flavor: String?): String {
    val dir = flavor?.let { flavorRuntimeDirs[it.lowercase()] }
        ?: return nativeLibraryPath
    return listOf(dir, "../native/.build-win-x86_64/sqlite-seam-build")
        .joinToString(File.pathSeparator) {
            rootProject.projectDir.resolve(it).normalize().toString()
        }
}

fun flavorRuntimePathEnv(flavor: String?): Map<String, String> {
    if (flavor == null) return nativeRuntimePathEnv()
    val dir = rootProject.projectDir.resolve(
        flavorRuntimeDirs[flavor.lowercase()] ?: return nativeRuntimePathEnv(),
    ).normalize()
    return mapOf(
        "PATH" to dir.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""),
    )
}

kotlin {
    jvmToolchain(17)
}

// DESK-39 cross-lane note: okhttp 5.3.0 (the Android app's pin, shared by
// addons/AddonOperationToken.kt) is built with Kotlin 2.2, whose module
// metadata the pinned Kotlin 2.0.21 compiler refuses to read — the whole
// desktop module fails to compile ("incompatible version of Kotlin ... 2.2.0,
// expected 2.0.0"). Two scoped mitigations, keeping the artifact pin:
//  1. hold the TRANSITIVE kotlin-stdlib at the compiler's own 2.0.21 (okhttp
//     drags 2.2.21 in; a module must compile against its compiler's stdlib),
//  2. skip the metadata version gate so the compiler ACCEPTS okhttp/okio's
//     2.2-built descriptors (the surface the desktop compiles against —
//     okhttp3.Call et al. — is stable plain classes/interfaces).
// If a future dependency genuinely needs 2.2 semantics this is the first
// thing to retire — by moving the desktop to a Kotlin that reads 2.2.
configurations.named("compileClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
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

// Shared android-tree test resources (the golden fixtures the shared tests
// read via getResourceAsStream) join the desktop test source set, mirroring
// how the shared test SOURCES above join its compilation.
sourceSets.named("test") {
    resources.srcDir(rootProject.file("../android/app/src/test/resources"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("detekt.yml")
    // Baselines the desktop lane's known detekt debt (the
    // FULL main+test set, matching the Android :app:detekt posture — until
    // this round the file carried only the RP-harness debt and the gate sat
    // red on ~496 findings elsewhere). Entries are written by
    // `gradlew detektBaseline` ONLY (never hand-edited); new issues still
    // fail the build; retire entries by fixing the code, not by growing
    // this file.
    baseline = file("detekt-baseline.xml")
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
    // DESK-35/64: the desktop RAR lane. The Android lane reads RAR4/RAR5
    // through the libarchive JNI library (APK-only); the JVM twin reads them
    // through junrar's pure-Java reader (unrar-license caveat documented in
    // importer/RarArchiveSource.kt).
    implementation("com.github.junrar:junrar:8.1.1")
    // DESK-39: the shared addons/AddonOperationToken cancellation fence is
    // okhttp-Call-typed (attach/detach); the desktop lane compiles it against
    // the same artifact version the Android app pins. The GitHub install
    // twin itself (DesktopAddonRepository) rides java.net.http.
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    // BIP-340 Schnorr for the shared discovery/nostr module (the
    // Nostr listing signatures). Built with Kotlin 2.1 — the SAME scoped
    // mitigations as okhttp above carry it (stdlib held at 2.0.21 +
    // -Xskip-metadata-version-check). The -jvm artifact is the Kotlin API
    // only; the Windows/mingw native rides the separate secp256k1-kmp-jni-
    // jvm companion pinned right below.
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jvm:0.16.0")
    // The JNI companion ships SEPARATELY (the artifact's own error copy
    // names it): this drags secp256k1-kmp-jni-jvm-mingw's Windows native,
    // which NativeLoader extracts to the temp dir and System.load()s. The
    // temp dir is E:-pinned on EVERY launch lane below (the loader reads
    // fr.acinq.secp256k1.tmpdir first, java.io.tmpdir as fallback) — the
    // test-JVM pin alone covered only the unit-test workers, not `run` or
    // the jpackage image (R3-1/R7-1).
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.16.0")
    // Win32 auto-login (SendInput into the launched WoW.exe): the JVM has
    // no FFI at 17, and JNA keeps the credentials in-process (no helper
    // exe command line). JDK 22+ would allow the FFM API instead.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // DESK-39: the shared addons/AddonOperationSafetyTest drives the
    // cancellation fence against a stalled local HTTP server.
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
}

application {
    mainClass = "com.pocketrealm.desktop.MainKt"
    // Same native library path every server gate carries (DESK-lane parity):
    // without it the app's plain `run` crashes on first WorldNative touch
    // (UnsatisfiedLinkError: no pocket_world_runtime) and the save-exit
    // drain shuts the session down.
    applicationDefaultJvmArgs = listOf(
        "-Djava.library.path=$nativeLibraryPath",
        // the secp256k1 JNI loader extracts its Windows native
        // to the FIRST set property below (fr.acinq.secp256k1.tmpdir) —
        // without this pin the first host Schnorr sign writes the 1.4 MB
        // DLL to %TEMP% on C: (hard rule 1). The test workers carry the
        // same pin through their own java.io.tmpdir systemProperty.
        "-Dfr.acinq.secp256k1.tmpdir=E:/pocket_realm_toolchain/tmp",
    )
}

// `run` rides applicationDefaultJvmArgs, which cannot carry an
// environment; the same libmysql.dll PATH contract as every lane below.
tasks.named<JavaExec>("run") {
    environment(nativeRuntimePathEnv())
}


// ---------------------------------------------------------------------------
// DESK-115: lockfile-pin verification before the runtime ships.
//
// NativeRuntimeFreshness (shared-sources.json) is Android's staleness fence;
// on desktop it was compiled but never used, so packageApp jpackaged
// whatever three DLLs were staged and a stale-but-ABI-stable
// pocket_world_runtime.dll shipped silently. These legs feed the
// already-compiled shared evaluator with the win-lane lockfile
// (schemas/realm-runtime-lockfile-mariadb-win.json — the MARIADB backend
// the app's shipped default boots on), schemas/sources.json and
// — for packageApp — the staging provenance plus freshly rehashed DLL bytes,
// failing the build on a non-empty Verdict with its REMEDY copy. Skippable
// ONLY via -PpocketSkipNativeFreshness=true, which skips loudly (the driver
// refuses any other value as ambiguous).
// ---------------------------------------------------------------------------

val pocketSkipNativeFreshness: String =
    (project.findProperty("pocketSkipNativeFreshness") as String?).orEmpty()

fun nativeRuntimeFenceTask(name: String, staged: Boolean, laneLabel: String) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description =
            if (staged) {
                "Rehash the staged realm-runtime DLLs against the win-lane lockfile " +
                    "(fail the build on staleness)."
            } else {
                "Check the win-lane lockfile commits against schemas/sources.json " +
                    "(pins-only staleness fence)."
            }
        classpath = sourceSets.named("main").get().runtimeClasspath
        mainClass.set("com.pocketrealm.desktop.NativeRuntimeFenceKt")
        // The fence reads files only, but keeps the same child-process PATH
        // contract as every lane (the DLLs live beside what it hashes).
        environment(nativeRuntimePathEnv())
        args(
            "--repo-root", rootProject.projectDir.resolve("..").normalize().absolutePath,
            "--lane", laneLabel,
            "--mode", if (staged) "staged" else "pins",
            "--skip", pocketSkipNativeFreshness,
        )
    }

val validateNativeRuntimePins = nativeRuntimeFenceTask(
    "validateNativeRuntimePins",
    staged = false,
    laneLabel = "desktop pins-only (mariadb-win)",
)

// The packageApp leg: full staged-bytes verification over the exact four
// DLLs the packaging step copies (the lockfile's artifact rows — the two
// realm runtimes, the sqlite seam, and libmysql.dll, each with its own
// size/sha256 pin) plus the pinned seed transcripts, so the fence and the
// packaging can never disagree about what ships.
val validateNativeRuntimeFreshness = nativeRuntimeFenceTask(
    "validateNativeRuntimeFreshness",
    staged = true,
    laneLabel = "desktop packageApp (mariadb-win)",
)

tasks.named<Test>("test") {
    // forked test WORKER JVMs take java.io.tmpdir from
    // the OS %TEMP% on C: (org.gradle.jvmargs only configures the daemon) -
    // every @TempDir/JUnit scratch root (including SQLite datadir fixtures)
    // must live under the E: toolchain root (plan section 1 hard rule 1).

    // The shared JNI shims loadLibrary() by name; the native lanes' DLL
    // output dirs must be searchable. Tests skip cleanly when absent —
    // unless -PrequireNatives demands them (the CI native lane), which
    // flips every DLL-guarded assumeTrue into a hard failure.
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    systemProperty("java.io.tmpdir", "E:/pocket_realm_toolchain/tmp")
    // The same dependent-DLL (libmysql.dll) PATH contract as the lanes:
    // the DLL-loading test legs would die on UnsatisfiedLinkError with
    // -Djava.library.path alone.
    environment(nativeRuntimePathEnv())
    if (project.hasProperty("requireNatives")) {
        systemProperty("pocketrealm.requireNatives", "true")
    }
    // Isolation valve for parallel development lanes sharing this project
    // dir: concurrent `test` invocations interleave on build/test-results
    // (one lane's cleanup deletes another's open output.bin and the
    // executors die on the broken stream). When -PtestResultsDir is passed,
    // this run's results go there instead; default behavior is unchanged.
    // NOTE: must resolve the -P gradle property, never project.findProperty:
    // the java plugin registers a convention project property of the same
    // name (a File — buildDir/test-results), so findProperty never returns
    // null and the String cast throws ClassCastException at task creation.
    providers.gradleProperty("testResultsDir").orNull?.let { resultsDir ->
        binaryResultsDirectory.set(file("$resultsDir/binary"))
        reports.junitXml.outputLocation.set(file(resultsDir))
    }
    // The world is one-lifetime-per-process (DESK-25): a second in-process
    // start refuses with WRONG_STATE. The suite now boots the real world in
    // TWO legs (the DESK-29 presence leg + the FriendAccountNativeOpsTest
    // relay-op leg), so each test CLASS gets a fresh JVM - the one-lifetime
    // budget is per class, never contended by class order.
    forkEvery = 1
    // DESK-115 test leg: the pins-only staleness fence rides every test run,
    // so a lockfile that drifted from schemas/sources.json fails even where
    // the untracked staging bytes do not exist (mirrors the Android
    // validateNativeRuntimePins lane; the shared NativeRuntimeFreshnessTest
    // in shared_tests pins evaluate() itself).
    dependsOn(validateNativeRuntimePins)
}


// Supervisor-path gate: the bring-up gates drive the backend directly and
// poll; only the app drives the shared DurableRuntimeSupervisor, whose
// start contract (backend.start returns READY) once regressed unseen. This
// gate drives model.startRealm through the supervisor and fails on any
// non-clean cycle.
tasks.register<JavaExec>("supervisorStartGate") {
    group = "bring-up"
    description = "Boot db+realm+world through the supervisor; verify READY + clean save/stop."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SupervisorStartGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}

// GAP-2 (was DESKPROD-2) live lane: the restore -> proof -> ordinary-start
// sequence the fix plan records as the restore pair's deeper exit
// criterion. supervisorStartGate's ordinary cycle cannot also host it: the
// proof's READY spends the process's one world lifetime (DESK-25), so this
// sequence owns its own process. Run after supervisorStartGate.
tasks.register<JavaExec>("supervisorRestoreGate") {
    group = "bring-up"
    description = "Restore a backup through the production bots-disabled proof; " +
        "verify the spent-lifetime disclosure and the WRONG_STATE next-start refusal."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SupervisorRestoreGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}

// DESK-109/111 save-exit exercise gate: boots the stack through the
// supervisor, registers the PRODUCTION save-exit shutdown hook, and exits
// MID-SESSION (no window, no onCloseRequest) — then a second JVM reads the
// durable state back and fails unless the hook's drain saved the world and
// stopped cleanly (journal clean at STOPPED, clean world lifecycle record,
// no WAL sidecars). Needs the staged natives, seeds, and prepared data;
// missing inputs fail the gate loudly, never a fake pass.
val saveExitGateBoot = tasks.register<JavaExec>("saveExitGateBoot") {
    group = "bring-up"
    description = "Boot via the supervisor, register the save-exit hook, exit mid-session."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SaveExitGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
    systemProperty("saveExitGate.phase", "boot")
}

val saveExitGateVerify = tasks.register<JavaExec>("saveExitGateVerify") {
    group = "bring-up"
    description = "Verify the mid-session exit drained the stack cleanly (journal + lifecycle + sidecars)."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SaveExitGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
    systemProperty("saveExitGate.phase", "verify")
    mustRunAfter(saveExitGateBoot)
}

tasks.register("saveExitGate") {
    group = "bring-up"
    description =
        "Exit mid-session and prove the save-exit shutdown hook drained the booted stack."
    dependsOn(saveExitGateBoot, saveExitGateVerify)
}

// Bring-up: seed the four realm databases from the pinned
// transcripts into %LOCALAPPDATA% (see SeedRealmData.kt).
tasks.register<JavaExec>("seedRealmData") {
    group = "bring-up"
    description = "Seed the four realm databases into %LOCALAPPDATA% from the pinned transcripts."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.SeedRealmDataKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
    if (project.hasProperty("stagingRoot")) {
        args(project.property("stagingRoot"))
    }
}

// W3.5 login-topology verification: launch one STAGED genuine flavor
// client and drive Win32AutoLogin against its live login window.
// Usage: gradlew flavorLoginProbe -DclientDir="E:/Wow clients/<dir>" -DflavorId=tbc
tasks.register<JavaExec>("flavorLoginProbe") {
    group = "bring-up"
    description = "Drive Win32AutoLogin against a staged genuine flavor client's live login window."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.FlavorLoginProbeKt")
    systemProperty("clientDir", System.getProperty("clientDir") ?: "")
    systemProperty("flavorId", System.getProperty("flavorId") ?: "")
}

// Gate: boot realmd in-process, prove 127.0.0.1:3724 accepts a
// connection, stop cleanly (see BootRealmd.kt).
tasks.register<JavaExec>("bootRealmd") {
    group = "bring-up"
    description = "Boot realmd in-process and verify the 3724 listener + clean stop."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.BootRealmdKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}

// Dev/probe lane: boot realmd and HOLD it up for external probes.
tasks.register<JavaExec>("realmdHold") {
    group = "bring-up"
    description = "Boot realmd and hold it up (60s) for external probes."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.RealmdHoldKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}

// Gate: protocol-level SRP6 authentication against the live
// realmd (verifier row seeded directly into classicrealmd.sqlite).
tasks.register<JavaExec>("authGate") {
    group = "bring-up"
    description = "Boot realmd and prove a 1.12 SRP6 logon handshake end-to-end."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.AuthGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}
// Gate: full world boot against the prepared data (run
// tools/win_prepare_data.py first), 8085 listener + clean save/stop.
tasks.register<JavaExec>("bootWorld") {
    group = "bring-up"
    description = "Boot database+realm+world in-process; verify READY, 8085, save/stop."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.BootWorldKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}
// Bring-up: boot the full stack + launch WoW.exe against it
// (interactive; press Enter in the console to save + stop).
tasks.register<JavaExec>("launchClient") {
    group = "bring-up"
    description = "Boot database+realm+world, then launch WoW.exe at loopback."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.LaunchClientKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
    standardInput = System.`in`
    if (project.hasProperty("clientDir")) {
        jvmArgs("-DclientDir=${project.property("clientDir")}")
    }
}

// Gate: the world-chat injection bridge surface (honest
// failures without a client; the conversational half is interactive).
tasks.register<JavaExec>("whisperGate") {
    group = "bring-up"
    description = "Boot the world and prove the chat-injection bridge contract."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.WhisperGateKt")
    jvmArgs("-Djava.library.path=$nativeLibraryPath")
    environment(nativeRuntimePathEnv())
}

// W4.1: the per-flavor desktop end-to-end qualification probe (see
// FlavorE2eProbe.kt). -DflavorId + -DclientDir (or -Darchive / -DskipImport).
tasks.register<JavaExec>("flavorE2eProbe") {
    group = "bring-up"
    description = "Per-flavor desktop end-to-end: import -> prep -> boot READY -> auto-login -> Save & Exit."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.FlavorE2eProbeKt")
    val e2eFlavor = if (project.hasProperty("rpFlavor")) {
        project.property("rpFlavor")?.toString()
    } else null
    jvmArgs("-Djava.library.path=${flavorNativeLibraryPath(e2eFlavor)}")
    environment(flavorRuntimePathEnv(e2eFlavor))
    listOf("flavorId", "clientDir", "archive", "skipImport", "e2eUser", "e2ePass")
        .forEach { key ->
            if (project.hasProperty(key)) {
                jvmArgs("-D${key}=${project.property(key)}")
            }
        }
}

// Harness lane: the stdio JSON-line RP host (see RpHost.kt). One JSON op
// per stdin line -> one JSON response per stdout line; EOF drains with a
// save. -PrpClient=1 also launches WoW.exe (needs -PclientDir=...);
// -PrpAccount/-PrpPassword auto-type the login after the client is up.
tasks.register<JavaExec>("rpHost") {
    group = "bring-up"
    description = "Boot the stack and serve the world console ops over stdio JSON lines."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.pocketrealm.desktop.RpHostKt")
    // flip: -PrpFlavor=<tbc|wotlk> loads that lane's MariaDB win runtime
    // (the per-flavor build root); the default stays the vanilla lane.
    val rpFlavor = if (project.hasProperty("rpFlavor")) {
        project.property("rpFlavor")?.toString()
    } else null
    jvmArgs("-Djava.library.path=${flavorNativeLibraryPath(rpFlavor)}")
    environment(flavorRuntimePathEnv(rpFlavor))
    standardInput = System.`in`
    if (project.hasProperty("clientDir")) {
        jvmArgs("-DclientDir=${project.property("clientDir")}")
    }
    if (project.hasProperty("rpClient")) {
        jvmArgs("-DrpClient=${project.property("rpClient")}")
    }
    if (project.hasProperty("rpAccount")) {
        jvmArgs("-DrpAccount=${project.property("rpAccount")}")
    }
    if (project.hasProperty("rpPassword")) {
        jvmArgs("-DrpPassword=${project.property("rpPassword")}")
    }
    if (project.hasProperty("rpBots")) {
        jvmArgs("-DrpBots=${project.property("rpBots")}")
    }
}

// Packaging: jpackage app image carrying the native lanes
// (realm DLLs + sqlite seam + libmysql.dll + the mariadb-provider
// closure), the pinned seed transcripts, and the build provenance.
// Inputs are machine-local build outputs — the task
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
    // DESK-115: fail loudly before jpackage when the staged DLLs do not
    // match the win-lane lockfile pins (or the lane provenance drifted).
    // When the native lanes were never built here, the fence honest-skips
    // and the missing-inputs check below still reports the gaps.
    dependsOn(validateNativeRuntimeFreshness)
    doLast {
        // The mariadb-win lane's OWN build root (-mariadb suffix, select_win_
        // flavor): the desktop image ships this lane, and its root is
        // disjoint from the sqlite dev lane's so neither rebuild can
        // overwrite the other's staged bytes.
        val runtimeDir = rootProject.projectDir.resolve("../native/.build-win-x86_64-mariadb/pocket-runtime-build")
        val seamDir = rootProject.projectDir.resolve("../native/.build-win-x86_64/sqlite-seam-build")
        val stagingRoot = rootProject.projectDir.resolve("../native/.build-o09-x86_64/realm-staging-sqlite")
        // The mariadb-win lane's own staging (the lane the DESK-115 fence
        // pins — NativeRuntimeFence.PROVENANCE_RELATIVE): the image's
        // bundled provenance must describe the SHIPPED lane, not the sqlite
        // sibling that only happens to share the seed transcripts.
        val laneStagingRoot = rootProject.projectDir.resolve("../native/.build-o09-x86_64/realm-staging")
        // The MARIADB default's provider lane (DESK-47/106): packageApp
        // stages the verified provider closure under mariadb-provider/ (the
        // engine resolves it from the app dir via java.library.path) and the
        // committed lockfile FLAT beside the DLLs — an install has no
        // schemas/ checkout to walk up to, so without the bundled pin the
        // closure could only refuse with DB-LINK.
        val providerRoot = rootProject.projectDir.resolve("../native/.build-win-x86_64/mariadb-provider")
        val providerLockfile = rootProject.projectDir.resolve("../schemas/database-provider-lockfile-win.json")
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
        // DESK-10/54/83: the addon bundle (researched catalog + the
        // project-owned android-port add-on tree) stages under addons/ in the
        // image so DesktopAddonAssets resolves it from the jpackage $APPDIR
        // at runtime.
        val addonBundle = rootProject.projectDir.resolve("../android/app/src/main/assets/addons")
        // The dependent DLLs the image must ALSO carry beside the exe (see
        // the post-jpackage step below); resolved once, staged twice.
        val vcRuntime = vcRuntimeDlls()
        val allInputs = listOf(
            jarFile,
            runtimeDir.resolve("pocket_realmd_runtime.dll"),
            runtimeDir.resolve("pocket_world_runtime.dll"),
            // The MariaDB Connector/C client DLL the mariadb-backend
            // runtimes import BY NAME — pe_imports elsewhere pin only the
            // import NAME; this DLL's BYTES are pinned by its own artifact
            // row in realm-runtime-lockfile-mariadb-win.json, so the fence
            // rehashes all four shipped DLLs, never three.
            runtimeDir.resolve("libmysql.dll"),
            seamDir.resolve("pocket_sqlite.dll"),
            laneStagingRoot.resolve("BUILD_PROVENANCE.json"),
            providerLockfile,
        ) + seeds + llmAssets + vcRuntime
        val missing = allInputs.filter { !it.isFile }
        if (missing.isNotEmpty() || !addonBundle.isDirectory || !providerRoot.isDirectory) {
            val listing = (missing + listOf(addonBundle, providerRoot).filterNot { it.isDirectory })
                .joinToString(System.lineSeparator()) { "  $it" }
            logger.lifecycle("packageApp SKIPPED (missing inputs):$listing")
            return@doLast
        }
        val imageInput = layout.buildDirectory.dir("package/input").get().asFile
        imageInput.deleteRecursively()
        imageInput.mkdirs()
        allInputs.forEach { it.copyTo(imageInput.resolve(it.name), overwrite = true) }
        addonBundle.copyRecursively(imageInput.resolve("addons"), overwrite = true)
        // DESK-28 embedded-LLM lane payload, an OPTIONAL group: the Windows
        // llama-server sidecar binary + its GGUF dir, built into the native
        // llm staging tree by tools/build_win_llama_server.py (the pinned
        // schemas/sources.json llama-cpp-6d05498 source). The binary is
        // resolved at runtime from the app dir (java.library.path = $APPDIR,
        // DesktopLlmModelManager's packaged lane). Unlike the required
        // DLLs/seeds an absent payload must NOT block the image: the
        // on-device lane degrades to the manager's honest
        // ERR_NO_SIDECAR_BINARY refusal instead.
        val llamaStaging = rootProject.projectDir.resolve("../native/.build-win-x86_64/llama-staging")
        val llamaServerBinary = llamaStaging.resolve("llama-server.exe")
        val llamaGgufDir = llamaStaging.resolve("gguf")
        if (llamaServerBinary.isFile) {
            // R3-2 (native-server round 1): a PRESENT sidecar rides the
            // image only when it byte-matches the lane lockfile the build
            // tool wrote (native/llm/lockfile-win-x86_64.json) — the pin
            // existed but nothing enforced it, so a stale or foreign
            // llama-server.exe shipped silently. Same discipline the
            // vanilla-tweaks patcher and the arm64 vendored-llama libs
            // already enforce at packaging. ABSENT stays optional (the
            // honest ERR_NO_SIDECAR_BINARY degrade below).
            val llamaLock = rootProject.projectDir.resolve("../native/llm/lockfile-win-x86_64.json")
            check(llamaLock.isFile) {
                "Missing llama-server lockfile $llamaLock — " +
                    "build the sidecar: python tools/build_win_llama_server.py"
            }
            val llamaArtifact = (JsonSlurper().parse(llamaLock) as Map<*, *>)["artifact"] as Map<*, *>
            check(
                (llamaArtifact["bytes"] as Number).toLong() == llamaServerBinary.length() &&
                    llamaArtifact["sha256"] == sha256Hex(llamaServerBinary),
            ) {
                "llama-server.exe differs from its pinned size/SHA-256 " +
                    "($llamaLock) — rebuild it: python tools/build_win_llama_server.py"
            }
            llamaServerBinary.copyTo(imageInput.resolve(llamaServerBinary.name), overwrite = true)
            if (llamaGgufDir.isDirectory) {
                llamaGgufDir.copyRecursively(imageInput.resolve("gguf"), overwrite = true)
            }
        } else {
            logger.lifecycle(
                "packageApp: embedded-LLM lane payload absent " +
                    "(${llamaServerBinary.absolutePath}) — the on-device LLM lane stays off " +
                    "in this image (build it: python tools/build_win_llama_server.py)",
            )
        }
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
                // same E:-pin as applicationDefaultJvmArgs — the
                // packaged image's first secp256k1 use must not extract its
                // native to %TEMP% on C:.
                "--java-options", "-Dfr.acinq.secp256k1.tmpdir=E:/pocket_realm_toolchain/tmp",
                "--win-console",
            )
        }
        applyLongPathAwareManifest(image.resolve("PocketRealm.exe"))
        val appDir = image.resolve("app")
        // Dependent-DLL resolution gate: Windows resolves a loaded DLL's
        // static imports through the EXE's directory (search position #1),
        // the system dirs, then PATH — never -Djava.library.path and (probed
        // on JDK 17, PATH clean) never the loaded DLL's own $APPDIR. The
        // staged libmysql.dll and VC runtime DLLs therefore also go BESIDE
        // PocketRealm.exe, or the packaged app's first realm/world start
        // dies with UnsatisfiedLinkError exactly like the pre-fix dev lanes.
        (listOf(runtimeDir.resolve("libmysql.dll")) + vcRuntime).forEach { dll ->
            dll.copyTo(image.resolve(dll.name), overwrite = true)
        }
        // The MARIADB default's provider closure, staged whole exactly as
        // the lane wrote it (bin/lib/plugin/share + bootstrap.sql/
        // migration-manifest.json/migrations): the engine verifies it
        // against the bundled lockfile above and launches mariadbd from it.
        // Copied AFTER jpackage on purpose: jpackage recursively sweeps
        // every .jar under --input onto the launcher cfg's app.classpath,
        // which put the MariaDB distribution's incidental jars
        // (share/mysql/JdbcInterface.jar, Mongo2.jar, Mongo3.jar — vetted
        // by no lockfile) on the app classpath of every install.
        providerRoot.copyRecursively(appDir.resolve("mariadb-provider"), overwrite = true)
        // The sweep contract the provider move buys: exactly one
        // app.classpath entry — the fat app jar, nothing swept in.
        val classpathEntries = appDir.resolve("PocketRealm.cfg")
            .readLines()
            .filter { it.startsWith("app.classpath") }
            .map { it.substringAfter('=').trim() }
        if (classpathEntries.size != 1 || !classpathEntries.single().endsWith(jarFile.name)) {
            throw GradleException(
                "PocketRealm.cfg must name exactly one app.classpath (the main jar " +
                    "${jarFile.name}); found: $classpathEntries — jpackage's input jar " +
                    "sweep picked up jars that must never be on the app classpath",
            )
        }
        // Boot the finished image once before shipping: the packaged-image
        // smoke lane (Main.kt, -Dpocketrealm.nativeSmoke=1) loads every
        // shipped DLL through the launcher's own cfg/java.library.path and
        // exits 0/1 without opening a window. PATH is scrubbed to the
        // Windows system dirs so the load can only resolve libmysql.dll and
        // the VC runtime from the exe dir copies above — a DLL staged into
        // $APPDIR but not beside the exe fails here, not in a user's install.
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        exec {
            environment(
                "PATH" to listOf(
                    "$systemRoot\\System32",
                    systemRoot,
                    "$systemRoot\\System32\\Wbem",
                ).joinToString(File.pathSeparator),
                "JAVA_TOOL_OPTIONS" to "-Dpocketrealm.nativeSmoke=1",
            )
            commandLine(image.resolve("PocketRealm.exe").absolutePath)
        }
        logger.lifecycle("app image: $image")
    }
}


/** Lowercase hex SHA-256 of [file] (the lockfile verification helper —
 * its llama-server sidecar gate; the same discipline the app module's
 * packaging applies to vanilla-tweaks and the vendored llama libs). */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The VC runtime DLLs the realm DLLs import (MSVCP140/VCRUNTIME140[_1]) —
 * app-local deployment from the VS redist tree, so the image runs on
 * machines without the VC redistributable installed. Returns an empty
 * list when the redist tree cannot be located (the DLLs stay missing and
 * packageApp reports them as missing inputs, never silently unpackaged).
 *
 * The pick is deterministic: the NEWEST version directory wins (sorted by
 * name), so two machines with several side-by-side redists package the
 * same CRT instead of whichever directory the OS listed first — and an
 * older-CRT-than-linker substitution is much less likely (the by-name
 * MSVCP140/VCRUNTIME140 imports still forgive a modest skew).
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
    // The CRT dir is picked BY NAME among its redist siblings: an x64 redist
    // version also stages Microsoft.VC*.CXXAMP and Microsoft.VC*.OpenMP,
    // and a plain "starts with Microsoft.VC" pick lands on OpenMP (sorted
    // last), which carries none of the three imported DLLs — the pick then
    // silently degenerates to an empty list and the image ships without
    // any VC runtime, booting only where System32 already has the redist.
    val crt = redistRoot.listFiles()
        ?.sortedByDescending { it.name }
        ?.firstNotNullOfOrNull { version ->
            version.resolve("x64").listFiles()
                ?.sortedByDescending { it.name }
                ?.firstOrNull { dir ->
                    dir.name.startsWith("Microsoft.VC") && dir.name.endsWith(".CRT")
                }
        } ?: return emptyList()
    return listOf("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll")
        .map { crt.resolve(it) }
        .filter { it.isFile }
}


/**
 * longPathAware for the launcher exe (data preparation and storage live
 * under %LOCALAPPDATA%; deep generation trees can exceed MAX_PATH for
 * native code). jpackage's launcher embeds its own manifest and is created
 * READ-ONLY, so mt.exe -outputresource fails; tools/win_manifest_longpath.py
 * merges the setting through kernel32's UpdateResource and restores the
 * attribute.
 *
 * Failure policy: no python at all (IOException starting the process) is
 * the documented honest skip; anything else — a python that runs and
 * fails (pefile missing, unrecognized manifest, resource write denied) —
 * FAILS the task, because a silently-missed flag would regress the
 * qualification deliverable while the build stays green. The Store
 * python.exe alias stub (starts fine, exits nonzero, prints nothing) is
 * exactly the case that makes the exit code load-bearing.
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
    val exit = process.waitFor()
    if (output.isNotBlank()) {
        logger.lifecycle("packageApp: ${output.trim()}")
    }
    if (exit != 0) {
        throw GradleException(
            "longPathAware merge FAILED (python exit $exit): ${output.trim().ifEmpty { "no output" }}. " +
                "The launcher manifest is not pinned without it — install python with the " +
                "'pefile' package (pip install pefile) and rerun packageApp. If python here is " +
                "the Microsoft Store alias stub, disable the alias or put a real python on PATH.",
        )
    }
}

tasks.withType<Test>().configureEach {
    // Every forked test worker keeps its temp tree on E:.
    systemProperty("java.io.tmpdir", "E:/pocket_realm_toolchain/tmp")
    // R6-1/R2-r4-1 (W1 code-red round 4): a §2.4-sanctioned FOCUSED run
    // (`-Ppocket.focus=<label>` + --tests ...) writes its JUnit XML under
    // build/focused-test-results-<label>/ instead of the canonical
    // build/test-results/ tree — a focused run can no longer hollow the
    // canonical evidence the counts artifacts and the wave-evidence tree
    // pin reconcile against. Canonical runs (no property) keep the
    // historical location, so the canonical trees only ever change via
    // canonical runs. The set MUST be at configuration time (
    // Gradle finalizes task properties at execution start, so the
    // doFirst form threw "outputLocation is final" on this lane —
    // the desktop lane has plain Gradle Test tasks and no AGP re-setting
    // report locations; that rationale belonged to the android lane alone).
    val pocketFocusedResults = providers.gradleProperty("pocket.focus")
        .map { "focused-test-results-${it.trim()}/test-results" }
        .flatMap { layout.buildDirectory.dir(it) }
    if (!providers.gradleProperty("pocket.focus").orNull.isNullOrBlank()) {
        reports.junitXml.outputLocation.set(pocketFocusedResults)
        // the JUnit-XML redirect alone was NOT the
        // fence — the Test task's BINARY results directory stayed
        // canonical, so every sanctioned focused run still attempted to
        // delete build/test-results/test/binary (the observed
        // "Unable to delete directory ... binary" IOException) and could
        // hollow the canonical tree it was fenced against. Both output
        // locations move together under -Ppocket.focus.
        binaryResultsDirectory.set(
            pocketFocusedResults.map { it.dir("binary") },
        )
        // W6-N4 code-red R6-N4-r3-2: the focused lane emits its OWN
        // counts marker (the same shape the android lane's
        // focused_android_unit_test.py prints) — a TRACKED emitter, so
        // the archived log's counts line is reproducible by re-running,
        // never appended text.
        doLast {
            val resultsDir = pocketFocusedResults.get().asFile
            val xmls = resultsDir.walkTopDown()
                .filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
                .toList()
            if (xmls.isEmpty()) return@doLast
            var tests = 0
            var failures = 0
            var classes = 0
            val perClass = ArrayList<String>()
            for (xml in xmls.sortedBy { it.name }) {
                try {
                    val head = xml.useLines { lines ->
                        lines.firstOrNull { it.contains("<testsuite") }
                    } ?: continue
                    fun attr(name: String): Int =
                        Regex("$name=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val t = attr("tests")
                    val f = attr("failures") + attr("errors")
                    tests += t
                    failures += f
                    classes += 1
                    val suite = Regex("name=\"([^\"]+)\"").find(head)
                        ?.groupValues?.get(1)?.substringAfterLast('.') ?: "?"
                    perClass += "$suite $t/$f"
                } catch (_: Exception) {
                    // an unreadable XML counts as absent, never as a pass
                }
            }
            println(
                "# N4-FOCUSED-COUNTS tests=$tests classes=$classes " +
                    "failures=$failures (${perClass.joinToString(", ")})",
            )
        }
    }
}


// R2-5/R5-1/R3-N5-r5-2 (W6-N5 code-red round 5): a FILTERED desktop test
// run WITHOUT the -Ppocket.focus redirect writes its JUnit XML straight
// into the canonical build/test-results/test tree and hollows the
// standing evidence (captured live twice this wave: 4 files / 39 tests).
// The fence is now structural: test-class filters require the focus
// property — the focused lane's redirect then protects the canonical
// tree. Canonical runs (`cleanTest test`) carry no filters and pass.
val desktopFilteredRun = gradle.startParameter.taskRequests.any { request ->
    request.args.any { it.startsWith("--tests") }
}
val desktopFocusRedirect = providers.gradleProperty("pocket.focus").orNull?.isNotBlank() == true
if (desktopFilteredRun && !desktopFocusRedirect) {
    throw GradleException(
        "desktop test-class filters (--tests) require -Ppocket.focus=<label> — " +
            "an unfenced filtered run writes its JUnit XML into the canonical " +
            "build/test-results/test tree and hollows the standing evidence " +
            "(R2-5/R5-1/R3-N5-r5-2, W6-N5 code-red round 5)"
    )
}

// R4-r5-1 (W6-N5 code-red round 5): the desktop lane's CANONICAL counts
// emitter — the android treatment. The tracked counts writer's fail-closed
// drift guard re-binds a changed canonical count ONLY through a native
// counts marker in the cited log; without this task that recovery path was
// unsatisfiable for desktop (the artifact frozen at its last measure).
tasks.register("desktopCanonicalCounts") {
    val canonicalResults = project.file("build/test-results/test")
    doLast {
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        var classes = 0
        val files = canonicalResults.listFiles { f ->
            f.name.startsWith("TEST-") && f.name.endsWith(".xml")
        } ?: emptyArray()
        for (xml in files.sortedBy { it.name }) {
            val head = xml.useLines { lines ->
                lines.firstOrNull { it.contains("<testsuite") }
            } ?: continue
            fun attr(name: String): Int =
                Regex("$name=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            tests += attr("tests")
            failures += attr("failures") + attr("errors")
            skipped += attr("skipped")
            classes += 1
        }
        println(
            "# DESKTOP-CANONICAL-COUNTS tests=$tests skipped=$skipped " +
                "failures=$failures classes=$classes",
        )
    }
}
