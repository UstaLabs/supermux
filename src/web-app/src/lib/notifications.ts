// Client-side helpers for the Web Push notifications the service worker (`sw.ts`) shows.
//
// IMPORTANT (iOS Safari PWAs): never use these to *suppress* a push — every `push` event
// must still show a real notification or WebKit revokes the subscription. Suppression is a
// server-side decision (the viewing-tracker). Closing an ALREADY-delivered notification when
// the user opens its chat, as below, is safe — it's a client action, not a silent push.

/**
 * Per-session notification tag. MUST stay in lockstep with `sw.ts`, which shows each push as
 * `tag: cmux:${sessionId}` so a chat's notifications collapse to one and can be cleared as a set.
 */
export function notificationTag(sessionId: string): string {
  return `cmux:${sessionId}`
}

/** Close every delivered notification for a chat — called when the user opens (and is looking at) it. */
export async function clearChatNotifications(
  reg: Pick<ServiceWorkerRegistration, "getNotifications">,
  sessionId: string,
): Promise<void> {
  if (!sessionId) return
  const notes = await reg.getNotifications({ tag: notificationTag(sessionId) })
  for (const n of notes) n.close()
}
