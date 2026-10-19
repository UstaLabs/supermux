// Runs an example server the way CI does: under Node, with the fixture agent CLIs of stub-agents.mjs on its PATH and an
// empty HOME of its own, so a test can drive the server's HTTP API without ever reaching a real agent.
import { spawn } from "node:child_process"
import { createInterface } from "node:readline"
import { stubAgents } from "./stub-agents.mjs"

// The one line a server prints to say where it listens.
const LISTENING = /^listening on (http:\/\/127\.0\.0\.1:\d+)$/

/**
 * Sends `signal` to `child` and resolves with how it ended, `{ code, signal }`. A child still running `graceMs` later is
 * killed outright, so a server stuck in its shutdown cannot hang a test run. On a child that has ended already it just
 * reports how it ended.
 */
async function terminate(child, signal, graceMs) {
  if (child.exitCode !== null || child.signalCode !== null) return { code: child.exitCode, signal: child.signalCode }
  const ended = new Promise(resolve => child.once("exit", (code, by) => resolve({ code, signal: by })))
  child.kill(signal)
  const timer = setTimeout(() => child.kill("SIGKILL"), graceMs)
  try {
    return await ended
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Starts `node <script> --port 0 ...args` with stub agent CLIs on PATH; resolves once it prints its URL. `extraEnv` is added
 * to the server's environment (the stubs' own environment is stubAgents()'s business, not this one's).
 *
 * If the server exits, or prints no URL within `timeoutMs`, this rejects with what it printed, after the server is stopped
 * and the stubs are removed. `graceMs` is how long `stop()` waits for the server to obey its signal before it kills it.
 */
export async function startServer(script, args = [], extraEnv = {}, { timeoutMs = 20_000, graceMs = 10_000 } = {}) {
  const stubs = stubAgents()
  let child
  let stderr = ""
  let url
  try {
    child = spawn(process.execPath, [script, "--port", "0", ...args], { env: { ...stubs.env, ...extraEnv }, stdio: ["ignore", "pipe", "pipe"] })
    child.stderr.setEncoding("utf8")
    child.stderr.on("data", chunk => { stderr += chunk })
    url = await new Promise((resolve, reject) => {
      const printed = []
      const failure = what => new Error(`${what}\nstdout: ${printed.join("\n")}\nstderr: ${stderr}`)
      const settle = (done, value) => {
        clearTimeout(timer)
        done(value)
      }
      const timer = setTimeout(() => settle(reject, failure(`server printed no "listening on http://127.0.0.1:<port>" line in ${timeoutMs} ms`)), timeoutMs)
      // This reader stays on after the URL: a server that logs a lot must not fill its pipe and stall.
      createInterface({ input: child.stdout }).on("line", line => {
        const match = LISTENING.exec(line)
        if (match) settle(resolve, match[1])
        else printed.push(line)
      })
      child.once("error", error => settle(reject, error))
      // "close", not "exit": by then everything the server wrote has been read, so the error can say what it said.
      child.once("close", (code, signal) => settle(reject, failure(`server exited with ${code === null ? `signal ${signal}` : `code ${code}`}`)))
    })
  } catch (error) {
    // A server that never said where it listens may still be running: it goes before the directory it runs from does.
    try {
      if (child) await terminate(child, "SIGKILL", graceMs)
    } finally {
      stubs.cleanup()
    }
    throw error
  }
  return {
    url,
    child,
    /** The stub agent CLIs the server runs with: `stubs.launched()` names those that have run, so a test knows a stub answered. */
    stubs,
    stderr: () => stderr,
    async post(path, body) {
      const res = await fetch(url + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body ?? {}) })
      const text = await res.text()
      if (!res.ok) throw new Error(`${path} ${res.status}: ${text}`)
      try {
        return JSON.parse(text)
      } catch {
        return {}
      }
    },
    async get(path) {
      const res = await fetch(url + path)
      const text = await res.text()
      if (!res.ok) throw new Error(`${path} ${res.status}: ${text}`)
      return res.headers.get("content-type")?.includes("json") ? JSON.parse(text) : text
    },
    /** Ends the server with `signal`, removes the stubs, and resolves with how it ended, `{ code, signal }`. Safe to call twice. */
    async stop(signal = "SIGTERM") {
      try {
        return await terminate(child, signal, graceMs)
      } finally {
        stubs.cleanup()
      }
    },
  }
}
