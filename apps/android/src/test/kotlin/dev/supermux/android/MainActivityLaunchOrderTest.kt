package dev.supermux.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the launch ordering invariant in `MainActivity` (see [launchOrder]'s KDoc): the
 * `AppViewModel` is created only AFTER the debug pairing seed, the legacy single-host migration
 * and the pairing gate. A VM built earlier snapshots an empty paired-host list in
 * `FleetStore.init` and never re-syncs, leaving a freshly-paired install hostless until restart.
 */
class MainActivityLaunchOrderTest {

    @Test
    fun an_unpaired_launch_never_creates_the_view_model() {
        assertEquals(
            listOf(LaunchStep.DebugSeed, LaunchStep.LegacyMigration, LaunchStep.PairingGate),
            launchOrder(paired = false),
        )
    }

    @Test
    fun a_paired_launch_creates_the_view_model_last() {
        val order = launchOrder(paired = true)
        assertEquals(LaunchStep.CreateViewModel, order.last())
        assertTrue(order.indexOf(LaunchStep.LegacyMigration) < order.indexOf(LaunchStep.CreateViewModel))
        assertTrue(order.indexOf(LaunchStep.PairingGate) < order.indexOf(LaunchStep.CreateViewModel))
    }

    @Test
    fun the_debug_seed_runs_before_the_legacy_migration() {
        // A debug-seeded token has to be visible to the migration, or the emulator boots hostless.
        val order = launchOrder(paired = true)
        assertTrue(order.indexOf(LaunchStep.DebugSeed) < order.indexOf(LaunchStep.LegacyMigration))
    }
}
