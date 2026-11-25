import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    // Generates res/raw/aboutlibraries.json: every bundled library and its licence, for Settings.
    alias(libs.plugins.aboutlibraries.android)
}

// Release builds get their version and update channel from scripts/release/version.sh; a local
// build is versionCode 1 and does not update itself.
val appVersion = providers.gradleProperty("pubkymaps.version").get()
val appVersionCode = providers.gradleProperty("pubkymaps.versionCode").orNull?.toInt() ?: 1
val appVersionName = providers.gradleProperty("pubkymaps.versionName").orNull ?: appVersion

// The update feed the `direct` APK follows: "edge", "stable", "dev", or "" for none (local builds).
val updateChannel = providers.gradleProperty("pubkymaps.updateChannel").orNull ?: ""
require(updateChannel in setOf("", "edge", "stable", "dev")) { "unknown update channel '$updateChannel'" }

// The update probe (scripts/ci/update-probe.py) builds with its own feed address and a throwaway key.
val updateFeedOverride = providers.gradleProperty("pubkymaps.updateFeedUrl").orNull ?: ""
val updatePublicKey =
    providers.gradleProperty("pubkymaps.updatePublicKey").orNull
        ?: rootProject.file("release/update-feed-key.pub").readText().trim()
val updateProbe = providers.gradleProperty("pubkymaps.updateProbe").orNull == "true"

android {
    namespace = "to.pubky.maps"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "to.pubky.maps"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // The ABIs the MapLibre runtime ships native code for.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    buildFeatures {
        buildConfig = true
    }

    // `direct`: the APK published on GitHub, which updates itself from the signed update feed.
    // `store`: the .aab for app stores, which update the app themselves; it has no updater and does
    // not ask for REQUEST_INSTALL_PACKAGES (Google Play allows updates only through Play).
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_CHANNEL", "\"$updateChannel\"")
            buildConfigField("String", "UPDATE_FEED_URL", "\"$updateFeedOverride\"")
            buildConfigField("String", "UPDATE_PUBLIC_KEY", "\"$updatePublicKey\"")
        }
        create("store") {
            dimension = "distribution"
        }
    }

    buildTypes {
        // R8 shrinks and optimises release builds. They are left unsigned here and signed by
        // scripts/release/sign.sh, so no signing key ever reaches Gradle.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Only the update probe's builds may fetch their fixture feed over plain HTTP from the phone's
    // localhost; every other build keeps Android's default (HTTPS only).
    if (updateProbe) {
        sourceSets
            .getByName("release")
            .res.directories
            .add("src/updateProbe/res")
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.toVersion(
                libs.versions.java.bytecode
                    .get(),
            )
        targetCompatibility =
            JavaVersion.toVersion(
                libs.versions.java.bytecode
                    .get(),
            )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }
}

androidComponents {
    beforeVariants { variant ->
        // Store builds are only ever release bundles, and the unit and screenshot tests run once,
        // on the direct debug build.
        if (variant.productFlavors.contains("distribution" to "store") && variant.buildType == "debug") {
            variant.enable = false
        }
        if (variant.name != "directDebug") {
            (variant as HasHostTestsBuilder).hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            JvmTarget.fromTarget(
                libs.versions.java.bytecode
                    .get(),
            ),
        )
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(
                JavaLanguageVersion.of(
                    libs.versions.java.test
                        .get(),
                ),
            )
        },
    )
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
    "directImplementation"(libs.bouncycastle.prov)
    "directImplementation"(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
