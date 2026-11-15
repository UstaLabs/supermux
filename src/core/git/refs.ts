// src/core/git/refs.ts
// Git's ref names ("origin/feature/x", "refs/heads/main") are git's own syntax, with "/" on every OS: never
// filesystem paths. Everything that takes one apart goes through here.

/** A remote-tracking branch's name on its remote: "origin/feature/x" → "feature/x" (a remote's name has no "/"). */
export function stripRemote(ref: string): string {
  return ref.replace(/^[^/]+\//, "")
}

/** A local branch's name from its full ref: "refs/heads/feature/x" → "feature/x"; anything else as given. */
export function branchOfRef(ref: string): string {
  return ref.replace(/^refs\/heads\//, "")
}
