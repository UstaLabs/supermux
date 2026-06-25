/** Pure helper — no side-effects, easy to unit-test. */
export function requireAtLeastOneChannel(hasTelegram: boolean, hasWeb: boolean, hasWhatsapp: boolean): { error?: string } {
  if (!hasTelegram && !hasWeb && !hasWhatsapp) {
    return {
      error: "supermux needs at least one channel: set MUX_TELEGRAM_BOT_TOKEN (Telegram), MUX_WEB_PORT + MUX_WEB_PUBLIC_URL (web PWA), or MUX_WHATSAPP_GOWA_URL (WhatsApp via GOWA).",
    }
  }
  return {}
}
