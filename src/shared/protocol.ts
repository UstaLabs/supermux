import { z } from "zod"

// shim → broker

export const RegisterMsg = z.object({
  kind: z.literal("register"),
  workdir: z.string(),
  pid: z.number().int(),
  display_name: z.string().optional(),
})

export const ReplyArgs = z.object({
  chat_id: z.string(),
  text: z.string(),
  reply_to: z.string().optional(),
  files: z.array(z.string()).optional(),
  keyboard: z.array(z.string()).optional(),
  format: z.enum(["text", "markdownv2"]).optional(),
})
export const ReactArgs = z.object({
  chat_id: z.string(),
  message_id: z.string(),
  emoji: z.string(),
})
export const EditMsgArgs = z.object({
  chat_id: z.string(),
  message_id: z.string(),
  text: z.string(),
  format: z.enum(["text", "markdownv2"]).optional(),
})
export const DownloadArgs = z.object({
  file_id: z.string(),
})

export const OutboundMsg = z.object({
  kind: z.literal("outbound"),
  call_id: z.string(),
  op: z.discriminatedUnion("name", [
    z.object({ name: z.literal("reply"), args: ReplyArgs }),
    z.object({ name: z.literal("react"), args: ReactArgs }),
    z.object({ name: z.literal("edit_message"), args: EditMsgArgs }),
    z.object({ name: z.literal("download_attachment"), args: DownloadArgs }),
  ]),
})

export const OrchestrationMsg = z.object({
  kind: z.literal("orchestration"),
  call_id: z.string(),
  op: z.discriminatedUnion("name", [
    z.object({ name: z.literal("spawn_session"),  args: z.object({ workdir: z.string(), name: z.string().optional() }) }),
    z.object({ name: z.literal("kill_session"),   args: z.object({ id: z.string() }) }),
    z.object({ name: z.literal("rename_session"), args: z.object({ id: z.string(), new_name: z.string() }) }),
    z.object({ name: z.literal("mute_session"),   args: z.object({ id: z.string(), muted: z.boolean() }) }),
    z.object({ name: z.literal("set_active"),     args: z.object({ chat_id: z.string(), id: z.string() }) }),
    z.object({ name: z.literal("get_active"),     args: z.object({ chat_id: z.string() }) }),
  ]),
})

export const PingMsg = z.object({ kind: z.literal("ping") })

export const ShimToBroker = z.discriminatedUnion("kind", [
  RegisterMsg, OutboundMsg, OrchestrationMsg, PingMsg,
])

// broker → shim

export const RegisteredMsg = z.object({
  kind: z.literal("registered"),
  display_name: z.string(),
  session_id: z.string(),
})

export const InboundMsg = z.object({
  kind: z.literal("inbound"),
  content: z.string(),
  meta: z.record(z.string(), z.string()),
})

export const ResultMsg = z.object({
  kind: z.literal("result"),
  call_id: z.string(),
  ok: z.boolean(),
  value: z.unknown().optional(),
  error: z.string().optional(),
})

export const PongMsg = z.object({ kind: z.literal("pong") })

export const BrokerToShim = z.discriminatedUnion("kind", [
  RegisteredMsg, InboundMsg, ResultMsg, PongMsg,
])

export type ShimToBroker = z.infer<typeof ShimToBroker>
export type BrokerToShim = z.infer<typeof BrokerToShim>
export type OutboundOp = z.infer<typeof OutboundMsg>["op"]
export type OrchestrationOp = z.infer<typeof OrchestrationMsg>["op"]
