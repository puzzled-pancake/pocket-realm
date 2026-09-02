plugins {
    // AGP 9.x ships built-in Kotlin support (the app module relies on the
    // same fact); the standalone org.jetbrains.kotlin.android plugin must
    // NOT be applied here or the `kotlin` extension registers twice.
    id("com.android.library")
}

// Runtime staging knobs (all configuration-cache safe). This vendored copy
// stages from in-repo prebuilts (see prebuilt/README.md); the properties are
// overrides for pointing at an external llama-droid deploy tree instead.
//  - llmRuntimeDeployDir: default pocketrealm-llm/prebuilt (server exe + impl
//                         + mtmd; the shared llama.cpp closure comes from the
//                         app's staged jniLibs — byte-identical accelerated
//                         build, see prebuilt/README.md).
//  - llmRuntimeHexDir:    default pocketrealm-llm/prebuilt/hexagon.
//                         Contains libggmlhex.so (renamed geniex v0.5.0 backend,
//                         loaded only via GGML_BACKEND_PATH — see HexagonProbe)
//                         and dsp/libggml-htp-v{73,75,79,81}.so (shipped as assets).
val deployDir = providers.gradleProperty("llmRuntimeDeployDir")
    .getOrElse("pocketrealm-llm/prebuilt")
    .let { rootProject.file(it) }
val hexDir = providers.gradleProperty("llmRuntimeHexDir")
    .getOrElse("pocketrealm-llm/prebuilt/hexagon")
    .let { rootProject.file(it) }

// The DSP skels + build info are only meaningful on the arm64 full lane.
// The consumer's qualification lanes (pocketLane=database, x86_64) must not
// carry them; the module's asset contribution itself is conditional (the
// AGP 9 sources DSL has no asset pattern filters).
val laneFull = providers.gradleProperty("pocketLane").getOrElse("full") == "full"
val abiArm64 = providers.gradleProperty("pocketAbi").getOrElse("arm64-v8a") == "arm64-v8a"

android {
    namespace = "com.pocketrealm.llm"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // 26 matches the app. The prebuilt runtime itself documents min API 28
        // (Android 8.1); the RP6 ships 13+, so the gap is theoretical. On a
        // 26/27 device a failed exec surfaces as a service restart loop, not
        // a crash of the game.
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The llama.cpp server ships as prebuilt binaries staged by the tasks
    // below into build/llmRuntimeLibs. They must be named lib*.so so the
    // packager keeps them and extracts them into nativeLibraryDir at install
    // time (exec from there is allowed). Plain File paths: AGP 9 refuses
    // Provider source dirs; the task dependencies are wired via preBuild.
    sourceSets {
        getByName("main") {
            val build = layout.buildDirectory.get().asFile
            jniLibs.directories.add(build.resolve("llmRuntimeLibs").absolutePath)
            // The DSP skels + build info are only meaningful on the arm64
            // full lane; on other lanes the module contributes NO assets at
            // all (the AGP 9 sources DSL has no asset pattern filters, so
            // the contribution itself is conditional).
            if (laneFull && abiArm64) {
                assets.directories.add(build.resolve("llmRuntimeAssets").absolutePath)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Stage the prebuilt CPU runtime into the jniLibs source dir with the lib*.so
// naming required for extraction. Binary arrives as libllamaserver.so. The
// include filter keeps prose (prebuilt/README.md) and the hexagon/ subtree
// out of the jniLibs staging root.
val stageRuntime by tasks.registering(Copy::class) {
    from(deployDir) { include("llama-server", "*.so") }
    into(layout.buildDirectory.dir("llmRuntimeLibs/arm64-v8a"))
    rename("^llama-server$", "libllamaserver.so") // anchored: plain pattern also mangles libllama-server-impl.so
}

// Stage the Hexagon NPU backend (renamed: libggmlhex.so is deliberately NOT
// matched by ggml's exe-dir backend scan, which looks for libggml-hexagon*.so;
// the service loads it via GGML_BACKEND_PATH only in NPU mode).
val stageHexagon by tasks.registering(Copy::class) {
    from(hexDir) { include("libggmlhex.so") }
    into(layout.buildDirectory.dir("llmRuntimeLibs/arm64-v8a"))
}

// Ship the DSP skels + build provenance as assets; the service extracts the
// skels to filesDir/dsp and points ADSP_LIBRARY_PATH there (app-sandbox skel
// loading validated on-device). Staged only for the arm64 full lane.
val stageDspSkels by tasks.registering(Copy::class) {
    from(hexDir) { include("dsp/**") }
    into(layout.buildDirectory.dir("llmRuntimeAssets"))
}

val stageBuildInfo by tasks.registering(Copy::class) {
    from(file("prebuilt/build-info")) { include("runtime-build-info.properties") }
    into(layout.buildDirectory.dir("llmRuntimeAssets"))
}

tasks.preBuild {
    if (laneFull && abiArm64) {
        dependsOn(stageRuntime, stageHexagon, stageDspSkels, stageBuildInfo)
    } else {
        dependsOn(stageRuntime, stageHexagon)
    }
}

dependencies {
    testImplementation(libs.junit)
}
