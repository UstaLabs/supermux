# webview-compose on the desktop JVM: the rules a release build shrunk with ProGuard needs.
#
# Compose Desktop's ProGuard step (packageRelease*, createReleaseDistributable, runRelease) reads no rules from library
# jars: add this file to the app's own build, as webview-sample does:
#
#   compose.desktop.application.buildTypes.release.proguard {
#       configurationFiles.from(project(":webview-compose").file("src/jvmMain/resources/META-INF/proguard/webview-compose-desktop.pro"))
#   }
#
# The macOS engine's glue (native/macos) calls MacGlue.onEvent back by name from JNI_OnLoad. Nothing in Kotlin calls
# it, so shrinking removes it, and with it every engine: they report Unavailable(NativeLibraryFailed) with a message that
# names this rule. The glue also finds MacGlue itself by name.
-keep class dev.supermux.webview.engine.mac.MacGlue { *; }
