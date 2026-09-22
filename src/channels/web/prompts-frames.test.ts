import { test, expect } from "bun:test"
import { handleWebInbound } from "./inbound-handler"

test("web inbound still delivers ordinary text", async () => {
  const delivered: { id: string; text: string }[] = []
  await handleWebInbound({
    channel: "web",
    chat_id: "web",
    message_id: "m1",
    user: "u",
    user_id: "u",
    ts: "t",
    text: "hi",
    target_session_id: "s1",
  }, {
    messageLog: { append: () => {} } as never,
    deliver: async (id, text) => { delivered.push({ id, text }) },
    hasSession: () => true,
    replyNoSuchSession: async () => {},
  })
  expect(delivered).toEqual([{ id: "s1", text: "hi" }])
})
