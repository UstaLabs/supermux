import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { readSchemaStamp, writeSchemaStamp, checkSchemaStamp } from "./schema-stamp"

function makeTmpDir(): string {
  return mkdtempSync(join(tmpdir(), "mux-stamp-"))
}

describe("readSchemaStamp", () => {
  test("missing file → undefined", () => {
    const dir = makeTmpDir()
    try {
      expect(readSchemaStamp(dir)).toBeUndefined()
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("garbage content → undefined", () => {
    const dir = makeTmpDir()
    try {
      writeFileSync(join(dir, "schema-version"), "not-a-number\n")
      expect(readSchemaStamp(dir)).toBeUndefined()
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("valid number → returns it", () => {
    const dir = makeTmpDir()
    try {
      writeFileSync(join(dir, "schema-version"), "16\n")
      expect(readSchemaStamp(dir)).toBe(16)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })
})

describe("writeSchemaStamp / readSchemaStamp roundtrip", () => {
  test("writes and reads back the same value", () => {
    const dir = makeTmpDir()
    try {
      writeSchemaStamp(dir, 16)
      expect(readSchemaStamp(dir)).toBe(16)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("file content is '<value>\\n'", () => {
    const dir = makeTmpDir()
    try {
      writeSchemaStamp(dir, 7)
      const { readFileSync: readfs } = require("fs")
      expect(readfs(join(dir, "schema-version"), "utf8")).toBe("7\n")
      expect(readSchemaStamp(dir)).toBe(7)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })
})

describe("checkSchemaStamp", () => {
  test("missing stamp → ok:true", () => {
    const dir = makeTmpDir()
    try {
      expect(checkSchemaStamp(dir, 16)).toEqual({ ok: true })
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("stamp == supported → ok:true", () => {
    const dir = makeTmpDir()
    try {
      writeSchemaStamp(dir, 16)
      expect(checkSchemaStamp(dir, 16)).toEqual({ ok: true })
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("stamp < supported → ok:true", () => {
    const dir = makeTmpDir()
    try {
      writeSchemaStamp(dir, 10)
      expect(checkSchemaStamp(dir, 16)).toEqual({ ok: true })
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("stamp > supported → ok:false with stamp value", () => {
    const dir = makeTmpDir()
    try {
      writeSchemaStamp(dir, 999)
      expect(checkSchemaStamp(dir, 16)).toEqual({ ok: false, stamp: 999 })
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("garbage content → undefined stamp → ok:true", () => {
    const dir = makeTmpDir()
    try {
      writeFileSync(join(dir, "schema-version"), "garbage\n")
      expect(checkSchemaStamp(dir, 16)).toEqual({ ok: true })
    } finally {
      rmSync(dir, { recursive: true })
    }
  })
})
