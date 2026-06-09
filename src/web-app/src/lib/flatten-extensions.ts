import type { Extension } from "@codemirror/state"

/** Flatten nested Extension arrays (e.g. client.plugin() returns an array). */
export function flattenExtensions(ext: Extension | Extension[] | undefined): Extension[] {
  const out: Extension[] = []
  const stack: (Extension | Extension[])[] = ext ? (Array.isArray(ext) ? [...ext] : [ext]) : []
  while (stack.length) {
    const cur = stack.pop()!
    if (Array.isArray(cur)) {
      for (let i = cur.length - 1; i >= 0; i--) stack.push(cur[i] as Extension)
    } else {
      out.push(cur)
    }
  }
  return out
}
