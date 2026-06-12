// Ambient declarations for runtime assets imported with `with { type: "file" }`.
// Bun resolves such an import to a path string (a $bunfs/... path in compiled
// builds, the real file path in source mode). tsc has no built-in knowledge of
// the "file" import attribute, so it needs these module shapes. See
// src/core/runtime-assets.ts for the consumers.
declare module "*.md" {
  const path: string
  export default path
}

// The pty-helper is a committed native ELF imported by its extension-less
// specifier; a wildcard can't match it, so it's declared explicitly.
declare module "*/terminal/pty-helper" {
  const path: string
  export default path
}
