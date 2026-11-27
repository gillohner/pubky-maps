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
    // ktlint's lint and format tasks keep the absolute path of every file they read, and on their next
    // run read again each such file that exists. From the build cache, those paths name the checkout
    // that stored the entry: with two checkouts of this repository on one machine (sharing
    // ~/.gradle/caches/build-cache-1), a check then fails on the other checkout's half-written files,
    // and a format served from the cache leaves this checkout's files unformatted. Both tasks are
    // quick (they re-read only changed files between runs), so they are never taken from the cache.
    tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
        outputs.doNotCacheIf("ktlint's results name the files of the checkout that stored them") { true }
    }
    // A warning in the app's own code fails the build (a deprecated call, a check that is always
    // true), so none piles up unseen; test code is left out, its warnings fail nothing.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
    }
}
