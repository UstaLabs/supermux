import { test, expect } from "bun:test"
import { homedir } from "os"
import { join } from "path"
import { encodeProjectDir, claudeTranscriptPath } from "./transcript-path"

test("encodeProjectDir replaces / and . with -", () => {
  expect(encodeProjectDir("/home/user/projects/myapp")).toBe("-home-user-projects-myapp")
  expect(encodeProjectDir("/home/user/projects/myapp/.claude-worktrees/web-channel"))
    .toBe("-home-user-projects-myapp--claude-worktrees-web-channel")
})

test("claudeTranscriptPath builds the full jsonl path under ~/.claude/projects", () => {
  const p = claudeTranscriptPath("/home/user/projects/myapp", "abc-123")
  expect(p).toBe(join(homedir(), ".claude", "projects", "-home-user-projects-myapp", "abc-123.jsonl"))
})

test("claudeTranscriptPath handles dotted paths and trailing slashes", () => {
  const dotted = claudeTranscriptPath("/home/user/projects/myapp/.config", "s1")
  expect(dotted.endsWith("-home-user-projects-myapp--config/s1.jsonl")).toBe(true)
  expect(encodeProjectDir("/home/user/projects/myapp/")).toBe("-home-user-projects-myapp")
})
