import { expect, test } from "bun:test"
import { delimiter } from "node:path"
import { join } from "node:path"
import { bunGlobalBinDir, lspPathEnv, muxLspBinDir, muxLspHome } from "./paths"

// The PATH for LSP installs was joined with ":", which on Windows glued `…\lsp\bin:…\.bun\bin:<first entry>` into
// one broken entry: installs could not find bun and lost the first PATH directory.
test("the LSP PATH puts mux's and bun's bin dirs first, joined with the host's delimiter", () => {
  const entries = lspPathEnv().split(delimiter)
  expect(entries[0]).toBe(muxLspBinDir())
  expect(entries[1]).toBe(bunGlobalBinDir())
})

// The language servers' own tools live in the mux home, wherever MUX_HOME puts it (they went to $HOME/.mux/lsp).
test("muxLspHome is MUX_LSP_DIR, else the mux home's lsp folder", () => {
  const prev = { dir: process.env.MUX_LSP_DIR, home: process.env.MUX_HOME }
  try {
    delete process.env.MUX_LSP_DIR
    process.env.MUX_HOME = "/tmp/x-mux-home"
    expect(muxLspHome()).toBe(join("/tmp/x-mux-home", "lsp"))
    process.env.MUX_LSP_DIR = "/tmp/x-lsp"
    expect(muxLspHome()).toBe("/tmp/x-lsp")
  } finally {
    for (const [key, value] of [["MUX_LSP_DIR", prev.dir], ["MUX_HOME", prev.home]] as const) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})
