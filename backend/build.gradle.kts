plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "com.mezo.trace"
    version = "0.1.0"
}

// Phase 14: static analysis across all backend modules. Fails the build on
// detected issues (new code must be clean); config lives in
// `gradle/detekt.yml`. Runs as part of `./gradlew check` and in CI.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    val hasDetektConfig = file("${rootProject.projectDir}/gradle/detekt.yml").exists()
    val baselineFile = file("${projectDir}/detekt-baseline.xml")

    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            toolVersion = "1.23.7"
            if (hasDetektConfig) {
                config.setFrom(files("${rootProject.projectDir}/gradle/detekt.yml"))
            }
            // Pre-existing findings live in the module baseline; new code must
            // be clean. Baselines are reviewed and shrunk over time.
            if (baselineFile.exists()) {
                baseline = baselineFile
            }
            buildUponDefaultConfig = true
            parallel = true
            // Generated/build sources are excluded automatically by detekt.
        }

        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(tasks.matching { it.name == "detekt" })
        }
    }
}
