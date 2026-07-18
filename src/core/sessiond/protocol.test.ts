import { describe, expect, test } from "bun:test"
import {
  PROTOCOL_VERSION,
  parseRequest,
  type SessiondEvent,
  type SessiondResponse,
} from "./protocol"

const envelope = { id: "request-1", version: 1, secret: "install-secret" } as const

describe("sessiond request protocol", () => {
  test("accepts an authenticated create request", () => {
    const request = {
      ...envelope,
      op: "create",
      group: "workspace",
      name: "agent-1",
      cwd: "C:\\work\\supermux",
      argv: ["powershell.exe", "-NoLogo"],
      env: { TERM: "xterm-256color", SUPERMUX: "1" },
      cols: 120,
      rows: 40,
    }

    const parsed: unknown = parseRequest(request)
    expect(parsed).toEqual(request)
  })

  test("accepts hello only with the authenticated versioned envelope", () => {
    const parsed: unknown = parseRequest({ ...envelope, op: "hello" })
    expect(parsed).toEqual({ ...envelope, op: "hello" })
    expect(() => parseRequest({ id: "request-1", version: 1, op: "hello" })).toThrow(/secret/i)
    expect(() => parseRequest({ id: "request-1", secret: "install-secret", op: "hello" })).toThrow(/version/i)
  })

  test("accepts every operation's valid shape", () => {
    const requests: unknown[] = [
      { ...envelope, op: "hello" },
      {
        ...envelope,
        op: "create",
        group: "workspace",
        name: "agent-1",
        cwd: "C:\\work",
        argv: ["pwsh.exe"],
        env: {},
      },
      { ...envelope, op: "list" },
      { ...envelope, op: "list", group: "workspace" },
      { ...envelope, op: "resolve", group: "workspace", name: "agent-1" },
      { ...envelope, op: "livePid", targetId: "target-1" },
      { ...envelope, op: "write", targetId: "target-1", dataBase64: "aGVsbG8=" },
      { ...envelope, op: "sendKeys", targetId: "target-1", keys: ["Enter", "C-c"] },
      { ...envelope, op: "resize", targetId: "target-1", cols: 120, rows: 40 },
      { ...envelope, op: "capture", targetId: "target-1" },
      { ...envelope, op: "capture", targetId: "target-1", raw: true },
      { ...envelope, op: "attach", targetId: "target-1", viewerId: "viewer-1" },
      { ...envelope, op: "detach", targetId: "target-1", viewerId: "viewer-1" },
      { ...envelope, op: "interrupt", targetId: "target-1" },
      { ...envelope, op: "kill", targetId: "target-1" },
    ]

    for (const request of requests) {
      const parsed: unknown = parseRequest(request)
      expect(parsed).toEqual(request)
    }
  })

  test("rejects unknown operations and unsupported protocol versions", () => {
    expect(() => parseRequest({ ...envelope, op: "launch" })).toThrow(/unknown operation.*launch/i)
    expect(() => parseRequest({ ...envelope, version: 2, op: "hello" })).toThrow(/unsupported protocol version.*2/i)
    expect(() => parseRequest({ ...envelope, version: "1", op: "hello" })).toThrow(/version.*number/i)
  })

  test("rejects non-object inputs and missing or wrong envelope scalars", () => {
    expect(() => parseRequest(null)).toThrow(/request.*object/i)
    expect(() => parseRequest([])).toThrow(/request.*object/i)

    for (const [field, value, expected] of [
      ["id", undefined, /id.*nonempty string/i],
      ["id", "", /id.*nonempty string/i],
      ["id", 123, /id.*nonempty string/i],
      ["secret", undefined, /secret.*nonempty string/i],
      ["secret", "", /secret.*nonempty string/i],
      ["secret", false, /secret.*nonempty string/i],
      ["op", undefined, /op.*string/i],
      ["op", 3, /op.*string/i],
    ] as const) {
      expect(() => parseRequest({ ...envelope, op: "hello", [field]: value })).toThrow(expected)
    }
  })

  test("rejects missing or wrong operation-specific scalar values", () => {
    const invalid: Array<[unknown, RegExp]> = [
      [{ ...envelope, op: "create", group: 1, name: "n", cwd: "c", argv: ["x"], env: {} }, /group.*string/i],
      [{ ...envelope, op: "create", group: "g", name: null, cwd: "c", argv: ["x"], env: {} }, /name.*string/i],
      [{ ...envelope, op: "create", group: "g", name: "n", cwd: false, argv: ["x"], env: {} }, /cwd.*string/i],
      [{ ...envelope, op: "list", group: 4 }, /group.*string/i],
      [{ ...envelope, op: "resolve", group: "g" }, /name.*string/i],
      [{ ...envelope, op: "livePid", targetId: 3 }, /targetId.*string/i],
      [{ ...envelope, op: "sendKeys", targetId: "t", keys: ["Enter", 3] }, /keys.*string/i],
      [{ ...envelope, op: "capture", targetId: "t", raw: "true" }, /raw.*boolean/i],
      [{ ...envelope, op: "attach", targetId: "t", viewerId: false }, /viewerId.*string/i],
      [{ ...envelope, op: "detach", viewerId: "v" }, /targetId.*string/i],
      [{ ...envelope, op: "interrupt" }, /targetId.*string/i],
      [{ ...envelope, op: "kill", targetId: null }, /targetId.*string/i],
    ]

    for (const [request, expected] of invalid) expect(() => parseRequest(request)).toThrow(expected)
  })

  test("validates terminal dimension integer boundaries", () => {
    for (const [cols, rows] of [[1, 1], [1000, 1000]]) {
      expect(parseRequest({ ...envelope, op: "resize", targetId: "target-1", cols, rows })).toMatchObject({ cols, rows })
    }

    for (const [field, value] of [
      ["cols", 0],
      ["cols", 1001],
      ["cols", 12.5],
      ["cols", "80"],
      ["rows", 0],
      ["rows", 1001],
      ["rows", Number.NaN],
    ] as const) {
      expect(() => parseRequest({
        ...envelope,
        op: "resize",
        targetId: "target-1",
        cols: 80,
        rows: 24,
        [field]: value,
      })).toThrow(new RegExp(`${field}.*integer.*1.*1000`, "i"))
    }

    expect(() => parseRequest({
      ...envelope,
      op: "create",
      group: "g",
      name: "n",
      cwd: "c",
      argv: ["pwsh"],
      env: {},
      cols: 0,
    })).toThrow(/cols.*integer.*1.*1000/i)
  })

  test("rejects empty or malformed create argv", () => {
    const base = { ...envelope, op: "create", group: "g", name: "n", cwd: "c", env: {} }
    expect(() => parseRequest({ ...base, argv: [] })).toThrow(/argv.*nonempty/i)
    expect(() => parseRequest({ ...base, argv: "pwsh" })).toThrow(/argv.*array/i)
    expect(() => parseRequest({ ...base, argv: ["pwsh", 1] })).toThrow(/argv.*string/i)
  })

  test("rejects invalid create environments", () => {
    const base = { ...envelope, op: "create", group: "g", name: "n", cwd: "c", argv: ["pwsh"] }
    expect(() => parseRequest({ ...base, env: null })).toThrow(/env.*record/i)
    expect(() => parseRequest({ ...base, env: [] })).toThrow(/env.*record/i)
    expect(() => parseRequest({ ...base, env: { TERM: 1 } })).toThrow(/env.*string values/i)
  })

  test("rejects malformed base64 write data", () => {
    expect(() => parseRequest({ ...envelope, op: "write", targetId: "target-1", dataBase64: 4 })).toThrow(/dataBase64.*string/i)
    for (const dataBase64 of ["abc", "aGVsbG8", "aGV=bG8=", "!!!!", "YW Jj"])
      expect(() => parseRequest({ ...envelope, op: "write", targetId: "target-1", dataBase64 })).toThrow(/dataBase64.*base64/i)
  })
})

test("response and event types expose the wire contract", () => {
  const response: SessiondResponse = { id: "request-1", ok: false, error: "not found" }
  const dataEvent: SessiondEvent = { event: "data", targetId: "target-1", viewerId: "viewer-1", dataBase64: "YQ==" }
  const exitEvent: SessiondEvent = { event: "exit", targetId: "target-1", code: 0 }

  expect(PROTOCOL_VERSION).toBe(1)
  expect([response, dataEvent, exitEvent]).toHaveLength(3)
})
