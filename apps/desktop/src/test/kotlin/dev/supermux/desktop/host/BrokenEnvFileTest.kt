package dev.supermux.desktop.host

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

// #113: a power loss on Windows (the test VM's hard stop) left the task's env file as 819 zero bytes. With no install id
// in it the app took its own task for another copy's and refused to start, with no way out. A broken env file of our
// task is now ours (and the next install rewrites it), and the file is flushed to disk before it is moved into place.
class BrokenEnvFileTest {
    private val me = BrokerService.Owner("b39407e310ec4e7c81f5041cba538bcb")

    @Test fun aZeroedOrEmptyEnvFileOfOurTaskIsOursNotAnotherCopys() {
        for (broken in listOf("\u0000".repeat(819), "", "  \r\n")) {
            assertEquals(BrokerService.Ownership.OURS, BrokerService.ownership(broken, marked = true, legacy = false, me = me), "env: ${broken.length} chars")
        }
    }

    // The task is registered but its env file is gone: nothing says another copy wrote it (the file is per user).
    @Test fun aMissingEnvFileOfOurTaskIsOursNotAnotherCopys() {
        assertEquals(BrokerService.Ownership.OURS, BrokerService.ownership(null, marked = true, legacy = false, me = me))
    }

    @Test fun aReadableEnvFileStillDecides() {
        val theirs = "\$env:MUX_WEB_PORT = '9898'\r\n\$env:MUX_DESKTOP_INSTALL = 'someone-else'\r\n"
        assertEquals(BrokerService.Ownership.OTHER, BrokerService.ownership(theirs, marked = true, legacy = false, me = me))
        val unstamped = "\$env:MUX_WEB_PORT = '9898'\r\n"
        assertEquals(BrokerService.Ownership.OTHER, BrokerService.ownership(unstamped, marked = true, legacy = false, me = me))
    }

    @Test fun theFileIsOnDiskBeforeItIsMovedIntoPlace() {
        val file = createTempDirectory().resolve("Supermux/broker-env.ps1")
        val seen = mutableListOf<Boolean>()
        BrokerService.writePrivate(file, "\$env:A = '1'\r\n", sync = { seen += Files.exists(file) })
        assertEquals(listOf(false), seen, "flushed once, before the move")
        assertEquals("\$env:A = '1'\r\n", Files.readString(file))
    }
}
