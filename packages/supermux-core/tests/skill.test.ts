// The agent skill (skills/supermux-core) against the code it describes. Its runnable blocks are run by
// e2e/snippets.e2e.mjs; here: the frontmatter an installer reads, the event kinds, and the names its API reference
// tells an agent to import.
import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import ts from "typescript"

const packageRoot = new URL("../", import.meta.url)
const read = (path: string) => readFileSync(new URL(path, packageRoot), "utf8")
const parse = (path: string) => ts.createSourceFile(path, read(path), ts.ScriptTarget.Latest)
const skill = "skills/supermux-core/"

test("SKILL.md opens with frontmatter that names the skill and says when to use it", () => {
  const frontmatter = /^---\n([\s\S]*?)\n---\n/.exec(read(`${skill}SKILL.md`))
  expect(frontmatter).not.toBeNull()
  const fields = Object.fromEntries(frontmatter![1].split("\n").flatMap(line => {
    const field = /^([a-z-]+):\s*(.*)$/.exec(line)
    return field ? [[field[1], field[2]!.trim()]] : []
  }))
  expect(fields.name).toBe("supermux-core")
  // A plain YAML scalar: a ": " in it would make it a mapping, and installers cap the length.
  expect(fields.description).toMatch(/^Use when .{40,}\.$/)
  expect(fields.description).not.toContain(": ")
  expect(fields.description!.length).toBeLessThanOrEqual(1024)
})

/** The `kind` of each member of the body union in src/events/normalized.ts. */
function bodyKinds(): string[] {
  const source = parse("src/events/normalized.ts")
  const union = source.statements.find((statement): statement is ts.TypeAliasDeclaration =>
    ts.isTypeAliasDeclaration(statement) && statement.name.text === "NormalizedBodyBase")
  if (!union || !ts.isUnionTypeNode(union.type)) throw new Error("NormalizedBodyBase is not a union type")
  return union.type.types.map(member => {
    const kind = ts.isTypeLiteralNode(member) && member.members.find(property =>
      ts.isPropertySignature(property) && ts.isIdentifier(property.name) && property.name.text === "kind")
    const type = kind && ts.isPropertySignature(kind) ? kind.type : undefined
    if (!type || !ts.isLiteralTypeNode(type) || !ts.isStringLiteral(type.literal)) {
      throw new Error(`a NormalizedBodyBase member has no string kind: ${member.getText(source)}`)
    }
    return type.literal.text
  })
}

test("references/events.md has a line for every event kind, and no kind the code does not have", () => {
  const kinds = bodyKinds()
  expect(kinds.length).toBeGreaterThan(20)
  const events = read(`${skill}references/events.md`)
  expect(kinds.filter(kind => !events.includes(`\`${kind}\``))).toEqual([])
  // Each kind's line starts "- `kind`"; the core's own events have dots in their names (`message.completed`).
  const listed = [...events.matchAll(/^- `([a-z-]+)`/gm)].map(match => match[1])
  expect(listed.filter(kind => !kinds.includes(kind!))).toEqual([])
})

/** The names `path` (an entry module of the package) exports, values and types. */
function exportsOf(path: string): Set<string> {
  const names = new Set<string>()
  for (const statement of parse(path).statements) {
    if (ts.isExportDeclaration(statement)) {
      if (!statement.exportClause || !ts.isNamedExports(statement.exportClause)) throw new Error(`${path}: export * is not read here`)
      for (const element of statement.exportClause.elements) names.add(element.name.text)
    } else if (ts.canHaveModifiers(statement) && ts.getModifiers(statement)?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword)) {
      if (ts.isVariableStatement(statement)) {
        for (const declaration of statement.declarationList.declarations) if (ts.isIdentifier(declaration.name)) names.add(declaration.name.text)
      } else {
        const name = (statement as { name?: ts.Node }).name
        if (name && ts.isIdentifier(name)) names.add(name.text)
      }
    }
  }
  return names
}

/** The source entry of an import specifier of this package, through package.json's exports ("./dist/x.js" → "src/x.ts"). */
function entryOf(specifier: string): string {
  const exports: Record<string, string | { import: string }> = JSON.parse(read("package.json")).exports
  const target = exports[`.${specifier.slice("@supermux/core".length)}`]
  if (!target) throw new Error(`${specifier} is not a subpath of the package`)
  return (typeof target === "string" ? target : target.import).replace(/^\.\/dist\//, "src/").replace(/\.js$/, ".ts")
}

// Names a signature may use without importing them: TypeScript's and Node's own.
const GLOBALS = new Set(["Promise", "Record", "Array", "Partial", "Omit", "Pick", "Error", "AbortSignal", "NodeJS"])

test("references/api.md imports only names the package exports, and uses no other name of its own", () => {
  const code = [...read(`${skill}references/api.md`).matchAll(/^```ts\n([\s\S]*?)^```$/gm)].map(block => block[1]).join("\n")
  const imports = [...code.matchAll(/^import (?:type )?\{([^}]*)\} from "([^"]+)"/gm)]
  expect(imports.length).toBeGreaterThan(0)
  const imported = new Set<string>()
  for (const [, list, specifier] of imports) {
    const names = list!.split(",").map(name => name.trim()).filter(Boolean)
    for (const name of names) imported.add(name)
    if (!specifier!.startsWith("@supermux/core")) continue
    const exported = exportsOf(entryOf(specifier!))
    expect({ specifier, missing: names.filter(name => !exported.has(name)) }).toEqual({ specifier, missing: [] })
  }
  // Without comments and strings: every type name (capitalized, not after a dot) and every function a line starts
  // with is imported above, or a global.
  const bare = code.replace(/\/\/.*$/gm, "").replace(/"[^"\n]*"/g, '""').replace(/^import .*$/gm, "")
  const used = new Set([
    ...[...bare.matchAll(/(?<![.\w])[A-Z]\w*/g)].map(match => match[0]),
    ...[...bare.matchAll(/^([a-z]\w*)\(/gm)].map(match => match[1]!),
  ])
  expect([...used].filter(name => !imported.has(name) && !GLOBALS.has(name))).toEqual([])
})

test("the API reference's own check reads every entry it is pointed at", () => {
  // A guard on the guard: the export reader finds what the entries really export.
  expect(exportsOf(entryOf("@supermux/core")).has("supermux")).toBe(true)
  expect(exportsOf(entryOf("@supermux/core")).has("CoreEvent")).toBe(true)
  expect(exportsOf(entryOf("@supermux/core/mcp")).has("mcpServer")).toBe(true)
  expect(fileURLToPath(new URL(entryOf("@supermux/core/mcp"), packageRoot))).toEndWith("src/mcp/index.ts")
})
