import { expect, test } from "bun:test"
import { isPlainShellWord, shellWord } from "./shell-word"

test("a plain word is typed as it is; anything else is single-quoted", () => {
  for (const word of ["/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl", "/opt/a+b=c@d%e/x.y", "/home/şirket/.mux/x"]) {
    expect(isPlainShellWord(word)).toBe(true)
    expect(shellWord(word)).toBe(word)
  }
  expect(shellWord("/Users/John Smith/.mux/x.jsonl")).toBe("'/Users/John Smith/.mux/x.jsonl'")
  expect(shellWord("/a/it's")).toBe(`'/a/it'\\''s'`)
  expect(shellWord("")).toBe("''")
  for (const word of ["", "a b", "/a,b", "/a$b", "/a;b", "/a(b)", "/a*b", "/a?b", "/a\\b", "/a'b", '/a"b', "/a`b", "/a\nb", "/a:b", "/a~b", "/a!b", "/a&b", "/a|b", "/a<b", "/a#b", "/a{b}", "/a[b]"]) {
    expect(isPlainShellWord(word)).toBe(false)
  }
})
