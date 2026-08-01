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
