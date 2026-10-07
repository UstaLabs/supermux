import { expect, test } from "bun:test"
import { muxServersInInit, watchClaudeMuxShimDuplicates } from "./duplicates"

const init = (servers: Array<{ name: string; status?: string }>) => ({ type: "system", subtype: "init", session_id: "n", mcp_servers: servers.map(s => ({ status: "connected", ...s })) })

test("counts mux-shim / mux-channel servers in a Claude init frame; anything else is not an init", () => {
  expect(muxServersInInit(init([{ name: "mux-shim" }, { name: "github" }]))).toEqual({ muxShim: 1, muxChannel: 0 })
  expect(muxServersInInit(init([{ name: "mux-shim" }, { name: "mux-shim" }, { name: "mux-channel" }]))).toEqual({ muxShim: 2, muxChannel: 1 })
  expect(muxServersInInit({ type: "assistant" })).toBeUndefined()
  expect(muxServersInInit({ type: "system", subtype: "init" })).toEqual({ muxShim: 0, muxChannel: 0 })
})

test("the watcher warns once per session for two mux-shim servers, from the core's native session.update events", () => {
  let listener: ((event: any) => void) | undefined
  const core = { subscribe: (fn: (event: any) => void) => { listener = fn; return () => {} } }
  const warnings: Array<[string, Record<string, unknown>]> = []
  watchClaudeMuxShimDuplicates(core as any, { warn: (event, fields) => warnings.push([event, fields]), info: () => {} }, () => "host")
  listener!({ type: "session.update", sessionId: "s1", update: { protocol: "native", value: init([{ name: "mux-shim" }]) } })
  expect(warnings).toEqual([])
  const twice = { type: "session.update", sessionId: "s2", update: { protocol: "native", value: init([{ name: "mux-shim" }, { name: "mux-shim", status: "failed" }]) } }
  listener!(twice); listener!(twice)
  expect(warnings).toEqual([["claude_duplicate_mux_shim", { session: "s2", mode: "host", servers: ["mux-shim:connected", "mux-shim:failed"] }]])
})
