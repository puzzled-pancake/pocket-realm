pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform desktop artifacts depend on androidx.* POMs
        // that only exist on Google Maven.
        maven("https://maven.google.com")
    }
}

rootProject.name = "pocketrealm-desktop"

// Opt-in isolated build lane: `-Ppocketrealm.buildDir=<dir>` redirects the
// project's build directory for one invocation. Two concurrent desktop
// builds (separate working sessions on one checkout) otherwise contend on
// build/test-results (Gradle's Test task deletes it at task start); the
// property lets a second lane verify without touching the first's outputs.
// Inert unless the property is passed.
gradle.beforeProject {
    if (path == ":") {
        providers.gradleProperty("pocketrealm.buildDir").orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { dir -> layout.buildDirectory.set(file(dir)) }
    }
}
