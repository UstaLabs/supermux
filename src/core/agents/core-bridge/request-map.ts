import type { NormalizedBody } from "../../../../packages/supermux-core/src/events/normalized.js"
import type { PendingRequest, RequestAnswer } from "../../../../packages/supermux-core/src/types.js"
import type { BrokerRequest, RequestAnswerInput } from "../types"

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined
}

function permissionSummary(body: Extract<NormalizedBody, { kind: "permission-request" }>): string {
  const tool = body.toolCall.tool || body.toolCall.title
  const detail = body.detail
  const input = rec(body.toolCall.input)
  const command = detail?.command ?? str(input?.command) ?? str(input?.cmd)
  const path = detail?.blockedPath ?? str(input?.path) ?? str(input?.file_path) ?? str(input?.filePath)
  const bits = [tool]
  if (command) bits.push(command)
  else if (path) bits.push(path)
  return bits.filter(Boolean).join(" ")
}

export function mapPermissionRequest(body: Extract<NormalizedBody, { kind: "permission-request" }>): BrokerRequest {
  return {
    requestId: body.requestId,
    kind: "permission",
    title: body.toolCall.title || body.toolCall.tool || "Permission",
    body: permissionSummary(body),
    options: body.options.map((o) => ({ id: o.id, label: o.label, kind: o.kind })),
    allowFreeText: false,
    blocking: true,
  }
}

export function mapUserQuestion(body: Extract<NormalizedBody, { kind: "user-question" }>): BrokerRequest {
  const first = body.questions[0]
  const title = first?.header || first?.prompt || "Question"
  return {
    requestId: body.requestId,
    kind: "question",
    title,
    body: JSON.stringify(body.questions),
    options: (first?.options ?? []).map((o) => ({ id: o.id, label: o.label })),
    allowFreeText: body.questions.some((q) => q.allowFreeText),
    blocking: body.blocking,
  }
}

export function mapPendingRequest(pending: PendingRequest): BrokerRequest {
  if (pending.kind === "permission") return mapPermissionRequest(pending.body)
  return mapUserQuestion(pending.body)
}

export function rejectOptionId(request: BrokerRequest): string | undefined {
  const reject = request.options.find((o) => o.kind === "reject_once")
    ?? request.options.find((o) => o.kind === "reject_always")
    ?? request.options.find((o) => /reject|deny|decline/i.test(o.label) || /reject|deny/i.test(o.id))
  return reject?.id
}

export function answerFromTelegramText(request: BrokerRequest, text: string): RequestAnswerInput | undefined {
  const match = request.options.find((o) => o.label === text)
  if (match) {
    if (request.kind === "permission") return { optionId: match.id }
    const qid = firstQuestionId(request)
    if (!qid) return { optionId: match.id }
    return { answers: { [qid]: match.id } }
  }
  if (request.kind === "permission") {
    const reject = rejectOptionId(request)
    if (!reject) return undefined
    return { optionId: reject, message: text }
  }
  if (request.allowFreeText) {
    const qid = firstQuestionId(request)
    if (!qid) return undefined
    return { answers: { [qid]: text } }
  }
  return undefined
}

function firstQuestionId(request: BrokerRequest): string | undefined {
  try {
    const questions = JSON.parse(request.body) as { id?: string }[]
    if (Array.isArray(questions) && typeof questions[0]?.id === "string") return questions[0].id
  } catch {
    return undefined
  }
}

export function toCoreAnswer(answer: RequestAnswerInput): RequestAnswer {
  if ("decline" in answer && answer.decline) return { decline: true }
  if ("answers" in answer && answer.answers) return { answers: answer.answers }
  if ("optionId" in answer) return { optionId: answer.optionId, message: answer.message }
  throw new Error("invalid request answer")
}
