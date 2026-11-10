import { expect, test } from "bun:test"
import { handleWebInbound } from "./inbound-handler"

function run(from_card?: { id: string; title: string }) {
  const appended: any[] = []
  const delivered: Array<[string, string]> = []
  return handleWebInbound(
    { channel: "web", chat_id: "web", message_id: "m1", user: "phone", user_id: "phone", ts: "t", text: "Use B", target_session_id: "s1", ...(from_card ? { from_card } : {}) },
    {
      messageLog: { append: (_s: string, e: any) => appended.push(e) } as any,
      hasSession: () => true,
      replyNoSuchSession: async () => {},
      deliver: async (sid: string, text: string) => { delivered.push([sid, text]) },
    } as any,
  ).then(() => ({ appended, delivered }))
}

test("from a card: the log keeps his words and the card; the agent is told which card they came from", async () => {
  const { appended, delivered } = await run({ id: "c1", title: "Pick" })
  expect(appended[0]).toMatchObject({ direction: "inbound", text: "Use B", from_card: { id: "c1", title: "Pick" } })
  expect(delivered).toEqual([["s1", '(from the card "Pick")\nUse B']])
})

test("an ordinary message is unchanged", async () => {
  const { appended, delivered } = await run()
  expect(appended[0].from_card).toBeUndefined()
  expect(delivered).toEqual([["s1", "Use B"]])
})
