import { expect, test } from "bun:test"

// tests/preload-isolate-state.ts points HOME at a throwaway folder so no test touches the real one. On Windows the
// home folder is USERPROFILE, not HOME (libuv uv_os_homedir, in Node and Bun alike), so the preload moves it too:
// otherwise a child process a test starts with `env: process.env` (an agent CLI, a keeper) would use the runner's
// real profile.
test("the test preload isolates USERPROFILE as well as HOME", () => {
  expect(process.env.USERPROFILE).toBe(process.env.HOME!)
})
