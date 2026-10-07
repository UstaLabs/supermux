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

    /**
     * A valid Kotlin file of at least [lines] lines: one package / import header, then the sample's
     * declarations again and again (renamed, so it stays one well-formed file).
     */
    fun kotlinLines(lines: Int): String {
        val body = KOTLIN.substring(KOTLIN.indexOf("/** A shape. */"))
        val per = body.count { it == '\n' }
        return buildString {
            append("package demo.app\n\nimport kotlin.math.max\n\n")
            var i = 0
            while (i * per < lines) {
                append(body.replace("Shape", "Shape$i").replace("fun main", "fun main$i"))
                append('\n')
                i++
            }
        }
    }

    val PHP = """
        |<html>
        |<body>
        |<?php
        |namespace App;
        |
        |function greet(string ${'$'}name): string {
        |    return "Hello, " . ${'$'}name;
        |}
        |?>
        |<p class="x"><?= greet("ağ") ?></p>
        |<script>let n = 1;</script>
        |</body>
        |</html>
        |""".trimMargin()

    val GO = """
        |package main
        |
        |import "fmt"
        |
        |func main() {
        |    xs := make([]int, 0, 4)
        |    xs = append(xs, len("ağ"))
        |    fmt.Println(xs, cap(xs))
        |    println("done")
        |}
        |""".trimMargin()

    val SCALA = """
        |package demo
        |
        |object Main extends App {
        |  case class Point(x: Int, y: Int)
        |  val p = Point(1, 2)
        |  def norm(q: Point): Double = math.sqrt(q.x * q.x + q.y * q.y)
        |  println(s"norm = ${'$'}{norm(p)}")
        |}
        |""".trimMargin()

    val GLSL = """
        |#version 330 core
        |uniform vec3 color;
        |out vec4 fragColor;
        |void main() {
        |    float a = clamp(gl_FragCoord.x / 100.0, 0.0, 1.0);
        |    fragColor = vec4(color * a, 1.0);
        |}
        |""".trimMargin()

    val PASCAL = """
        |program Hello;
        |var
        |  i: Integer;
        |begin
        |  for i := 1 to 3 do
        |    WriteLn('Hello ', i);
        |end.
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
        "php" to ("php" to PHP),
        "go" to ("go" to GO),
        "scala" to ("scala" to SCALA),
        "glsl" to ("glsl" to GLSL),
        "pascal" to ("pascal" to PASCAL),
    )

    /** Big documents for the performance numbers: [unit] repeated to at least [lines] lines. */
    fun repeatTo(unit: String, lines: Int, header: String = "", footer: String = ""): String {
        val per = maxOf(1, unit.count { it == '\n' })
        return header + unit.repeat((lines + per - 1) / per) + footer
    }

    val MARKDOWN_UNIT = """
        |## Section
        |
        |A paragraph with *emphasis*, `code` and a [link](https://example.com), ağ 😀.
        |Another line of the same paragraph.
        |
        |- item one
        |- item **two**
        |
        |```kotlin
        |fun f(x: Int) = x + 1
        |```
        |
        |<div class="note">html block</div>
        |
        |""".trimMargin()

    val VUE_SCRIPT_UNIT = """
        |export function f(x: number): string {
        |  const s = "ağ" + x
        |  return s.repeat(2) // twice
        |}
        |""".trimMargin()

    val PHP_UNIT = """
        |function f(array ${'$'}xs): int {
        |    return count(${'$'}xs) + strlen("ağ");
        |}
        |""".trimMargin()
}
