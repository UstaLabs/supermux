import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") // JediTerm (M2)
    maven("https://jogamp.org/deployment/maven")                              // KCEF transitive (M3)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.jediterm.core) // dual LGPLv3/Apache-2.0 — used under Apache-2.0
    implementation(libs.jediterm.ui)

    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "dev.supermux.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.AppImage)
            packageName = "supermux"
            packageVersion = "1.0.0"
            description = "supermux desktop"
            vendor = "UstaLabs"
        }
    }
}
