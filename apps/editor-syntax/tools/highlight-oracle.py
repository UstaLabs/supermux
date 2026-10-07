#!/usr/bin/env python3
"""highlight-oracle.py: tree-sitter's own highlighter as a cross-check of our span goldens.

Run on the Mac, inside apps/editor-syntax, after native/fetch.sh and a golden update (which writes
the samples to src/nativeBackedTest/resources/golden/samples/):

    python3 tools/highlight-oracle.py

For each core sample it runs `npx tree-sitter-cli@0.25.10 highlight --html --css-classes` with OUR
pinned grammar sources (build/grammars) and OUR shipped highlights query, recognising every
capture name of that query, and writes golden/oracle/<name>.txt: the innermost capture of every
UTF-16 unit, mapped to a token class like Captures.kt (longest dotted prefix), as
`start-end tok-class` runs. OracleTest compares our spans with these files; the differences it
accepts are listed there, each with its reason.

Injections are not part of the cross-check (the CLI resolves injected languages by its own
scope rules); the samples below have none that fire.
"""
import html, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SAMPLES = os.path.join(HERE, "src/nativeBackedTest/resources/golden/samples")
OUT = os.path.join(HERE, "src/nativeBackedTest/resources/golden/oracle")
QUERIES = os.path.join(HERE, "src/commonMain/resources/queries")
CAPTURES_KT = os.path.join(HERE, "src/commonMain/kotlin/dev/supermux/editor/syntax/Captures.kt")
CLI = "tree-sitter-cli@0.25.10"

# sample name -> (language id, grammar source dir under build/grammars, file extension)
CORE = {
    "json": ("json", "json", "json"),
    "kotlin": ("kotlin", "kotlin", "kt"),
    "typescript": ("typescript", "typescript/typescript", "ts"),
    "tsx": ("tsx", "typescript/tsx", "tsx"),
    "python": ("python", "python", "py"),
    "go": ("go", "go", "go"),
    "scala": ("scala", "scala", "scala"),
    "glsl": ("glsl", "glsl", "glsl"),
    "pascal": ("pascal", "pascal", "pas"),
}


def capture_classes():
    """CAPTURE_CLASSES from Captures.kt: the constant names resolved to their tok-* values."""
    src = open(CAPTURES_KT, encoding="utf-8").read()
    consts = dict(re.findall(r'const val ([A-Z_]+) = "(tok-[a-z-]+)"', src))
    table = {}
    body = src[src.index("internal val CAPTURE_CLASSES"):]
    for name, value in re.findall(r'"([a-z0-9_.\-]+)" to ([A-Z_]+|"")', body):
        table[name] = "" if value == '""' else consts[value]
    return table


def token_class(capture, table):
    if not capture or capture.startswith("_"):
        return None
    name = capture
    while True:
        if name in table:
            return table[name] or None
        if "." not in name:
            return None
        name = name.rsplit(".", 1)[0]


def query_captures(path):
    # strings first: a `";"` token must not start a comment
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', open(path, encoding="utf-8").read())
    text = re.sub(r";[^\n]*", "", text)
    return sorted(set(re.findall(r"@([A-Za-z0-9_.\-]+)", text)))


def main():
    table = capture_classes()
    os.makedirs(OUT, exist_ok=True)
    work = tempfile.mkdtemp(prefix="ts-oracle-")
    parsers = os.path.join(work, "parsers")
    os.makedirs(parsers)
    names = set()
    for sample, (lang, gdir, ext) in CORE.items():
        root = os.path.join(parsers, "tree-sitter-" + lang)
        os.makedirs(os.path.join(root, "queries"))
        # the grammar's own directory (its scanner may include ../common/ headers): link its src
        os.symlink(os.path.join(HERE, "build/grammars", gdir, "src"), os.path.join(root, "src"))
        shutil.copy(os.path.join(QUERIES, lang, "highlights.scm"), os.path.join(root, "queries/highlights.scm"))
        names.update(query_captures(os.path.join(root, "queries/highlights.scm")))
        json.dump({
            "grammars": [{"name": lang, "camelcase": lang, "scope": "source." + lang, "path": ".",
                          "file-types": [ext], "highlights": "queries/highlights.scm"}],
            "metadata": {"version": "0.0.0", "license": "MIT", "description": lang, "links": {"repository": "https://example.com"}},
        }, open(os.path.join(root, "tree-sitter.json"), "w"))
    names.discard("none")  # our @none clears; the CLI draws nothing for an unrecognised name either
    config = os.path.join(work, "config.json")
    json.dump({"parser-directories": [parsers], "theme": {n: "#000000" for n in sorted(names) if not n.startswith("_")}},
              open(config, "w"))
    for sample, (lang, gdir, ext) in CORE.items():
        path = os.path.join(SAMPLES, sample + ".txt")
        text = open(path, encoding="utf-8").read()
        src = os.path.join(work, sample + "." + ext)
        open(src, "w", encoding="utf-8").write(text)
        out = subprocess.run(["npx", "-y", CLI, "highlight", "--html", "--css-classes", "--config-path", config,
                              "--scope", "source." + lang, src], capture_output=True, text=True, cwd=work)
        if out.returncode:
            sys.exit("%s: tree-sitter highlight failed:\n%s" % (sample, out.stderr[-2000:]))
        runs = flatten(out.stdout, text, table)
        with open(os.path.join(OUT, sample + ".txt"), "w", encoding="utf-8") as f:
            f.write("".join("%d-%d %s\n" % r for r in runs))
        print("%-10s %4d oracle spans" % (sample, len(runs)))
    shutil.rmtree(work, ignore_errors=True)


def flatten(page, text, table):
    """The CLI's HTML (nested <span class='a b'>) as (start, end, tok-class) runs over [text]."""
    body = page[page.index("<table>"):] if "<table>" in page else page
    # each source line is a table row: <tr><td class=line-number>..</td><td class=line>CONTENT</td></tr>
    lines = re.findall(r"<td class=line>(.*?)</td></tr>", body, re.S)
    if not lines:
        lines = re.findall(r"<td class='line'>(.*?)</td></tr>", body, re.S)
    stack, cls = [], []
    for line in lines:
        pos = 0
        for m in re.finditer(r"<span class='([^']*)'>|<span class=\"([^\"]*)\">|</span>|<[^>]+>|([^<]+)", line):
            if m.group(1) is not None or m.group(2) is not None:
                stack.append((m.group(1) or m.group(2)).replace(" ", "."))
            elif m.group(0) == "</span>":
                stack.pop()
            elif m.group(3) is not None:
                for ch in html.unescape(m.group(3)):
                    units = 2 if ord(ch) > 0xFFFF else 1
                    c = token_class(stack[-1], table) if stack else None
                    cls.extend([c] * units)
    # every row holds its line INCLUDING the newline, so the rows concatenated are the text
    n = len(text.encode("utf-16-le")) // 2
    units = (cls + [None] * n)[:n]
    runs, i = [], 0
    while i < n:
        c = units[i]
        j = i + 1
        while j < n and units[j] == c:
            j += 1
        if c:
            runs.append((i, j, c))
        i = j
    return runs


if __name__ == "__main__":
    main()
