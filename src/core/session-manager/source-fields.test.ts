// #152 B5: what a client shows about where a session's work comes from (the chat crumb, the composer footer, #72's
// pane header): the pull / merge request it works on, and the remote its pushes go to.
import { expect, test } from "bun:test"
import { sessionSourceFields } from "./source-fields"

test("a session on a PR carries its link and its push remote; one made before #152 carries nothing new", () => {
  expect(sessionSourceFields({ source_kind: "pr", pr_forge: "github", pr_number: 123, pr_url: "https://github.com/a/b/pull/123", push_remote: "pr-ozan" }))
    .toEqual({ source: "pr", pr: { forge: "github", number: 123, url: "https://github.com/a/b/pull/123" }, push_remote: "pr-ozan" })
  expect(sessionSourceFields({ source_kind: "new" })).toEqual({ source: "new" })
  expect(sessionSourceFields({})).toEqual({})
})
