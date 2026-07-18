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
      args: {
        group: "workspace",
        name: "agent-1",
        cwd: "C:\\work\\supermux",
        argv: ["powershell.exe", "-NoLogo"],
        env: { TERM: "xterm-256color", SUPERMUX: "1" },
        cols: 120,
        rows: 40,
      },
    }

    const parsed: unknown = parseRequest(request)
    expect(parsed).toEqual(request)
  })

  test("accepts hello only with the authenticated versioned envelope", () => {
    const parsed: unknown = parseRequest({ ...envelope, op: "hello", args: {} })
    expect(parsed).toEqual({ ...envelope, op: "hello", args: {} })
    expect(() => parseRequest({ id: "request-1", version: 1, op: "hello", args: {} })).toThrow(/secret/i)
    expect(() => parseRequest({ id: "request-1", secret: "install-secret", op: "hello", args: {} })).toThrow(/version/i)
  })

  test("accepts every operation's valid shape", () => {
    const requests: unknown[] = [
      { ...envelope, op: "hello", args: {} },
      {
        ...envelope,
        op: "create",
        args: { group: "workspace", name: "agent-1", cwd: "C:\\work", argv: ["pwsh.exe"], env: {} },
      },
      { ...envelope, op: "list", args: {} },
      { ...envelope, op: "list", args: { group: "workspace" } },
      { ...envelope, op: "resolve", args: { group: "workspace", name: "agent-1" } },
      { ...envelope, op: "livePid", args: { targetId: "target-1" } },
      { ...envelope, op: "write", args: { targetId: "target-1", dataBase64: "aGVsbG8=" } },
      { ...envelope, op: "sendKeys", args: { targetId: "target-1", keys: ["Enter", "C-c"] } },
      { ...envelope, op: "resize", args: { targetId: "target-1", cols: 120, rows: 40 } },
      { ...envelope, op: "capture", args: { targetId: "target-1" } },
      { ...envelope, op: "capture", args: { targetId: "target-1", raw: true } },
      { ...envelope, op: "attach", args: { targetId: "target-1", viewerId: "viewer-1" } },
      { ...envelope, op: "detach", args: { targetId: "target-1", viewerId: "viewer-1" } },
      { ...envelope, op: "interrupt", args: { targetId: "target-1" } },
      { ...envelope, op: "kill", args: { targetId: "target-1" } },
    ]

    for (const request of requests) {
      const parsed: unknown = parseRequest(request)
      expect(parsed).toEqual(request)
    }
  })

  test("rejects unknown operations and unsupported protocol versions", () => {
    expect(() => parseRequest({ ...envelope, op: "launch", args: {} })).toThrow(/unknown operation.*launch/i)
    expect(() => parseRequest({ ...envelope, version: 2, op: "hello", args: {} })).toThrow(/unsupported protocol version.*2/i)
    expect(() => parseRequest({ ...envelope, version: "1", op: "hello", args: {} })).toThrow(/version.*number/i)
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
      expect(() => parseRequest({ ...envelope, op: "hello", args: {}, [field]: value })).toThrow(expected)
    }
  })

  test("rejects missing or non-object operation args", () => {
    expect(() => parseRequest({ ...envelope, op: "hello" })).toThrow(/args.*object/i)
    expect(() => parseRequest({ ...envelope, op: "hello", args: null })).toThrow(/args.*object/i)
    expect(() => parseRequest({ ...envelope, op: "list", args: [] })).toThrow(/args.*object/i)
  })

  test("rejects missing or wrong operation-specific scalar values", () => {
    const invalid: Array<[unknown, RegExp]> = [
      [{ ...envelope, op: "create", args: { group: 1, name: "n", cwd: "c", argv: ["x"], env: {} } }, /group.*string/i],
      [{ ...envelope, op: "create", args: { group: "g", name: null, cwd: "c", argv: ["x"], env: {} } }, /name.*string/i],
      [{ ...envelope, op: "create", args: { group: "g", name: "n", cwd: false, argv: ["x"], env: {} } }, /cwd.*string/i],
      [{ ...envelope, op: "list", args: { group: 4 } }, /group.*string/i],
      [{ ...envelope, op: "resolve", args: { group: "g" } }, /name.*string/i],
      [{ ...envelope, op: "livePid", args: { targetId: 3 } }, /targetId.*string/i],
      [{ ...envelope, op: "sendKeys", args: { targetId: "t", keys: ["Enter", 3] } }, /keys.*string/i],
      [{ ...envelope, op: "capture", args: { targetId: "t", raw: "true" } }, /raw.*boolean/i],
      [{ ...envelope, op: "attach", args: { targetId: "t", viewerId: false } }, /viewerId.*string/i],
      [{ ...envelope, op: "detach", args: { viewerId: "v" } }, /targetId.*string/i],
      [{ ...envelope, op: "interrupt", args: {} }, /targetId.*string/i],
      [{ ...envelope, op: "kill", args: { targetId: null } }, /targetId.*string/i],
    ]

    for (const [request, expected] of invalid) expect(() => parseRequest(request)).toThrow(expected)
  })

  test("validates terminal dimension integer boundaries", () => {
    for (const [cols, rows] of [[1, 1], [1000, 1000]]) {
      expect(parseRequest({ ...envelope, op: "resize", args: { targetId: "target-1", cols, rows } })).toMatchObject({ args: { cols, rows } })
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
        args: { targetId: "target-1", cols: 80, rows: 24, [field]: value },
      })).toThrow(new RegExp(`${field}.*integer.*1.*1000`, "i"))
    }

    expect(() => parseRequest({
      ...envelope,
      op: "create",
      args: { group: "g", name: "n", cwd: "c", argv: ["pwsh"], env: {}, cols: 0 },
    })).toThrow(/cols.*integer.*1.*1000/i)
  })

  test("rejects empty or malformed create argv", () => {
    const base = { ...envelope, op: "create" }
    const args = { group: "g", name: "n", cwd: "c", env: {} }
    expect(() => parseRequest({ ...base, args: { ...args, argv: [] } })).toThrow(/argv.*nonempty/i)
    expect(() => parseRequest({ ...base, args: { ...args, argv: "pwsh" } })).toThrow(/argv.*array/i)
    expect(() => parseRequest({ ...base, args: { ...args, argv: ["pwsh", 1] } })).toThrow(/argv.*string/i)
  })

  test("rejects invalid create environments", () => {
    const base = { ...envelope, op: "create" }
    const args = { group: "g", name: "n", cwd: "c", argv: ["pwsh"] }
    expect(() => parseRequest({ ...base, args: { ...args, env: null } })).toThrow(/env.*record/i)
    expect(() => parseRequest({ ...base, args: { ...args, env: [] } })).toThrow(/env.*record/i)
    expect(() => parseRequest({ ...base, args: { ...args, env: { TERM: 1 } } })).toThrow(/env.*string values/i)
  })

  test("rejects malformed base64 write data", () => {
    expect(() => parseRequest({ ...envelope, op: "write", args: { targetId: "target-1", dataBase64: 4 } })).toThrow(/dataBase64.*string/i)
    for (const dataBase64 of ["abc", "aGVsbG8", "aGV=bG8=", "!!!!", "YW Jj"])
      expect(() => parseRequest({ ...envelope, op: "write", args: { targetId: "target-1", dataBase64 } })).toThrow(/dataBase64.*base64/i)
  })
})

test("response and event types expose the wire contract", () => {
  const response: SessiondResponse = { id: "request-1", ok: false, error: "not found" }
  const dataEvent: SessiondEvent = { event: "data", targetId: "target-1", viewerId: "viewer-1", dataBase64: "YQ==" }
  const exitEvent: SessiondEvent = { event: "exit", targetId: "target-1", code: 0 }

  expect(PROTOCOL_VERSION).toBe(1)
  expect([response, dataEvent, exitEvent]).toHaveLength(3)
})
