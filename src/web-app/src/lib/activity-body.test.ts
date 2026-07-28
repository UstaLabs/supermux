import { expect, test } from "bun:test"
import { resolveBashParts, resolveEditParts } from "./activity-body"

test("resolveBashParts merges start command + result output", () => {
  expect(resolveBashParts({
    body: { kind: "bash", command: "ls -la" },
    resultBody: { kind: "bash", output: "a\nb", exitCode: 0 },
    toolName: "Bash",
  })).toEqual({ command: "ls -la", output: "a\nb", exitCode: 0 })
})

test("resolveBashParts falls back to medium strings", () => {
  expect(resolveBashParts({
    input: "npm test",
    output: "ok",
    toolName: "Bash",
  })).toEqual({ command: "npm test", output: "ok", exitCode: undefined })
})

test("resolveEditParts prefers structured body", () => {
  const r = resolveEditParts({
    body: { kind: "edit", path: "a.ts", diff: "@@\n-a\n+b", oldText: "a", newText: "b" },
    toolName: "Edit",
  })
  expect(r).toMatchObject({ path: "a.ts", diff: "@@\n-a\n+b" })
})

test("resolveEditParts write body becomes add-style content", () => {
  const r = resolveEditParts({
    body: { kind: "write", path: "n.ts", content: "x" },
    toolName: "Write",
  })
  expect(r).toMatchObject({ path: "n.ts", mode: "add", content: "x" })
  expect(r!.diff).toContain("+x")
})
