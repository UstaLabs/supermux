import { describe, expect, test } from "bun:test"
import { ByteRing, SessionScreen } from "./screen"

const encoder = new TextEncoder()

function bytes(value: string): Uint8Array {
  return encoder.encode(value)
}

describe("SessionScreen", () => {
  test("renders ANSI into canonical text while preserving the raw stream", async () => {
    const screen = new SessionScreen(20, 4, 1024)
    const output = "hello\r\n\x1b[31mred\x1b[0m"

    await screen.write(bytes(output))

    expect(screen.captureText()).toBe("hello\nred")
    expect(screen.captureRaw()).toBe(output)
    screen.dispose()
  })

  test("preserves wide and combining Unicode characters", async () => {
    const screen = new SessionScreen(12, 3, 1024)

    await screen.write(bytes("界 e\u0301 🙂"))

    expect(screen.captureText()).toBe("界 e\u0301 🙂")
    expect(screen.captureRaw()).toBe("界 e\u0301 🙂")
    screen.dispose()
  })

  test("captures the active alternate screen and restores normal content on exit", async () => {
    const screen = new SessionScreen(20, 3, 1024)

    await screen.write(bytes("normal"))
    await screen.write(bytes("\x1b[?1049h\x1b[Halternate"))
    expect(screen.captureText()).toBe("alternate")

    await screen.write(bytes("\x1b[?1049l"))
    expect(screen.captureText()).toBe("normal")
    expect(screen.captureRaw()).toBe("normal\x1b[?1049h\x1b[Halternate\x1b[?1049l")
    screen.dispose()
  })

  test("resizes the canonical terminal and reflows wrapped content", async () => {
    const screen = new SessionScreen(5, 2, 1024)
    await screen.write(bytes("abcdefghij\r\nX\r\nY"))

    expect(screen.dimensions()).toEqual({ cols: 5, rows: 2 })
    expect(screen.captureText()).toBe("abcde\nfghij\nX\nY")

    screen.resize(10, 3)
    expect(screen.dimensions()).toEqual({ cols: 10, rows: 3 })
    expect(screen.captureText()).toBe("abcdefghij\nX\nY")
    screen.dispose()
  })

  test("includes scrollback, preserves interior blank lines, and only right-trims rows", async () => {
    const screen = new SessionScreen(10, 3, 1024)

    await screen.write(bytes("  one   \r\n\r\nmid dle  \r\nlast    \r\ntail"))

    expect(screen.captureText()).toBe("  one\n\nmid dle\nlast\ntail")
    screen.dispose()
  })

  test("keeps a strict raw byte tail across multiple writes", async () => {
    const screen = new SessionScreen(20, 2, 7)

    await screen.write(bytes("abc"))
    await screen.write(bytes("def"))
    await screen.write(bytes("ghi"))

    expect(screen.captureRaw()).toBe("cdefghi")
    screen.dispose()
  })

  test("keeps exact fixed-capacity storage semantics under many tiny writes", async () => {
    const ring = new ByteRing(7)
    const writes = Array.from({ length: 10_000 }, (_, index) => bytes(String(index % 10)))

    for (const write of writes) ring.append(write)

    expect(new TextDecoder().decode(ring.bytes())).toBe("3456789")

    const screen = new SessionScreen(80, 2, 7)
    await Promise.all(writes.map((write) => screen.write(write)))
    expect(screen.captureRaw()).toBe("3456789")
    screen.dispose()
  })

  test("retains only the newest bytes of one oversized raw write", async () => {
    const screen = new SessionScreen(20, 2, 5)

    await screen.write(bytes("0123456789"))

    expect(screen.captureRaw()).toBe("56789")
    screen.dispose()
  })

  test("handles a raw byte limit that cuts through a multi-byte UTF-8 character", async () => {
    const screen = new SessionScreen(20, 2, 5)

    await screen.write(bytes("Aé🙂"))

    // The ring is byte-bounded, not JavaScript-character-bounded. TextDecoder
    // deterministically replaces the retained leading continuation byte.
    expect(screen.captureRaw()).toBe("�🙂")
    screen.dispose()
  })

  test("awaits parser completion and preserves concurrent write ordering", async () => {
    const screen = new SessionScreen(30, 2, 1024)

    const first = screen.write(bytes("first"))
    const second = screen.write(bytes(" second"))
    const mutable = bytes(" immutable")
    const third = screen.write(mutable)
    mutable.fill("x".charCodeAt(0))
    await Promise.all([first, second, third])

    expect(screen.captureText()).toBe("first second immutable")
    expect(screen.captureRaw()).toBe("first second immutable")
    screen.dispose()
  })

  test("validates dimensions and has deterministic disposal", async () => {
    expect(() => new SessionScreen(0, 24, 1024)).toThrow(/cols.*positive integer/i)
    expect(() => new SessionScreen(80, 1.5, 1024)).toThrow(/rows.*positive integer/i)
    expect(() => new SessionScreen(1001, 24, 1024)).toThrow(/cols.*1000/i)
    expect(() => new SessionScreen(80, 24, -1)).toThrow(/rawByteLimit.*non-negative integer/i)

    const screen = new SessionScreen(80, 24, 0)
    await screen.write(bytes("discarded raw data"))
    expect(screen.captureRaw()).toBe("")

    expect(() => screen.resize(-1, 20)).toThrow(/cols.*positive integer/i)
    screen.dispose()
    screen.dispose()

    await expect(screen.write(bytes("late"))).rejects.toThrow(/disposed/i)
    expect(() => screen.captureText()).toThrow(/disposed/i)
    expect(() => screen.captureRaw()).toThrow(/disposed/i)
    expect(() => screen.resize(80, 24)).toThrow(/disposed/i)
    expect(() => screen.dimensions()).toThrow(/disposed/i)
  })
})
