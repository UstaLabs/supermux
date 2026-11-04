import { expect, test } from "bun:test"
import { join } from "node:path"

// tests/preload-isolate-state.ts points HOME at a throwaway folder so no test touches the real one. On Windows the
// home folder is USERPROFILE, not HOME (libuv uv_os_homedir, in Node and Bun alike), so the preload moves it too:
// otherwise a child process a test starts with `env: process.env` (an agent CLI, a keeper) would use the runner's
// real profile.
test("the test preload isolates USERPROFILE as well as HOME", () => {
  expect(process.env.USERPROFILE).toBe(process.env.HOME!)
})

// On Windows the agents' config and data folders are %APPDATA% and %LOCALAPPDATA% (cursor-agent's login, opencode's
// store: src/core/agents/detect.ts), not folders under HOME, so the preload moves those into the test home too.
test("the test preload isolates APPDATA and LOCALAPPDATA inside the test home", () => {
  expect(process.env.APPDATA).toBe(join(process.env.HOME!, "AppData", "Roaming"))
  expect(process.env.LOCALAPPDATA).toBe(join(process.env.HOME!, "AppData", "Local"))
})
