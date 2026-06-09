import { expect, test } from "bun:test"
import { chooseDefaultProject } from "./default-project"

test("follows the latest project until the user chooses one", () => {
  expect(chooseDefaultProject("~", false, [])).toBe("~")
  expect(chooseDefaultProject("~", false, ["/first"])).toBe("/first")
  expect(chooseDefaultProject("/first", false, ["/second", "/first"])).toBe("/second")
})

test("preserves a project explicitly chosen by the user", () => {
  expect(chooseDefaultProject("/chosen", true, ["/latest"])).toBe("/chosen")
})
