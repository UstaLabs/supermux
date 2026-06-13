// src/core/forge/registry.ts
import type { ForgeAdapter, ForgeKind } from "./types"
import { githubAdapter } from "./github"
import { gitlabAdapter } from "./gitlab"

export const ADAPTERS: Record<ForgeKind, ForgeAdapter> = {
  github: githubAdapter,
  gitlab: gitlabAdapter,
}

export function adapterFor(kind: ForgeKind): ForgeAdapter {
  const a = ADAPTERS[kind]
  if (!a) throw new Error(`unknown forge kind: ${kind}`)
  return a
}
