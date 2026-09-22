import { test, expect } from "bun:test"
import { createBotApi } from "./bot-api"

test("reply keyboard is option labels", async () => {
  const calls: unknown[] = []
  const api = createBotApi({
    sendMessage: async (_chat: string, text: string, opts: unknown) => {
      calls.push({ text, opts })
      return { message_id: 1 }
    },
  })
  await api.sendReply({
    chat_id: "1",
    text: "s: Bash\nexecute ls",
    keyboard: ["Allow once", "Reject"],
    disable_notification: false,
  })
  expect(calls[0]).toMatchObject({
    text: "s: Bash\nexecute ls",
    opts: {
      reply_markup: {
        keyboard: [[{ text: "Allow once" }], [{ text: "Reject" }]],
        one_time_keyboard: true,
        resize_keyboard: true,
      },
    },
  })
})
