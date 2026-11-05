import { describe, expect, test } from "bun:test"
import { buildMenuEntries, menuRefresher, sanitizeCommand, type SessionLister } from "./menu"

const TG_COMMAND_RE = /^[a-z0-9_]{1,32}$/

function registryWith(...names: string[]): SessionLister {
  return { listVisible: () => names.map(name => ({ name })) }
}

const BASE_COUNT = buildMenuEntries(registryWith()).length

function sessionEntries(...names: string[]) {
  return buildMenuEntries(registryWith(...names)).slice(BASE_COUNT)
}

describe("sanitizeCommand", () => {
  test("lowercases", () => {
    expect(sanitizeCommand("MySession")).toBe("mysession")
  })

  test("maps spaces and punctuation to single underscores", () => {
    expect(sanitizeCommand("fix login bug!")).toBe("fix_login_bug")
    expect(sanitizeCommand("a--b..c")).toBe("a_b_c")
  })

  test("collapses underscore runs and trims edges", () => {
    expect(sanitizeCommand("__a___b__")).toBe("a_b")
  })

  test("strips unicode and emoji", () => {
    expect(sanitizeCommand("café ☕ run")).toBe("caf_run")
    expect(sanitizeCommand("Übung-1")).toBe("bung_1")
  })

  test("cuts to 32 chars without a trailing underscore", () => {
    const long = "a".repeat(31) + "_zzz"
    const out = sanitizeCommand(long)
    expect(out).toBe("a".repeat(31))
    expect(out.length).toBeLessThanOrEqual(32)
  })

  test("returns empty string when nothing survives", () => {
    expect(sanitizeCommand("🎉🎉🎉")).toBe("")
    expect(sanitizeCommand("→ 中文 →")).toBe("")
    expect(sanitizeCommand("---")).toBe("")
    expect(sanitizeCommand("")).toBe("")
  })
})

describe("buildMenuEntries", () => {
  test("every emitted command is valid for Telegram", () => {
    const entries = buildMenuEntries(
      registryWith("My Repo", "über-Fix!", "x".repeat(60), "🎉", "a b", "A_B"),
    )
    for (const e of entries) {
      expect(e.command).toMatch(TG_COMMAND_RE)
    }
  })

  test("uppercase and hyphenated names become lowercase underscore commands", () => {
    const [entry] = sessionEntries("My-Repo")
    expect(entry?.command).toBe("switch_to_my_repo")
  })

  test("description keeps the original pretty name", () => {
    const [entry] = sessionEntries("My Répo 🎉")
    expect(entry?.description).toBe("→ My Répo 🎉")
    expect(entry?.command).toBe("switch_to_my_r_po")
  })

  test("long names are trimmed so the command fits 32 chars", () => {
    const [entry] = sessionEntries("this-is-a-very-long-session-name-for-testing")
    expect(entry).toBeDefined()
    expect(entry!.command.length).toBeLessThanOrEqual(32)
    expect(entry!.command.startsWith("switch_to_")).toBe(true)
    expect(entry!.command).toMatch(TG_COMMAND_RE)
  })

  test("colliding names get _2 style suffixes", () => {
    const entries = sessionEntries("my repo", "My-Repo", "my_repo")
    expect(entries.map(e => e.command)).toEqual([
      "switch_to_my_repo",
      "switch_to_my_repo_2",
      "switch_to_my_repo_3",
    ])
    // Labels still tell the sessions apart.
    expect(entries.map(e => e.description)).toEqual(["→ my repo", "→ My-Repo", "→ my_repo"])
  })

  test("collision on a max-length command still fits 32 chars", () => {
    const long = "z".repeat(40)
    const entries = sessionEntries(long, long)
    expect(entries).toHaveLength(2)
    expect(entries[0]?.command).not.toBe(entries[1]?.command)
    for (const e of entries) {
      expect(e.command.length).toBeLessThanOrEqual(32)
      expect(e.command).toMatch(TG_COMMAND_RE)
    }
  })

  test("a name that sanitizes to nothing is skipped entirely", () => {
    const entries = buildMenuEntries(registryWith("🎉🎉🎉", "→→→"))
    expect(entries).toHaveLength(BASE_COUNT)
    for (const e of entries) {
      expect(e.command.startsWith("switch_to")).toBe(false)
    }
  })

  test("base commands are present and unchanged", () => {
    const entries = buildMenuEntries(registryWith())
    const commands = entries.map(e => e.command)
    expect(commands).toContain("sessions")
    expect(commands).toContain("spawn_codex")
    expect(new Set(commands).size).toBe(commands.length)
  })
})

describe("menuRefresher (card #75)", () => {
  test("a session start does not wait for the refresh: inBackground returns while it is still running", () => {
    let started = 0
    const menu = menuRefresher(() => { started++; return new Promise<void>(() => {}) }, () => {})
    menu.inBackground()
    expect(started).toBe(1)
  })

  test("the same failure is logged once until a refresh succeeds, and nothing throws", async () => {
    const warned: string[] = []
    let fail: string | undefined = "400: BOT_COMMANDS_TOO_MUCH"
    const menu = menuRefresher(async () => { if (fail) throw new Error(fail) }, (err) => warned.push(err))
    await menu.refresh()
    await menu.refresh()
    menu.inBackground()
    await Bun.sleep(0)
    expect(warned).toEqual(["400: BOT_COMMANDS_TOO_MUCH"])
    fail = undefined
    await menu.refresh()
    fail = "400: BOT_COMMANDS_TOO_MUCH"
    await menu.refresh()
    expect(warned).toEqual(["400: BOT_COMMANDS_TOO_MUCH", "400: BOT_COMMANDS_TOO_MUCH"])
  })
})
