import { expect, test } from "bun:test"
import { bearerToken, authToken, authedViaBearer } from "./cookies"

const reqWith = (headers: Record<string, string>) => new Request("https://x.test/ws", { headers })

test("bearerToken extracts the token from an Authorization: Bearer header", () => {
  expect(bearerToken(reqWith({ authorization: "Bearer abc123" }))).toBe("abc123")
})

test("bearerToken is empty when no/!bearer auth header", () => {
  expect(bearerToken(reqWith({}))).toBe("")
  expect(bearerToken(reqWith({ authorization: "Basic Zm9v" }))).toBe("")
})

test("authToken prefers cookie, falls back to bearer", () => {
  expect(authToken(reqWith({ cookie: "cmux_token=cook" }))).toBe("cook")
  expect(authToken(reqWith({ authorization: "Bearer bear" }))).toBe("bear")
  expect(authToken(reqWith({ cookie: "cmux_token=cook", authorization: "Bearer bear" }))).toBe("cook")
})

test("authedViaBearer is true only when bearer (not cookie) supplied the token", () => {
  expect(authedViaBearer(reqWith({ authorization: "Bearer bear" }))).toBe(true)
  expect(authedViaBearer(reqWith({ cookie: "cmux_token=cook" }))).toBe(false)
  expect(authedViaBearer(reqWith({}))).toBe(false)
})
