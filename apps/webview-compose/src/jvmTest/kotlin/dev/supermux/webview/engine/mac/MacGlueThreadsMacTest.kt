package dev.supermux.webview.engine.mac

import org.junit.Assume.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Minor 3: a thread the glue attaches to the JVM for an upcall (GCD's worker copying a snapshot's pixels) is detached
 * after it. Left attached, every worker GCD retires stays a Java thread for good. The real glue, on a Mac only.
 */
class MacGlueThreadsMacTest {
    @BeforeTest fun onlyOnAMac() {
        assumeTrue("the glue runs on a Mac only", MacPlatform.isMac)
        assertNull(MacNativeLibrary.ensureLoaded(), "the packaged glue loads")
    }

    @Test fun anUpcallFromAThreadOfTheGluesOwnLeavesNoJavaThreadBehind() {
        val threads = ManagementFactory.getThreadMXBean()
        MacGlue.testPostFromThreads(2) // the upcall's path warmed up first
        val before = threads.threadCount
        MacGlue.testPostFromThreads(25)
        val after = threads.threadCount
        assertTrue(after <= before + 2, "$before Java threads before 25 upcalls from threads that then ended, $after after")
    }
}
