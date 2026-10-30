plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    application
}

// THROWAWAY: the macOS layout test (docs/superpowers/plans/2026-10-29-webview-macos-layout-test.md).
// One WKWebView in a Compose Desktop window, under skiko's Metal layer (layout A) or over it with a mask (layout B),
// driven on the Mac with in-process input and screenshots. The macOS engine plan absorbs the winning glue into
// :webview-compose and deletes this module. Compose, kotlinx and :webview-compose only.
//
//   webview-mac-gradle :webview-layout-test:test :webview-layout-test:installDist
//   ssh mac 'bash ~/work/webview-compose/apps/webview-layout-test/native/macos/build.sh'
//   ssh mac 'bash ~/work/webview-compose/apps/webview-layout-test/mac/run.sh auto <out dir>'
group = "dev.supermux.webview.layouttest"
version = "0.1.0-dev.1"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":webview-compose"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    // mac/run.sh starts it as `java -cp build/install/webview-layout-test/lib/* …`, never through Gradle: an SSH session
    // has no window server.
    mainClass.set("dev.supermux.webview.layouttest.MainKt")
}

tasks.test {
    // The screenshot checks build BufferedImages; nothing in the tests may need a window server.
    systemProperty("java.awt.headless", "true")
}
