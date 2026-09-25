package dev.supermux.editor.syntax

/** Small documents per language, highlighted into golden/<name>.txt (M2c reuses them for web). */
internal object HighlightSamples {
    val KOTLIN = """
        |package demo.app
        |
        |import kotlin.math.max
        |
        |/** A shape. */
        |sealed class Shape(val name: String) {
        |    abstract fun area(): Double
        |
        |    data class Circle(val r: Double) : Shape("circle") {
        |        override fun area() = Math.PI * r * r
        |    }
        |}
        |
        |fun main(args: Array<String>) {
        |    val shapes = listOf(Shape.Circle(1.5))
        |    for (s in shapes) println("${'$'}{s.name}: ${'$'}{max(s.area(), 0.0)} ağ 😀")
        |    // done
        |}
        |""".trimMargin()

    val TYPESCRIPT = """
        |import { readFile } from "fs/promises";
        |
        |interface User { id: number; name?: string }
        |
        |export async function load(path: string): Promise<User[]> {
        |  const raw = await readFile(path, "utf8");
        |  return JSON.parse(raw) as User[];
        |}
        |
        |enum Color { Red = 1, Green }
        |const re = /ab+c/g;
        |""".trimMargin()

    val TSX = """
        |export const Hello = ({ name }: { name: string }) => (
        |  <div className="hi" onClick={() => alert(name)}>
        |    Hello, {name}!
        |  </div>
        |);
        |""".trimMargin()

    val PYTHON = """
        |import os
        |from typing import List
        |
        |@dataclass
        |class Point:
        |    x: int = 0
        |
        |def walk(root: str) -> List[str]:
        |    ""${'"'}Yield files.""${'"'}
        |    out = []
        |    for d, _, files in os.walk(root):
        |        out += [os.path.join(d, f) for f in files if not f.startswith(".")]
        |    return out  # ağ
        |""".trimMargin()

    val MARKDOWN = """
        |# Title
        |
        |Some *emphasis* and a [link](https://example.com).
        |
        |```kotlin
        |fun main() = println("hi")
        |```
        |
        |- item `code`
        |""".trimMargin()

    val HTML = """
        |<!DOCTYPE html>
        |<html>
        |<head>
        |  <style>
        |    body { color: red; }
        |  </style>
        |</head>
        |<body class="x">
        |  <script>
        |    const n = 1 + 2;
        |  </script>
        |</body>
        |</html>
        |""".trimMargin()

    val VUE = """
        |<template>
        |  <div :class="cls">{{ msg }}</div>
        |</template>
        |
        |<script lang="ts">
        |export default { data: () => ({ msg: "hi" as string }) }
        |</script>
        |
        |<style scoped>
        |.a { margin: 0 }
        |</style>
        |""".trimMargin()

    /** name -> (language, text) */
    val ALL = linkedMapOf(
        "json" to ("json" to SAMPLE),
        "kotlin" to ("kotlin" to KOTLIN),
        "typescript" to ("typescript" to TYPESCRIPT),
        "tsx" to ("tsx" to TSX),
        "python" to ("python" to PYTHON),
        "markdown" to ("markdown" to MARKDOWN),
        "html" to ("html" to HTML),
        "vue" to ("vue" to VUE),
    )
}
