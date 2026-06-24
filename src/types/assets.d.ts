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

// Memory seed templates imported with `with { type: "text" }`. Bun returns
// the file content as a string (source mode: reads the file; compiled: bundled
// bytes). tsc has no built-in knowledge of the "text" import attribute.
declare module "*.tmpl" {
  const content: string
  export default content
}

// supermux mux-core hook scripts vendored via `with { type: "text" }` — the
// .cmd polyglot wrapper and the extension-less session-start hook (a wildcard
// can't match the latter, so it's declared by its specifier like pty-helper).
// See src/core/plugins/mux-core.ts (ensureMuxCoreSkills writes these to disk).
declare module "*.cmd" {
  const content: string
  export default content
}
declare module "*/hooks/session-start" {
  const content: string
  export default content
}

// PWA assets embedded by scripts/generate-static-manifest.ts (the generated
// src/channels/web/static-manifest.generated.ts imports every built static file
// `with { type: "file" }`). Under moduleResolution:bundler these wildcards make
// the imports resolve to a path string; without them tsc errors (.css/.png/.svg/
// .ico/.webmanifest are unresolvable → TS2307; .js resolves on disk but untyped
// → TS7016). `.html` is intentionally omitted — bun-types already declares it
// (as HTMLBundle), and the generator coerces that one binding to string.
declare module "*.css" { const path: string; export default path }
declare module "*.png" { const path: string; export default path }
declare module "*.svg" { const path: string; export default path }
declare module "*.ico" { const path: string; export default path }
declare module "*.webmanifest" { const path: string; export default path }
declare module "*.js" { const path: string; export default path }
