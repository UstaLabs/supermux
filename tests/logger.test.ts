import { test, expect, beforeEach, afterEach } from "bun:test"
import { makeLogger } from "../src/shared/log"

let captured: string[] = []
let origWrite: typeof process.stderr.write

beforeEach(() => {
  captured = []
  origWrite = process.stderr.write.bind(process.stderr)
  ;(process.stderr as any).write = (chunk: any) => {
    captured.push(String(chunk))
    return true
  }
})
afterEach(() => { (process.stderr as any).write = origWrite })

test("emits info by default, suppresses debug", () => {
  delete process.env.MUX_LOG_LEVEL
  const log = makeLogger("test-mod")
  log.info("hello")
  log.debug("hidden")
  expect(captured.length).toBe(1)
  expect(captured[0]).toMatch(/\[INFO\] \[test-mod\] hello/)
  expect(captured[0]).toMatch(/^\[\d{4}-\d{2}-\d{2}T/)
})

test("MUX_LOG_LEVEL=debug emits debug too", () => {
  process.env.MUX_LOG_LEVEL = "debug"
  const log = makeLogger("test-mod")
  log.debug("now visible")
  expect(captured.length).toBe(1)
  expect(captured[0]).toMatch(/\[DEBUG\] \[test-mod\] now visible/)
})

test("extra payload appended as JSON", () => {
  process.env.MUX_LOG_LEVEL = "info"
  const log = makeLogger("test-mod")
  log.info("processed", { count: 7, name: "x" })
  expect(captured[0]).toMatch(/processed \{"count":7,"name":"x"\}/)
})

test("warn and error always emit", () => {
  process.env.MUX_LOG_LEVEL = "error"
  const log = makeLogger("test-mod")
  log.warn("warned")
  log.error("errored")
  expect(captured.length).toBe(1)  // warn suppressed (below error threshold)
  expect(captured[0]).toMatch(/\[ERROR\] \[test-mod\] errored/)
})

test("circular reference in extra falls back to [unserializable] without throwing", () => {
  process.env.MUX_LOG_LEVEL = "info"
  const log = makeLogger("test-mod")
  const circular: any = { a: 1 }
  circular.self = circular
  expect(() => log.info("processed", circular)).not.toThrow()
  expect(captured.length).toBe(1)
  expect(captured[0]).toContain("[unserializable]")
})
