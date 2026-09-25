package dev.supermux.editor.syntax

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopLoadTest {
    @Test fun loadsTheLibraryFromJarResources() {
        assertTrue(System.getProperty("editor.syntax.lib") == null, "run through jvmResourceLoadTest")
        assertTrue("json" in SyntaxLanguages.names())
        // A code-only grammar's tables come from the jar's resources, as for any consumer.
        NativeBackend().apply { ensureLanguage("haskell") }.newParser("haskell").use { p ->
            p.parse(ChunkedSource("main = print 1\n"), null).use { assertTrue(!it.hasError) }
        }
        // ...through the content-addressed cache (macOS: ~/Library/Caches/supermux/natives/<sha256>/).
        if (System.getProperty("os.name").lowercase().startsWith("mac")) {
            val key = SesNativeLoader.platformKey()!!
            val props = Properties().apply {
                SesNativeLoader::class.java.getResourceAsStream("${SesNativeLoader.RESOURCE_ROOT}/$key/native.properties")!!.use { load(it) }
            }
            val cached = File(System.getProperty("user.home"), "Library/Caches/supermux/natives/${props["sha256"]}/${props["library"]}")
            assertTrue(cached.isFile, "$cached")
        }
    }
}
