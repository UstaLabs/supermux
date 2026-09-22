package dev.supermux.terminal.sample

/**
 * iOS memory: **not measured**.
 *
 * The footprint iOS actually enforces is `phys_footprint` from `task_vm_info`, which needs a
 * `task_info` cinterop the sample does not have, and the numbers `NSProcessInfo` offers are about
 * the device, not the process. Reporting a wrong number in a performance panel is worse than
 * reporting none, so this actual says "unavailable" and the panel prints `0 B · via …`.
 *
 * Measuring the iOS sample means Instruments (Allocations / VM Tracker) attached to the running
 * app, which is a Mac task; see `benchmarks/2026-09-terminal.md`.
 */
actual fun readSampleMemory(): SampleMemory =
    SampleMemory(source = "unavailable on iOS (use Instruments)")
