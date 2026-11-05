import { parseEnv } from "node:util"

/**
 * The variables in `.env` text, read by the runtime's rules (util.parseEnv, the same in Bun and Node): a line may end
 * in CRLF, quotes are taken off, and a `#` outside them starts a comment.
 */
export function readEnvText(text: string): Map<string, string> {
  return new Map(Object.entries(parseEnv(text) as Record<string, string>))
}

/** `.env` text that readEnvText gives back exactly: a value it would change is quoted. */
export function envText(entries: Iterable<[string, string]>): string {
  let out = ""
  for (const [key, value] of entries) out += `${key}=${quoted(key, value)}\n`
  return out
}

/** Single quotes and backticks keep a value as it is; double quotes would turn a `\n` (in a Windows path, say) into a line break. */
function quoted(key: string, value: string): string {
  if (value === value.trim() && !/[#"'`\r\n]/.test(value)) return value
  if (!/['\r\n]/.test(value)) return `'${value}'`
  if (!/[`\r\n]/.test(value)) return `\`${value}\``
  throw new Error(`${key}: a value with a line break, or with both ' and \`, cannot be written to .env`)
}
