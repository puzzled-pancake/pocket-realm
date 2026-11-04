// Top-level build file. Plugins are declared here and applied in :app so versions
// are controlled from one place (the version catalog at gradle/libs.versions.toml).
// AGP 9.x applies the Kotlin Android extension itself, so only the Compose
// compiler plugin is declared separately.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

// Emit the app's JUnit totals for the repository's test-count reports.
tasks.register("androidCanonicalCounts") {
    val resultDir = project.file("app/build/test-results/testDebugUnitTest")
    mustRunAfter(":app:testDebugUnitTest")
    doLast {
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        var classes = 0
        resultDir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
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
        println(
            "# ANDROID-CANONICAL-COUNTS tests=$tests skipped=$skipped " +
                "failures=$failures classes=$classes",
        )
    }
}
