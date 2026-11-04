import { expect, test } from "bun:test"
import { delimiter } from "node:path"
import { bunGlobalBinDir, lspPathEnv, muxLspBinDir } from "./paths"

// The PATH for LSP installs was joined with ":", which on Windows glued `…\lsp\bin:…\.bun\bin:<first entry>` into
// one broken entry: installs could not find bun and lost the first PATH directory.
test("the LSP PATH puts mux's and bun's bin dirs first, joined with the host's delimiter", () => {
  const entries = lspPathEnv().split(delimiter)
  expect(entries[0]).toBe(muxLspBinDir())
  expect(entries[1]).toBe(bunGlobalBinDir())
})
