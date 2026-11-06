/**
 * The folder Claude Code keeps a working directory's transcripts in, under `<config>/projects`: the path, without a
 * trailing separator, with every character but A-Z, a-z and 0-9 made "-" ("/home/u/.mux/workspace/Supermux PM" →
 * "-home-u--mux-workspace-Supermux-PM", "C:\Users\me\app" → "C--Users-me-app"; checked against Claude's own folders).
 */
export function claudeProjectDirName(cwd: string): string {
  return cwd.replace(/[\\/]+$/, "").replace(/[^A-Za-z0-9]/g, "-")
}
