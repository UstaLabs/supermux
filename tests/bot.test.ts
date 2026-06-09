import { test, expect } from "bun:test"
import { InputFile } from "grammy"
import { createBotApi, BotApi } from "../src/channels/telegram/bot-api"
import { setMenu } from "../src/channels/telegram/menu"

// Fake low-level grammy bot.api substitute
function makeFakeApi() {
  const calls: any[] = []
  return {
    calls,
    sendMessage: async (...a: any[]) => { calls.push(["sendMessage", ...a]); return { message_id: 100 + calls.length } },
    editMessageText: async (...a: any[]) => { calls.push(["editMessageText", ...a]); return true },
    setMessageReaction: async (...a: any[]) => { calls.push(["setMessageReaction", ...a]); return true },
    sendPhoto: async (...a: any[]) => { calls.push(["sendPhoto", ...a]); return { message_id: 200 } },
    sendDocument: async (...a: any[]) => { calls.push(["sendDocument", ...a]); return { message_id: 201 } },
    sendVoice: async (...a: any[]) => { calls.push(["sendVoice", ...a]); return { message_id: 202 } },
    sendVideo: async (...a: any[]) => { calls.push(["sendVideo", ...a]); return { message_id: 203 } },
    setMyCommands: async (...a: any[]) => { calls.push(["setMyCommands", ...a]); return true },
  }
}

test("reply sendMessage with disable_notification=false by default", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({ chat_id: "c1", text: "hi", disable_notification: false })
  expect(api.calls[0][0]).toBe("sendMessage")
  expect(api.calls[0][1]).toBe("c1")
  expect(api.calls[0][2]).toBe("hi")
  expect(api.calls[0][3].disable_notification).toBe(false)
})

test("reply with files routes to sendPhoto for images", async () => {
  const api = makeFakeApi()
  const bot = createBotApi(api as any)
  await bot.sendReply({ chat_id: "c1", text: "see this", files: ["/tmp/a.png"], disable_notification: true })
  expect(api.calls[0][0]).toBe("sendPhoto")
})

test("editMessage uses editMessageText", async () => {
  const api = makeFakeApi()
  const bot = createBotApi(api as any)
  await bot.editMessage({ chat_id: "c1", message_id: "5", text: "updated" })
  expect(api.calls[0][0]).toBe("editMessageText")
})

test("react uses setMessageReaction", async () => {
  const api = makeFakeApi()
  const bot = createBotApi(api as any)
  await bot.react({ chat_id: "c1", message_id: "5", emoji: "👀" })
  expect(api.calls[0][0]).toBe("setMessageReaction")
})

test("setMenu uses setMyCommands with all_private_chats scope", async () => {
  const api = makeFakeApi()
  await setMenu(api as any, [{ command: "sessions", description: "List" }, { command: "switch_to_x", description: "→ x" }])
  expect(api.calls[0][0]).toBe("setMyCommands")
  const args = api.calls[0][1]
  expect(args).toEqual([{ command: "sessions", description: "List" }, { command: "switch_to_x", description: "→ x" }])
})

test("reply with .ogg file routes to sendVoice wrapped in InputFile", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({
    chat_id: "c1",
    text: "voice msg",
    files: ["/tmp/some-voice.ogg"],
    disable_notification: false,
  })
  const call = api.calls.find(c => c[0] === "sendVoice")
  expect(call).toBeDefined()
  expect(call[1]).toBe("c1")
  // The KEY assertion: arg[2] is an InputFile, not a raw string
  expect(call[2]).toBeInstanceOf(InputFile)
})

test("reply with .png file routes to sendPhoto wrapped in InputFile", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({
    chat_id: "c1",
    text: "img",
    files: ["/tmp/x.png"],
    disable_notification: false,
  })
  const call = api.calls.find(c => c[0] === "sendPhoto")
  expect(call).toBeDefined()
  expect(call[2]).toBeInstanceOf(InputFile)
})

test("reply with .mp4 file routes to sendVideo wrapped in InputFile", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({
    chat_id: "c1",
    text: "clip",
    files: ["/tmp/clip.mp4"],
    disable_notification: false,
  })
  const call = api.calls.find(c => c[0] === "sendVideo")
  expect(call).toBeDefined()
  expect(call[1]).toBe("c1")
  expect(call[2]).toBeInstanceOf(InputFile)
  expect(call[3].caption).toBe("clip")
})

test("reply with .mov/.webm/.mkv files also route to sendVideo", async () => {
  for (const f of ["/tmp/a.mov", "/tmp/b.webm", "/tmp/c.mkv"]) {
    const api = makeFakeApi()
    const bot: BotApi = createBotApi(api as any)
    await bot.sendReply({ chat_id: "c1", text: "v", files: [f], disable_notification: false })
    expect(api.calls.find(c => c[0] === "sendVideo")).toBeDefined()
  }
})

test("reply with .pdf file routes to sendDocument wrapped in InputFile", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({
    chat_id: "c1",
    text: "doc",
    files: ["/tmp/x.pdf"],
    disable_notification: false,
  })
  const call = api.calls.find(c => c[0] === "sendDocument")
  expect(call).toBeDefined()
  expect(call[2]).toBeInstanceOf(InputFile)
})

test("http(s):// URLs in files are passed through unwrapped (not InputFile)", async () => {
  const api = makeFakeApi()
  const bot: BotApi = createBotApi(api as any)
  await bot.sendReply({
    chat_id: "c1",
    text: "url",
    files: ["https://example.com/img.png"],
    disable_notification: false,
  })
  const call = api.calls.find(c => c[0] === "sendPhoto")
  expect(call).toBeDefined()
  expect(typeof call[2]).toBe("string")
  expect(call[2]).toBe("https://example.com/img.png")
})
