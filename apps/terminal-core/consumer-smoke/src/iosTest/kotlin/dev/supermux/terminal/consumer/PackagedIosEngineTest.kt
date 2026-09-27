package dev.supermux.terminal.consumer

import kotlin.test.Test

/**
 * The iOS consumer check (runs on a Mac; every Apple task is disabled on this Linux host).
 *
 *   apps/gradlew -p apps/terminal-core/consumer-smoke iosSimulatorArm64Test
 *
 * On Apple targets the engine is not loaded at run time: `libsupermux_terminal.a` is linked into
 * the consumer's test binary through the cinterop of the published `terminal-core-iosarm64` /
 * `-iossimulatorarm64` klib. Linking and running this at all therefore proves the published klib
 * carries the static archive and its cinterop.
 *
 * NOTE: a dev publish made on Linux contains NO Apple publications (their tasks are disabled), so
 * this check needs a publish made on the Mac — see VERIFICATION.md, "Release vs dev publish".
 */
class PackagedIosEngineTest {
    @Test
    fun packagedStaticArchiveRunsTheFixture() {
        println("consumer-smoke(ios): " + ConsumerFixture.run())
    }
}
