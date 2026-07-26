const PORTABLE_ENVIRONMENT_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const SAFE_SHELL_WORD = /^[A-Za-z0-9_@%+=:,./-]+$/

function quotePosix(value: string): string {
  if (SAFE_SHELL_WORD.test(value)) return value
  return `'${value.replaceAll("'", `'"'"'`)}'`
}

export function renderPosixLoginShellCommand(argv: readonly string[], env: Readonly<Record<string, string>>): string {
  if (argv.length === 0) throw new Error("cannot render an empty command")
  const environment = Object.entries(env).map(([key, value]) => {
    if (!PORTABLE_ENVIRONMENT_NAME.test(key)) throw new Error(`invalid environment variable name: ${key}`)
    return quotePosix(`${key}=${value}`)
  })
  const inner = ["exec", "env", "--", ...environment, ...argv.map(quotePosix)].join(" ")
  return `bash -lc ${quotePosix(inner)}`
}
