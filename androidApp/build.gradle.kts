import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

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
        versionCode = 1
        versionName = "0.1.0"
        // The ABIs the MapLibre runtime ships native code for.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    buildFeatures {
        buildConfig = true
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
    runtimeOnly(libs.maplibre.compose.runtime.opengl.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
