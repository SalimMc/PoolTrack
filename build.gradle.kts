// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.sonarqube)
}

// Analysis for SonarCloud, run from CI (`./gradlew jacocoTestReport sonar`) with a SONAR_TOKEN. The
// The Gradle plugin finds the app module's sources and tests itself; naming them here again would
// index every file twice. The coverage comes from the JaCoCo report of the unit tests, including its branch counters.
sonar {
    properties {
        property("sonar.projectKey", "SamiPr0_PoolTrack")
        property("sonar.organization", "samipr0")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            file("app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml").absolutePath,
        )
    }
}

// Locks every resolved dependency version (including transitive ones) in `gradle.lockfile`
// files, so a build resolves the same versions everywhere. Regenerate after a version bump:
// `./gradlew resolveAndLockAll --write-locks`.
allprojects {
    dependencyLocking { lockAllConfigurations() }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) {
                "Run with --write-locks to regenerate the lockfiles."
            }
        }
        doLast {
            configurations
                .filter { it.isCanBeResolved }
                .forEach { runCatching { it.resolve() } }
        }
    }
}
