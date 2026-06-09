/** Pure helper — no side-effects, easy to unit-test. */
export function requireAtLeastOneChannel(hasTelegram: boolean, hasWeb: boolean): { error?: string } {
  if (!hasTelegram && !hasWeb) {
    return {
      error: "supermux needs at least one channel: set MUX_TELEGRAM_BOT_TOKEN (Telegram), or MUX_WEB_PORT + MUX_WEB_PUBLIC_URL (web PWA).",
    }
  }
  return {}
}
