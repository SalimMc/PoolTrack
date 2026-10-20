import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlinCompose)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.googleServices)
  jacoco
}

// The single place to bump the version. Android only installs an update whose versionCode is higher
// than the installed one, and the in-app updater compares versionName with the GitHub release tag
// (v<versionName>), so versionCode is derived from it: 1.2.3 -> 10203.
val appVersionName = "1.7.0"

fun versionCodeOf(versionName: String): Int {
  val (major, minor, patch) = versionName.split('.').map(String::toInt)
  return major * 10_000 + minor * 100 + patch
}

android {
  namespace = "com.github.se.pooltrack"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.github.se.pooltrack"
    minSdk = 29
    targetSdk = 36
    versionCode = versionCodeOf(appVersionName)
    versionName = appVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  // Release builds are signed with the key described by these variables, set by the release
  // workflow. Every release must use the same key, or Android refuses to install it as an update.
  // Without them (local builds, CI checks) the release build is simply left unsigned.
  val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")
  if (releaseKeystore != null) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    debug { enableUnitTestCoverage = true }
    release {
      if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
      // R8 shrinks and obfuscates the release build, so the shipped code is not readable as-is.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  // Robolectric needs the merged resources and manifest to run Compose UI tests on the JVM.
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// With AGP 9+ we have to set the JVM target on a kotlin block outside the Android block.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  // Version alignment. Every androidx.compose.* artifact below takes its version from this BOM.
  implementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(platform(libs.androidx.compose.bom))

  // Core
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.serialization.json)

  // Firebase: Google Sign-In (via Credential Manager) + Firestore backup of local data.
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)

  // Jetpack Compose UI
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)

  // Navigation
  implementation(libs.androidx.navigation.compose)

  // Testing
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.ui.test.junit4)
}

// Fails when line coverage of the unit tests is below 80%. Part of `check`.
// Compose singletons, R and BuildConfig are generated, so they are excluded.
tasks.register<JacocoCoverageVerification>("coverageVerification") {
  group = "verification"
  description = "Verifies that unit tests cover at least 80% of the lines."
  dependsOn("testDebugUnitTest")

  executionData.setFrom(
      layout.buildDirectory.file(
          "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
      )
  )
  classDirectories.setFrom(
      fileTree(
          layout.buildDirectory.dir(
              "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
          )
      ) {
        exclude(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.class",
            "**/ComposableSingletons*.class",
        )
      }
  )
  violationRules {
    rule {
      limit {
        counter = "LINE"
        minimum = "0.80".toBigDecimal()
      }
    }
  }
}

// Robolectric loads app classes through its own sandbox classloader; without this JaCoCo records
// nothing for them, and Compose/Robolectric tests would show 0% coverage.
tasks.withType<Test>().configureEach {
  extensions.configure<JacocoTaskExtension> {
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

tasks.named("check") { dependsOn("coverageVerification") }
