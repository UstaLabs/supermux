import { chromium, type Browser, type LaunchOptions } from "playwright"

export type UiFixture = {
  baseUrl: string
  token: string
  deviceName: string
  sessionId: string
  sessionName: string
}

export function uiFixture(): UiFixture {
  const baseUrl = process.env.MUX_TEST_BASE_URL
  const token = process.env.MUX_TEST_PAIR_TOKEN
  const deviceName = process.env.MUX_TEST_DEVICE_NAME
  const sessionId = process.env.MUX_TEST_SESSION_ID
  const sessionName = process.env.MUX_TEST_SESSION_NAME
  if (!baseUrl || !token || !deviceName || !sessionId || !sessionName) {
    throw new Error("run UI journeys through scripts/test-broker.sh")
  }
  const url = new URL(baseUrl)
  if (url.hostname !== "127.0.0.1" || url.port === "9898") {
    throw new Error(`refusing non-hermetic UI target: ${baseUrl}`)
  }
  return { baseUrl, token, deviceName, sessionId, sessionName }
}

export function browserLaunchOptions(extra: LaunchOptions = {}): LaunchOptions {
  const executablePath = process.env.MUX_TEST_BROWSER_BIN || undefined
  const { args = [], ...rest } = extra
  return {
    ...rest,
    ...(executablePath ? { executablePath } : {}),
    headless: true,
    args: ["--no-sandbox", "--disable-dev-shm-usage", ...args],
  }
}

export function launchBrowser(extra: LaunchOptions = {}): Promise<Browser> {
  return chromium.launch(browserLaunchOptions(extra))
}

/** Broadcast a server frame to every web client (MUX_TEST_BROKER inject seam). */
export async function injectServerFrame(page: import("playwright").Page, frame: Record<string, unknown>): Promise<void> {
  const res = await page.evaluate(async (body) => {
    const response = await fetch("/debug/inject-frame", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      credentials: "include",
    })
    return { ok: response.ok, status: response.status, text: await response.text() }
  }, frame)
  if (!res.ok) throw new Error(`inject-frame failed ${res.status}: ${res.text}`)
}

export async function lastClientFrames(page: import("playwright").Page): Promise<unknown[]> {
  return await page.evaluate(async () => {
    const response = await fetch("/debug/last-client-frames", { credentials: "include" })
    if (!response.ok) throw new Error(`last-client-frames ${response.status}`)
    return await response.json() as unknown[]
  })
}
