// #156: `supermux … --json | python3` got the first 64 KiB only: the CLI exited before a pipe took the rest.
import { expect, test } from "bun:test"
import { join } from "node:path"
import { host } from "@supermux/platform"

// Through a shell pipe (sh), so not on Windows; writeAll itself is plain fs.writeSync there too.
const posix = test.skipIf(host.os() === "win32")

const script = (mode: string) => `
import { writeAll } from ${JSON.stringify(join(import.meta.dir, "write-all"))}
const line = "x".repeat(299) + "\\n"
for (let i = 0; i < 1000; i++) ${mode === "writeAll" ? "writeAll(1, line)" : "process.stdout.write(line)"}
process.exit(0)
`

/** Bytes that reach a shell pipe whose reader starts late (the pipe fills at 64 KiB before the writer exits). */
async function bytesThroughSlowPipe(mode: string): Promise<number> {
  const p = Bun.spawn(["sh", "-c", `"$0" -e "$1" | (sleep 0.5; wc -c)`, process.execPath, script(mode)], { stdout: "pipe", stderr: "inherit" })
  const out = await new Response(p.stdout).text()
  await p.exited
  return Number(out.trim())
}

posix("everything written with writeAll reaches a slow pipe, though the process exits right after", async () => {
  expect(await bytesThroughSlowPipe("writeAll")).toBe(300_000)
})

posix("a reader that goes away (EPIPE) ends the writing quietly", async () => {
  const p = Bun.spawn(["sh", "-c", `"$0" -e "$1" | head -c 10 >/dev/null`, process.execPath, script("writeAll")], { stdout: "pipe", stderr: "pipe" })
  await p.exited
  expect(await new Response(p.stderr).text()).toBe("")
})
