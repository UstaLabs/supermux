/**
 * A word a POSIX shell reads literally when it is typed unquoted: letters, digits and `_ . / @ % + = -`
 * only. No whitespace, quotes, `$`, backslashes, parentheses, globs or commas. The shell read rules of
 * the session inbox (Claude's exact `Bash(...)` rules, which match the typed command text) apply to
 * such paths only, and the preamble shows the path in exactly this form.
 */
export function isPlainShellWord(word: string): boolean {
  return /^[\p{L}\p{N}_.\/@%+=-]+$/u.test(word)
}

/** `word` as a POSIX shell must be given it: as is when plain, else in single quotes. */
export function shellWord(word: string): string {
  return isPlainShellWord(word) ? word : `'${word.replaceAll("'", `'\\''`)}'`
}
