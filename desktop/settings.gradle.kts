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
