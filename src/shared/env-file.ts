import { parseEnv } from "node:util"

/**
 * The variables in `.env` text. Every broker before mux/platform-layer wrote `KEY=value` raw and read the value
 * verbatim, so a value is taken as it stands: a `#`, spaces or quotes inside stay part of it (util.parseEnv alone cut
 * "pa#ss" to "pa", which an update must never do to a file that worked). A value in matching quotes, which [envText]
 * writes when a value needs them, is unquoted by the runtime's rules (util.parseEnv). Lines may end in CRLF and start
 * with `export `; comment lines are skipped; of two lines with one key the first wins, as the broker always read them.
 */
export function readEnvText(text: string): Map<string, string> {
  const out = new Map<string, string>()
  for (const raw of text.split("\n")) {
    const m = /^(?:export\s+)?(\w+)=(.*)$/.exec(raw.endsWith("\r") ? raw.slice(0, -1) : raw)
    if (!m || out.has(m[1]!)) continue
    out.set(m[1]!, unquoted(m[2]!))
  }
  return out
}

/** [value] without the quotes [envText] put around it; any other value exactly as it is. */
function unquoted(value: string): string {
  if (!/^(["'`]).*\1$/s.test(value)) return value
  return (parseEnv(`V=${value}`) as Record<string, string>).V ?? value
}

/** `.env` text that readEnvText gives back exactly, and that older brokers read right too: a value goes bare. */
export function envText(entries: Iterable<[string, string]>): string {
  let out = ""
  for (const [key, value] of entries) out += `${key}=${quoted(key, value)}\n`
  return out
}

/**
 * Only a value [readEnvText] would unquote (wrapped in matching quotes) gets quotes of its own. Single quotes and
 * backticks keep a value as it is; double quotes would turn a `\n` (in a Windows path, say) into a line break.
 */
function quoted(key: string, value: string): string {
  if (/[\r\n]/.test(value)) throw new Error(`${key}: a value with a line break cannot be written to .env`)
  if (!/^(["'`]).*\1$/s.test(value)) return value
  if (!value.includes("'")) return `'${value}'`
  if (!value.includes("`")) return `\`${value}\``
  throw new Error(`${key}: a value wrapped in quotes that holds both ' and \` cannot be written to .env`)
}
