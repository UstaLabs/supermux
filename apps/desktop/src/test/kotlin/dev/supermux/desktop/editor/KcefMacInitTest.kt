package dev.supermux.desktop.editor

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * macOS CEF bring-up (see KcefRuntime's macOS section).
 *
 * The bug these lock down: on macOS `JCefAppConfig` seeds KCEF's builder args with
 * `--framework-dir-path=` / `--main-bundle-path=` / `--browser-subprocess-path=` derived from
 * `java.home`, i.e. the JetBrainsRuntime layout. Under jpackage's runtime (Corretto) those point at
 * a `Contents/runtime/Contents/Frameworks` dir that has no CEF, and because `CefApp.startup` keeps
 * the LAST `--framework-dir-path=` it sees, they override the correct path KCEF *prepends* → the
 * framework dlopen fails and CEF then jumps through a null pointer, killing the whole JVM.
 */
class KcefMacInitTest {

    @Test
    fun mac_os_name_detection() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(isMacOs("Darwin"))
        assertFalse(isMacOs("Linux"))
        assertFalse(isMacOs("Windows 11"))
        assertFalse(isMacOs(null))
    }

    @Test
    fun stale_jbr_path_switches_are_dropped() {
        val jbr = listOf(
            "--framework-dir-path=/App.app/Contents/runtime/Contents/Frameworks/Chromium Embedded Framework.framework",
            "--main-bundle-path=/App.app/Contents/runtime/Contents/Frameworks/jcef Helper.app",
            "--browser-subprocess-path=/App.app/Contents/runtime/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper",
        )
        assertEquals(emptyList(), macCefArgs(jbr, emptyList()))
    }

    @Test
    fun non_path_jbr_switches_are_kept_in_order_then_ours() {
        val jbr = listOf(
            "--disable-in-process-stack-traces",
            "--framework-dir-path=/stale/Chromium Embedded Framework.framework",
            "--use-mock-keychain",
            "--disable-features=SpareRendererForSitePerProcess",
            "--force-device-scale-factor=2",
        )
        val ours = listOf("--no-sandbox", "--disable-gpu")
        assertEquals(
            listOf(
                "--disable-in-process-stack-traces",
                "--use-mock-keychain",
                "--disable-features=SpareRendererForSitePerProcess",
                "--force-device-scale-factor=2",
                "--no-sandbox",
                "--disable-gpu",
            ),
            macCefArgs(jbr, ours),
        )
    }

    @Test
    fun surrounding_whitespace_does_not_hide_a_stale_switch() {
        assertEquals(emptyList(), macCefArgs(listOf("  --framework-dir-path=/stale "), emptyList()))
    }

    @Test
    fun framework_binary_lives_under_frameworks_in_the_install_dir() {
        val install = Files.createTempDirectory("kcef-mac")
        assertEquals(
            install.resolve("Frameworks")
                .resolve("Chromium Embedded Framework.framework")
                .resolve("Chromium Embedded Framework"),
            macFrameworkBinary(install),
        )
    }

    @Test
    fun resources_dir_is_the_frameworks_own_resources() {
        // CEF's icudtl.dat + *.pak live INSIDE the framework on macOS. KCEF computes this path
        // (Platform.OS.MACOSX.getResourcesPath) but never applies it — CefInitializer skips
        // resources_dir_path on mac — and its `--main-bundle-path` points at `jcef Helper.app`,
        // which has no Resources, so CEF reports "icudtl.dat not found in bundle" and aborts.
        val install = Files.createTempDirectory("kcef-mac-res")
        assertEquals(
            install.resolve("Frameworks")
                .resolve("Chromium Embedded Framework.framework")
                .resolve("Resources"),
            macResourcesDir(install),
        )
        // Same framework dir the binary lives in — one source of truth.
        assertEquals(macFrameworkBinary(install).parent, macResourcesDir(install).parent)
    }

    @Test
    fun helper_app_and_binary_paths() {
        val install = Files.createTempDirectory("kcef-mac-helper")
        assertEquals(install.resolve("Frameworks").resolve("jcef Helper.app"), macHelperApp(install))
        assertEquals(
            macHelperApp(install).resolve("Contents").resolve("MacOS").resolve("jcef Helper"),
            macHelperBinary(install),
        )
    }

    @Test
    fun launch_args_lead_with_the_three_bundle_paths_then_the_sanitized_rest() {
        // These must reach CEF's OWN command line (via the app handler), not just CefApp.startup:
        // CEF reads framework-dir-path / main-bundle-path in PreSandboxStartup, which is what makes
        // `icudtl.dat` resolvable. KCEF's default AppHandler carries an EMPTY arg array, so without
        // this list CEF initializes with no idea where its framework is.
        val install = Files.createTempDirectory("kcef-mac-args")
        val args = macCefLaunchArgs(
            install,
            jbrArgs = listOf("--use-mock-keychain", "--framework-dir-path=/stale"),
            ours = listOf("--no-sandbox"),
        )
        assertEquals(
            listOf(
                "--framework-dir-path=${macFrameworkDir(install)}",
                "--main-bundle-path=${macHelperApp(install)}",
                "--browser-subprocess-path=${macHelperBinary(install)}",
                "--use-mock-keychain",
                "--no-sandbox",
            ),
            args,
        )
        // Exactly one framework path survives — a stale duplicate would win by last-occurrence.
        assertEquals(1, args.count { it.startsWith("--framework-dir-path=") })
    }

    @Test
    fun a_fresh_install_dir_is_not_reported_incomplete() {
        // Nothing downloaded yet — KCEF.init must be allowed to fetch the bundle.
        val install = Files.createTempDirectory("kcef-mac-fresh")
        assertFalse(macBundleIncomplete(install))
    }

    @Test
    fun locked_install_without_the_framework_binary_is_incomplete() {
        // The crash-loop shape: KCEF believes it is installed, but the mac framework isn't there.
        val install = Files.createTempDirectory("kcef-mac-broken")
        install.resolve("install.lock").writeText("")
        assertTrue(macBundleIncomplete(install))
    }

    @Test
    fun locked_install_with_the_framework_binary_is_complete() {
        val install = Files.createTempDirectory("kcef-mac-ok")
        install.resolve("install.lock").writeText("")
        macFrameworkBinary(install).also { it.parent.createDirectories() }.writeText("mach-o")
        assertFalse(macBundleIncomplete(install))
    }
}
