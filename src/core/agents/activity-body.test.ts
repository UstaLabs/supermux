import { expect, test } from "bun:test"
import {
  cleanToolDescription,
  clipToolBody,
  ensureEditDiff,
  pickDescriptionField,
  synthesizeUnifiedDiff,
  BODY_MAX,
} from "./activity-body"

test("synthesizeUnifiedDiff marks old as - and new as +", () => {
  const d = synthesizeUnifiedDiff("a.ts", "old\nline", "new\nline")
  expect(d).toContain("--- a/a.ts")
  expect(d).toContain("+++ b/a.ts")
  expect(d).toContain("-old")
  expect(d).toContain("+new")
})

test("ensureEditDiff prefers provided diff", () => {
  expect(ensureEditDiff({ path: "x", diff: "@@\n+x", oldText: "a", newText: "b" })).toBe("@@\n+x")
})

test("ensureEditDiff synthesizes when only old/new", () => {
  const d = ensureEditDiff({ path: "f.ts", oldText: "a", newText: "b" })
  expect(d).toContain("-a")
  expect(d).toContain("+b")
})

test("clipToolBody truncates large bash output", () => {
  const big = "x".repeat(BODY_MAX + 500)
  const { body, truncated } = clipToolBody({ kind: "bash", command: "ls", output: big })
  expect(truncated).toBe(true)
  expect(body?.kind).toBe("bash")
  if (body?.kind === "bash") {
    expect(body.output!.length).toBe(BODY_MAX)
    expect(body.command).toBe("ls")
  }
})

test("cleanToolDescription drops command echoes and bare tool stems", () => {
  expect(cleanToolDescription("Recompile Android Kotlin", ["gradlew build"])).toBe("Recompile Android Kotlin")
  expect(cleanToolDescription("npm test", ["npm test"])).toBeUndefined()
  expect(cleanToolDescription("write")).toBeUndefined()
  expect(cleanToolDescription("  ")).toBeUndefined()
})

test("pickDescriptionField prefers description over explanation", () => {
  expect(pickDescriptionField({ explanation: "e", description: "d" })).toBe("d")
  expect(pickDescriptionField({ reason: "why" })).toBe("why")
})
