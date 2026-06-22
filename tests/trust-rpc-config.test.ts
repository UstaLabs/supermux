import { test, expect } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeRpcWorkerMcpConfig, CLAUDE_RPC_SERVER, CLAUDE_CHANNEL_SERVER } from "../src/core/session-manager/trust"

test("writeRpcWorkerMcpConfig writes a valid JSON file with mux-rpc and mux-channel entries", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-rpc-config-"))
  const path = join(dir, "rpc.json")
  try {
    writeRpcWorkerMcpConfig(path)
    const config = JSON.parse(readFileSync(path, "utf8"))
    expect(config.mcpServers).toBeDefined()
    expect(config.mcpServers[CLAUDE_RPC_SERVER]).toBeDefined()
    expect(config.mcpServers[CLAUDE_CHANNEL_SERVER]).toBeDefined()
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("mux-rpc entry has MUX_RPC_ONLY=1 in env", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-rpc-config-"))
  const path = join(dir, "rpc.json")
  try {
    writeRpcWorkerMcpConfig(path)
    const config = JSON.parse(readFileSync(path, "utf8"))
    expect(config.mcpServers[CLAUDE_RPC_SERVER].env.MUX_RPC_ONLY).toBe("1")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("mux-channel entry has MUX_CHANNEL_ONLY=1 in env", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-rpc-config-"))
  const path = join(dir, "rpc.json")
  try {
    writeRpcWorkerMcpConfig(path)
    const config = JSON.parse(readFileSync(path, "utf8"))
    expect(config.mcpServers[CLAUDE_CHANNEL_SERVER].env.MUX_CHANNEL_ONLY).toBe("1")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("both mux-rpc and mux-channel entries have a command string and args array", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-rpc-config-"))
  const path = join(dir, "rpc.json")
  try {
    writeRpcWorkerMcpConfig(path)
    const config = JSON.parse(readFileSync(path, "utf8"))
    const rpc = config.mcpServers[CLAUDE_RPC_SERVER]
    const channel = config.mcpServers[CLAUDE_CHANNEL_SERVER]
    expect(typeof rpc.command).toBe("string")
    expect(rpc.command.length).toBeGreaterThan(0)
    expect(Array.isArray(rpc.args)).toBe(true)
    expect(typeof channel.command).toBe("string")
    expect(channel.command.length).toBeGreaterThan(0)
    expect(Array.isArray(channel.args)).toBe(true)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
