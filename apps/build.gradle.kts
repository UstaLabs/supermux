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

// #142: no Compose source-information or trace-marker strings (nor the calls that carry them) in any
// module. They only serve Android Studio's Layout Inspector source links and composition tracing, and
// on the web client they were 276 KB of the gzip ceiling (CI: 9381 -> 9105 KB, apps/web's
// maxGzipBytes). `-Psupermux.composeSourceInfo=true` turns both back on for a local inspector session.
val composeSourceInfo = providers.gradleProperty("supermux.composeSourceInfo").orNull == "true"
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            includeSourceInformation.set(composeSourceInfo)
            includeTraceMarkers.set(composeSourceInfo)
        }
    }
}
