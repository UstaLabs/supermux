package dev.supermux.editor.syntax

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS: a code-only grammar's tables come from the main bundle, as in Supermux.app. */
class MainBundleTablesTest {
    @Test fun aCodeOnlyGrammarLoadsFromTheMainBundle() {
        assertTrue(SyntaxResources.extraDirectories.isEmpty(), "no extra directory: the bundle lookup alone")
        val file = NSBundle.mainBundle.resourcePath + "/" + NativeBackend.tablesPath("ruby")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(file), "$file is not in the main bundle")
        val backend = NativeBackend()
        backend.ensureLanguageNow("ruby")
        backend.newParser("ruby").use { p ->
            p.parse(ChunkedSource("def greet(name)\n  puts \"hi #{name}\"\nend\n"), null).use { t -> assertFalse(t.hasError) }
        }
        assertEquals(true, backend.isReady("ruby"))
    }
}
