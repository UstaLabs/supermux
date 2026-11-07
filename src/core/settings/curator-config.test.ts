import { describe, expect, test } from "bun:test"
import { type CuratorConfig, defaultCuratorConfig, parseCuratorConfig } from "./curator-config"

// The contract the apps rely on when they save Settings ▸ Curator (card #97): a key that is left
// out keeps what is stored, so the apps must send every field, and "" is how they ask for the
// agent's own model or effort.
describe("curator settings save", () => {
  const stored: CuratorConfig = { enabled: true, hour: 3, minute: 30, agent: "codex", model: "gpt-6", reasoningLevel: "high" }

  test("a key left out keeps the stored value", () => {
    expect(parseCuratorConfig({}, stored)).toEqual(stored)
  })

  test("values equal to the defaults are saved when they are sent", () => {
    expect(parseCuratorConfig({ enabled: false, hour: 1, minute: 0, agent: "claude", model: "", reasoningLevel: "" }, stored))
      .toEqual(defaultCuratorConfig)
  })

  test("an empty model or effort goes back to the agent's own", () => {
    const cfg = parseCuratorConfig({ ...stored, model: "", reasoningLevel: "" }, stored)
    expect(cfg.model).toBeUndefined()
    expect(cfg.reasoningLevel).toBeUndefined()
  })
})
