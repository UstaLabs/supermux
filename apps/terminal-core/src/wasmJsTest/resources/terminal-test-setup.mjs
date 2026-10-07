// Test-only: load the terminal runtime BEFORE any test runs. The Kotlin test module imports this
// file (WasmTestSetup.kt), and ES module evaluation waits for this top-level await, so the shared
// commonTest suites (EngineContractTest, ...) run synchronously against an initialized runtime
// without any browser-specific code in them. A load failure does not abort the bundle: it is
// exported (and createTerminalEngine reports it with its typed reason).
import { initialize } from './terminal-loader.mjs';

export const setupFailure = await initialize().then(
  () => null,
  (e) => `${e && e.reason}: ${e && e.message}`,
);
