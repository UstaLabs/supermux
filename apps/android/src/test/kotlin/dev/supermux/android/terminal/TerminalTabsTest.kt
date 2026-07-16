package dev.supermux.android.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTabsTest {
    @Test fun broker_list_is_the_cross_device_source_of_truth() {
        assertEquals(
            listOf("ios-terminal", "android-terminal"),
            reconcileTerminalTabs(
                remoteIds = listOf("ios-terminal", "android-terminal"),
                localIds = listOf("main"),
                pendingCreates = emptyMap(),
                pendingCloses = emptySet(),
                nowMs = 20_000L,
            ),
        )
    }

    @Test fun pending_local_create_survives_until_websocket_creates_it() {
        assertEquals(
            listOf("existing", "new-terminal"),
            reconcileTerminalTabs(
                remoteIds = listOf("existing"),
                localIds = listOf("existing", "new-terminal"),
                pendingCreates = mapOf("new-terminal" to 10_000L),
                pendingCloses = emptySet(),
                nowMs = 12_000L,
            ),
        )
    }

    @Test fun expired_unconfirmed_create_is_removed() {
        assertEquals(
            listOf("existing"),
            reconcileTerminalTabs(
                remoteIds = listOf("existing"),
                localIds = listOf("existing", "never-created"),
                pendingCreates = mapOf("never-created" to 1_000L),
                pendingCloses = emptySet(),
                nowMs = 20_000L,
            ),
        )
    }

    @Test fun pending_close_stays_hidden_while_broker_removal_finishes() {
        assertEquals(
            listOf("other"),
            reconcileTerminalTabs(
                remoteIds = listOf("closing", "other"),
                localIds = listOf("other"),
                pendingCreates = emptyMap(),
                pendingCloses = setOf("closing"),
                nowMs = 0L,
            ),
        )
    }

    @Test fun active_terminal_moves_to_nearest_survivor() {
        assertEquals("third", activeTerminalAfterSync(listOf("first", "third"), "second", preferredIndex = 1))
        assertEquals("first", activeTerminalAfterSync(listOf("first"), "second", preferredIndex = 1))
        assertEquals("", activeTerminalAfterSync(emptyList(), "second"))
    }
}
