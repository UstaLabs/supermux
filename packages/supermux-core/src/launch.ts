import type { AuthProfile } from "./types.js"

/** Driver launch env: inherited, driver env, profile env, then the profile's unset keys removed. */
export function launchEnv(inherit: boolean, env: Record<string, string> | undefined, profile: AuthProfile | undefined): Record<string, string | undefined> {
  const merged: Record<string, string | undefined> = { ...(inherit ? process.env : {}), ...env, ...profile?.env }
  for (const key of profile?.unsetEnv ?? []) delete merged[key]
  return merged
}

/** Driver args followed by the profile's args. */
export function launchArgs(args: string[], profile: AuthProfile | undefined): string[] {
  return profile?.args?.length ? [...args, ...profile.args] : args
}
