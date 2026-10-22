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
// coverage comes from the JaCoCo report of the unit tests, including its branch counters.
sonar {
    properties {
        property("sonar.projectKey", "SamiPr0_PoolTrack")
        property("sonar.organization", "samipr0")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sources", "app/src/main/java")
        property("sonar.tests", "app/src/test/java")
        property(
            "sonar.java.binaries",
            "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
        )
    }
}
