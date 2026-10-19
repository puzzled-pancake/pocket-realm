// Top-level build file. Plugins are declared here and applied in :app so versions
// are controlled from one place (the version catalog at gradle/libs.versions.toml).
// AGP 9.x applies the Kotlin Android extension itself, so only the Compose
// compiler plugin is declared separately.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

// W6-N5 round 3 (R3-N5-r3-1's re-bind leg / the R5-1 root fix): the
// canonical android command ends with this aggregator, which prints ONE
// native counts marker over the three canonical test tasks' JUnit XML
// trees. The tracked counts writers re-bind the standing artifact only
// when this marker equals the re-aggregated numbers — a canonical log
// without the marker can no longer absorb a changed count.
tasks.register("androidCanonicalCounts") {
    // resolved at CONFIGURATION time (the configuration cache forbids
    // Task.project at execution time)
    val resultDirs = listOf(
        "app/build/test-results/testDebugUnitTest",
        "pocketrealm-llm/build/test-results/test",
        "pocketrealm-llm/build/test-results/testDebugUnitTest",
    ).map { project.file(it) }
    mustRunAfter(
        ":app:testDebugUnitTest",
        ":pocketrealm-llm:testDebugUnitTest",
        ":pocketrealm-llm:test",
    )
    doLast {
        val dirs = resultDirs
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        var classes = 0
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
                ?.forEach { xml ->
                    val head = xml.useLines { lines ->
                        lines.firstOrNull { it.contains("<testsuite") }
                    } ?: return@forEach
                    fun attr(name: String): Int =
                        Regex("$name=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    tests += attr("tests")
                    failures += attr("failures") + attr("errors")
                    skipped += attr("skipped")
                    classes += 1
                }
        }
        println(
            "# ANDROID-CANONICAL-COUNTS tests=$tests skipped=$skipped " +
                "failures=$failures classes=$classes",
        )
    }
}
