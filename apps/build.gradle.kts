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
}
