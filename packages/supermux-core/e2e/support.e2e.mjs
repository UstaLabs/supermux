import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { createServer } from "node:http"
import { tmpdir } from "node:os"
import { delimiter, join } from "node:path"
import { promisify } from "node:util"
import { openEvents } from "./support/sse.mjs"
import { startServer } from "./support/server.mjs"

const run = promisify(execFile)
// startServer() puts the stub agent CLIs on PATH, and they are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"

test("openEvents parses data lines and waits for a match", async () => {
  const server = createServer((req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" })
    res.write(": ping\n\n")
    res.write(`data: ${JSON.stringify({ n: 1 })}\n\n`)
    setTimeout(() => res.write(`data: ${JSON.stringify({ n: 2 })}\n\n`), 50)
  })
  await new Promise(r => server.listen(0, "127.0.0.1", r))
  const events = await openEvents(`http://127.0.0.1:${server.address().port}/`)
  try {
    assert.deepEqual(await events.next(m => m.n === 2), { n: 2 })
    assert.equal(events.seen.length, 2)
  } finally { events.close(); server.close() }
})

/** An HTTP server on a free port that answers with `respond`. */
async function serve(respond) {
  const server = createServer(respond)
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve))
  return { url: `http://127.0.0.1:${server.address().port}/`, close: () => server.close() }
}

test("openEvents rejects a response that is not a success", async () => {
  const server = await serve((req, res) => { res.writeHead(404); res.end() })
  try {
    await assert.rejects(openEvents(server.url), /404/)
  } finally { server.close() }
})

test("next() rejects instead of waiting out its timeout: the predicate throws, the stream ends, the data is not JSON", async () => {
  const ending = await serve((req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" })
    res.write(": ping\n\n") // Sends the headers now, so that openEvents() resolves before the message and the end arrive.
    setTimeout(() => res.end(`data: ${JSON.stringify({ n: 1 })}\n\n`), 50)
  })
  const garbled = await serve((req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" })
    res.write("data: {not json\n\n")
  })
  const one = await openEvents(ending.url)
  const two = await openEvents(garbled.url)
  try {
    // Both are asked for before anything arrives: the first meets the throw when the stream dispatches a message, the
    // second is still waiting when the stream ends.
    const throwing = assert.rejects(one.next(() => { throw new Error("bad predicate") }, 5_000), /bad predicate/)
    const waiting = assert.rejects(one.next(m => m.n === 2, 5_000), /event stream closed; saw \[\{"n":1\}\]/)
    await throwing
    // What came before the end can still be asked for; what never will come is refused at once.
    assert.deepEqual(await one.next(m => m.n === 1, 5_000), { n: 1 })
    await waiting
    await assert.rejects(one.next(m => m.n === 2, 5_000), /event stream closed/)
    await assert.rejects(two.next(() => false, 5_000), /event data is not JSON: \{not json/)
  } finally {
    one.close(); two.close(); ending.close(); garbled.close()
  }
})

test("close() refuses a next() that is still waiting", async () => {
  const server = await serve((req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" })
    res.write(": ping\n\n")
  })
  const events = await openEvents(server.url)
  try {
    const waiting = assert.rejects(events.next(() => false, 5_000), /event stream closed/)
    events.close()
    await waiting
    await assert.rejects(events.next(() => false, 5_000), /event stream closed/)
  } finally { server.close() }
})

test("a next() that has resolved leaves no timer behind to keep the process alive", async () => {
  const server = await serve((req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" })
    res.write(`data: ${JSON.stringify({ n: 1 })}\n\n`)
  })
  const sse = JSON.stringify(new URL("./support/sse.mjs", import.meta.url).href)
  const script = `
    import { openEvents } from ${sse}
    const events = await openEvents(process.argv[1])
    await events.next(m => m.n === 1, 120_000)
    events.close()
  `
  try {
    // With the two minutes' timer left running the process would still be alive when run() gives up on it, and fail.
    await run(process.execPath, ["--input-type=module", "-e", script, server.url], { timeout: 20_000 })
  } finally { server.close() }
})

/** Runs `body` with `source` written as a script into a directory of its own, which is removed after. */
async function withScript(source, body) {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  try {
    const script = join(dir, "server.mjs")
    writeFileSync(script, source)
    return await body({ dir, script })
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

/** Runs `body` with TMPDIR set to a new empty directory, which is where stubAgents() makes its own. */
async function withOwnTmpdir(body) {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const saved = process.env.TMPDIR
  process.env.TMPDIR = work
  try {
    return await body(work)
  } finally {
    if (saved === undefined) delete process.env.TMPDIR
    else process.env.TMPDIR = saved
    rmSync(work, { recursive: true, force: true })
  }
}

// Stands in for an example server: it takes --port, prints the same first line, answers over HTTP, and exits on SIGTERM
// (unless it is told to ignore the signal).
const PING_SERVER = `
import { spawnSync } from "node:child_process"
import { createServer } from "node:http"

const server = createServer((req, res) => {
  let body = ""
  req.on("data", chunk => { body += chunk })
  req.on("end", () => {
    const reply = (status, json) => {
      res.writeHead(status, { "content-type": "application/json" })
      res.end(JSON.stringify(json))
    }
    if (req.url === "/api/ping") reply(200, { argv: process.argv.slice(2), home: process.env.HOME, path: process.env.PATH, marker: process.env.E2E_MARKER })
    else if (req.url === "/api/claude") {
      spawnSync("claude", { stdio: "ignore", timeout: 10000 })
      reply(200, {})
    } else if (req.url === "/api/echo") reply(200, JSON.parse(body))
    else reply(404, { error: "no such route" })
  })
})
process.on("SIGTERM", () => { if (!process.env.E2E_IGNORE_SIGTERM) process.exit(0) })
server.listen(0, "127.0.0.1", () => console.log("listening on http://127.0.0.1:" + server.address().port))
`

test("startServer() runs a server with the stub agents on its PATH; stop() ends it and removes them", { skip }, async () => {
  await withScript(PING_SERVER, async ({ script }) => {
    const server = await startServer(script, ["--extra", "1"], { E2E_MARKER: "from-test" })
    try {
      const ping = await server.get("/api/ping")
      assert.deepEqual(ping.argv, ["--port", "0", "--extra", "1"])
      assert.equal(ping.marker, "from-test")
      assert.equal(ping.home, server.stubs.home)
      assert.equal(ping.path.split(delimiter)[0], server.stubs.bin)
      assert.deepEqual(await server.post("/api/echo", { a: 1 }), { a: 1 })
      await assert.rejects(server.get("/api/nope"), /^Error: \/api\/nope 404: .*no such route/)
      await assert.rejects(server.post("/api/nope"), /^Error: \/api\/nope 404: .*no such route/)

      // A stub that the server runs leaves its mark: this is how a test knows a stub gave the answer.
      assert.deepEqual(server.stubs.launched(), [])
      await server.get("/api/claude")
      assert.deepEqual(server.stubs.launched(), ["claude"])

      assert.deepEqual(await server.stop(), { code: 0, signal: null })
      assert.equal(server.child.exitCode, 0)
      assert.equal(existsSync(server.stubs.bin), false)
      assert.equal(existsSync(server.stubs.home), false)
    } finally {
      await server.stop() // Safe a second time.
    }
  })
})

test("stop() kills a server that has not obeyed its signal after graceMs", { skip }, async () => {
  await withScript(PING_SERVER, async ({ script }) => {
    const server = await startServer(script, [], { E2E_IGNORE_SIGTERM: "1" }, { graceMs: 500 })
    try {
      assert.deepEqual(await server.stop(), { code: null, signal: "SIGKILL" })
    } finally {
      await server.stop()
    }
  })
})

test("startServer() rejects with what the server printed when it exits at once, and leaves nothing behind", { skip }, async () => {
  const source = `console.log("starting"); console.error("boom"); process.exit(3)`
  await withScript(source, ({ script }) => withOwnTmpdir(async work => {
    await assert.rejects(startServer(script), /server exited with code 3\nstdout: starting\nstderr: boom/)
    assert.deepEqual(readdirSync(work), [])
  }))
})

test("startServer() kills a server that never says where it listens, and leaves nothing behind", { skip }, async () => {
  // It writes its pid where the last argument says, prints something that is not the URL line, and idles.
  const source = `
    import { writeFileSync } from "node:fs"
    writeFileSync(process.argv.at(-1), String(process.pid))
    console.log("nothing to say")
    setInterval(() => {}, 1000)
  `
  await withScript(source, ({ dir, script }) => withOwnTmpdir(async work => {
    const pidFile = join(dir, "pid")
    await assert.rejects(startServer(script, [pidFile], {}, { timeoutMs: 3_000 }), /printed no "listening on .*" line in 3000 ms\nstdout: nothing to say/)
    assert.throws(() => process.kill(Number(readFileSync(pidFile, "utf8")), 0), { code: "ESRCH" })
    assert.deepEqual(readdirSync(work), [])
  }))
})
