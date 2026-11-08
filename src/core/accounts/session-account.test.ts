import { describe, expect, test } from "bun:test"
import { AccountsApiError } from "./broker-accounts"
import { setSessionAccount, type SessionAccountDeps } from "./session-account"

function deps({ live, ...over }: Partial<Omit<SessionAccountDeps, "adapter">> & { live?: { setAccount(id: string): Promise<void> } } = {}) {
  const stored: Array<[string, string]> = []
  const d: SessionAccountDeps = {
    session: (id) => (id === "s1" ? { id: "s1", agent: "claude" } : undefined),
    resolve: async (_agent, account) => account,
    adapter: () => live,
    waking: () => false,
    store: (id, account) => { stored.push([id, account]) },
    ...over,
  }
  return { d, stored }
}

describe("setSessionAccount (#106)", () => {
  test("a sleeping session stores the account for its next wake", async () => {
    const { d, stored } = deps()
    expect(await setSessionAccount(d, "s1", "claude-b")).toEqual({ session: "s1", account: "claude-b", applied: "next_wake" })
    expect(stored).toEqual([["s1", "claude-b"]])
  })

  test("a running session switches now, then the account is stored", async () => {
    const calls: string[] = []
    const { d, stored } = deps({ live: { async setAccount(id) { calls.push(id) } } })
    expect(await setSessionAccount(d, "s1", "claude:system")).toEqual({ session: "s1", account: "claude:system", applied: "now" })
    expect(calls).toEqual(["claude:system"])
    expect(stored).toEqual([["s1", "claude:system"]])
  })

  test("a session that is starting is refused with 409, nothing stored", async () => {
    const { d, stored } = deps({ waking: () => true })
    await expect(setSessionAccount(d, "s1", "claude-b")).rejects.toMatchObject({ status: 409, code: "session_busy" })
    expect(stored).toEqual([])
  })

  test("an unknown session is 404; an invalid account keeps the resolver's error, nothing stored", async () => {
    const { d, stored } = deps({ resolve: async () => { throw new AccountsApiError(404, "Account x was not found", "unknown_account") } })
    await expect(setSessionAccount(d, "nope", "claude-b")).rejects.toMatchObject({ status: 404, code: "session_not_found" })
    await expect(setSessionAccount(d, "s1", "x")).rejects.toMatchObject({ status: 404, code: "unknown_account" })
    expect(stored).toEqual([])
  })
})
