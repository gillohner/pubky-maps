import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    // Generates res/raw/aboutlibraries.json: every bundled library and its licence, for Settings.
    alias(libs.plugins.aboutlibraries.android)
}

// Release builds get their version and update channel from scripts/release/version.py; a local
// build is versionCode 1 and does not update itself.
val appVersion = providers.gradleProperty("pubkymaps.version").get()
val appVersionCode = providers.gradleProperty("pubkymaps.versionCode").orNull?.toInt() ?: 1
val appVersionName = providers.gradleProperty("pubkymaps.versionName").orNull ?: appVersion

// The update feed the `direct` APK follows: "edge", "stable", "dev", or "" for none (local builds).
val updateChannel = providers.gradleProperty("pubkymaps.updateChannel").orNull ?: ""
require(updateChannel in setOf("", "edge", "stable", "dev")) { "unknown update channel '$updateChannel'" }

// The feed's public key: dev builds (manual release runs) are signed with separate dev keys. The
// update probe (scripts/ci/update-probe.py) builds with its own feed address and a throwaway key.
val updateFeedOverride = providers.gradleProperty("pubkymaps.updateFeedUrl").orNull ?: ""
val updatePublicKey =
    providers.gradleProperty("pubkymaps.updatePublicKey").orNull
        ?: rootProject
            .file(if (updateChannel == "dev") "release/update-feed-key-dev.pub" else "release/update-feed-key.pub")
            .readText()
            .trim()
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
        // The ABIs the Rust core is built for (scripts/build-android.sh): 64-bit phones, and x86_64
        // for emulators. MapLibre ships these too.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

/**
 * Builds the Rust core (core/) for the app's ABIs with cargo-ndk (scripts/build-android.sh) into
 * [outputDir], which every variant packages as jniLibs. Needs ANDROID_NDK_HOME, cargo-ndk and the
 * Android rustup targets.
 */
abstract class BuildCoreForAndroid
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val script: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            exec.exec { commandLine("bash", script.get().asFile.path, outputDir.get().asFile.path) }
        }
    }

val buildCoreForAndroid =
    tasks.register<BuildCoreForAndroid>("buildCoreForAndroid") {
        val root = rootProject.layout.projectDirectory
        sources.from(
            root.file("Cargo.toml"),
            root.file("Cargo.lock"),
            root.dir("core/src"),
            root.file("core/Cargo.toml"),
        )
        script.set(root.file("scripts/build-android.sh"))
        outputDir.set(layout.buildDirectory.dir("generated/coreJniLibs"))
    }

/**
 * Writes res/raw/rust_licences.json (scripts/rust-licences.py): the Rust crates linked into the core
 * with their licences, in the AboutLibraries format, for the app's open-source licence screen.
 */
abstract class ListRustLicences
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val script: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun list() {
            val out = outputDir.get().file("raw/rust_licences.json").asFile
            exec.exec { commandLine("python3", "-I", script.get().asFile.path, out.path) }
        }
    }

val listRustLicences =
    tasks.register<ListRustLicences>("listRustLicences") {
        val root = rootProject.layout.projectDirectory
        sources.from(root.file("Cargo.toml"), root.file("Cargo.lock"), root.file("core/Cargo.toml"))
        script.set(root.file("scripts/rust-licences.py"))
        outputDir.set(layout.buildDirectory.dir("generated/rustLicences"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildCoreForAndroid, BuildCoreForAndroid::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(listRustLicences, ListRustLicences::outputDir)
    }
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
    // Robolectric screenshot tests keep each rendered screen in memory; Gradle's 512 MB default runs
    // out with the full set (every screen on phone and tablet, light and dark).
    maxHeapSize = "2g"
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
    implementation(libs.sqldelight.android.driver)
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
