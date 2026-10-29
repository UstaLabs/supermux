import { expect, test } from "bun:test"
import { letterAt } from "./letters"

test("letters run A–Z, then AA, AB, …", () => {
  expect([0, 1, 25, 26, 27, 51, 52].map(letterAt)).toEqual(["A", "B", "Z", "AA", "AB", "AZ", "BA"])
})
