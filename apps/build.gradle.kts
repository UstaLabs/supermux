// Root build script: declare every plugin once (apply false) so sibling modules
// (:shared, :android) load plugin classes in ONE classloader scope. Without this,
// the Kotlin/Native `KotlinNativeBundleBuildService` is instantiated per-scope and
// the iOS framework-link task fails ("Cannot set the value ... using a provider of
// type KotlinNativeBundleBuildService").
plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // Applied only by :desktop (dev-time hot reload); declared here per the CHR docs so the plugin
    // is loaded once for the whole build rather than per-subproject classloader.
    alias(libs.plugins.compose.hot.reload) apply false
}

// #142 size probe: `-Psupermux.composeLean=true` builds without the Compose compiler's
// source-information and trace-marker strings (and the calls that carry them).
// Off by default; the CI size probe flips it to measure what it saves in the web wasm.
if (providers.gradleProperty("supermux.composeLean").orNull == "true") {
    subprojects {
        plugins.withId("org.jetbrains.kotlin.plugin.compose") {
            extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                includeSourceInformation.set(false)
                includeTraceMarkers.set(false)
            }
        }
    }
}
