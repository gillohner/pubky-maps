plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.sqldelight) apply false
}

val ktlintVersion =
    libs.versions.ktlint.cli
        .get()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }
    // A warning in the app's own code fails the build (a deprecated call, a check that is always
    // true), so none piles up unseen; test code is left out, its warnings fail nothing.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
    }
}
