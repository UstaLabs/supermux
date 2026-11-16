import { expect, test } from "bun:test"
import { isApiPath, isDocumentNavigation } from "./index"

// #117: a broker that serves the web app answered the native apps' GET /applets with the web app's index.html (the
// web app has an /applets page), so no device ever showed the Applets block. /applets is API for fetches; a browser
// navigation to it still gets the page.
test("/applets and its routes are API paths, not the web app's page", () => {
  expect(isApiPath("/applets")).toBe(true)
  expect(isApiPath("/applets/today/icon")).toBe(true)
  expect(isApiPath("/applets/today/jobs/sync/run")).toBe(true)
  expect(isApiPath("/applets-other")).toBe(false)
  const nav = new Request("http://broker/applets", { headers: { "sec-fetch-dest": "document" } })
  const fetchReq = new Request("http://broker/applets", { headers: { authorization: "Bearer x", accept: "application/json" } })
  expect(isDocumentNavigation(nav)).toBe(true)
  expect(isDocumentNavigation(fetchReq)).toBe(false)
})
