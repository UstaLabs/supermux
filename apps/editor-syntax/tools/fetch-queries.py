#!/usr/bin/env python3
"""fetch-queries.py: fill src/commonMain/resources/queries/<lang>/{highlights,injections,folds}.scm
from native/queries.lock.json. Run on the Mac, inside apps/editor-syntax, after native/fetch.sh and
`native/build.sh macos-arm64` (the compile check loads that library):

    python3 tools/fetch-queries.py            # fetch, verify against the lock, write the queries + notes
    python3 tools/fetch-queries.py --pin      # (re)record every source file's sha256 in the lock

The output is committed; this tool only has to run again when a source or a grammar is bumped.

Per language and query kind, the lock lists the source files, in order (a ref, or
{"ref": ..., "precedence": "first" | "last"} when a file does not follow its source's convention):
  npm:<grammar>/<path>   a file inside that grammar's npm tarball (url + sha256 in grammars.lock.json)
  helix:<dir>/<file>     helix-editor/helix at the lock's commit, runtime/queries/<dir>/<file> (MPL-2.0)
  nvim:<dir>/<file>      nvim-treesitter at the lock's commit, runtime/queries/<dir>/<file> (Apache-2.0)
Every fetched file (inherited ones too) is recorded with its sha256; a mismatch fails the run.

What the tool does to the text, all recorded in the lock's "notes":
- `; inherits: a,b` (Helix, nvim) is replaced by those files' contents, in order, recursively.
- Precedence: when several patterns capture one node, the LATER pattern wins, as in
  tree-sitter-highlight 0.25.10 (highlight/src/lib.rs, "set the match to it"), Helix and nvim,
  and in our Highlighter. Some npm files are still written the other way round (specific
  patterns first, a catch-all like `(identifier) @variable` last): their patterns are reversed.
  An npm file's convention is read from where its catch-alls sit (see `convention`); the lock
  can also state it per file.
- Predicates the backend does not evaluate must never ship (they would pass silently):
  `#lua-match?` / `#not-lua-match?` become `#match?` / `#not-match?` when the Lua pattern converts
  to an equivalent regex, `#contains?` becomes an escaped `#match?` alternation, `#is-not? local`
  and `#trim!` are removed (the one only narrows a match, the other only trims a fold's
  trailing blank lines), and every other pattern using an unknown predicate or
  directive (`#is? local`, `#has-ancestor?`, `#kind-eq?`, `#offset!`, ...) is dropped.
- An injection into a language the lock lists as unavailable (no grammar here: comment, regex,
  jsdoc, ...) is dropped: it could never be drawn and would only cost query time.
- The queries are then compiled against OUR grammar versions (the macOS library): a node, field or
  token our grammar lacks drops that alternative of a `[...]` list, or else the whole pattern.
"""
import argparse, ctypes, hashlib, json, os, re, sys, tarfile, urllib.error, urllib.request

HERE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
LOCK = os.path.join(HERE, "native/queries.lock.json")
GRAMMARS = os.path.join(HERE, "native/grammars.lock.json")
OUT = os.path.join(HERE, "src/commonMain/resources/queries")
CACHE = os.path.join(HERE, "build/query-sources")
KINDS = ("highlights", "injections", "folds")

# What ses_query_captures evaluates (native/README.md "Predicates"), plus #set! (read back by the
# highlighter: injection.*, priority). Anything else is rewritten or its pattern dropped.
SUPPORTED = {"eq?", "not-eq?", "any-eq?", "any-not-eq?", "any-of?", "not-any-of?",
             "match?", "not-match?", "any-match?", "any-not-match?", "set!"}


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def fetch(url, cache_name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, cache_name)
    if os.path.exists(path + ".404"):
        return None
    if not os.path.exists(path):
        try:
            with urllib.request.urlopen(url) as r:
                data = r.read()
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            open(path + ".404", "w").close()
            return None
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    return open(path, "rb").read()


class Sources:
    def __init__(self, lock):
        self.lock = lock
        self.grammars = {g["lang"]: g for g in json.load(open(GRAMMARS))["grammars"]}
        self.tars = {}

    def raw(self, ref):
        """(bytes, where) of a source ref like helix:kotlin/highlights.scm."""
        kind, _, rest = ref.partition(":")
        if kind == "npm":
            grammar, _, path = rest.partition("/")
            g = self.grammars[grammar]
            tgz = os.path.join(HERE, "build/dl", "%s-%s.tgz" % (g["lang"], g["version"]))
            if not os.path.exists(tgz):
                tgz = os.path.join(CACHE, "%s-%s.tgz" % (g["lang"], g["version"]))
                fetch(g["url"], os.path.basename(tgz))
            if sha256(open(tgz, "rb").read()) != g["sha256"]:
                sys.exit("%s: tarball sha256 differs from grammars.lock.json" % tgz)
            if tgz not in self.tars:
                t = tarfile.open(tgz)
                self.tars[tgz] = {m.name.split("/", 1)[1]: t.extractfile(m).read()
                                  for m in t.getmembers() if m.isfile() and "/" in m.name}
            files = self.tars[tgz]
            if path not in files:
                sys.exit("%s: no %s in %s" % (ref, path, tgz))
            return files[path], "%s@%s %s (%s)" % (g["package"], g["version"], path, g["license"])
        src = self.lock["sources"][kind]
        url = "https://raw.githubusercontent.com/%s/%s/%s/%s" % (src["repo"], src["commit"], src["dir"], rest)
        data = fetch(url, "%s-%s-%s" % (kind, src["commit"][:12], rest.replace("/", "__")))
        if data is None:
            return None, url
        return data, "%s@%s %s/%s (%s)" % (src["repo"], src["commit"][:12], src["dir"], rest, src["license"])


# ------------------------------------------------------------------------------ the query syntax --

TOKEN = re.compile(r"""
    (?P<ws>\s+) | (?P<comment>;[^\n]*) | (?P<open>[(\[]) | (?P<close>[)\]]) |
    (?P<string>"(?:[^"\\]|\\.)*") | (?P<capture>@[A-Za-z0-9_.\-+]*) | (?P<pred>\#[A-Za-z0-9_\-?!.]+) |
    (?P<quant>[*+?]) | (?P<anchor>\.) | (?P<neg>![A-Za-z_][A-Za-z0-9_]*) |
    (?P<field>[A-Za-z_][A-Za-z0-9_]*:) | (?P<word>[A-Za-z0-9_\-./]+)
""", re.X | re.S)


class Node:
    """A token or a () / [] group; [start, end) character offsets into the source."""
    def __init__(self, kind, start, end, text=None):
        self.kind, self.start, self.end, self.text, self.children = kind, start, end, text, []

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


def parse(src):
    root = Node("root", 0, len(src))
    stack = [root]
    pos = 0
    while pos < len(src):
        m = TOKEN.match(src, pos)
        if not m:
            raise ValueError("cannot tokenize at %d: %r" % (pos, src[pos:pos + 40]))
        kind = m.lastgroup
        if kind == "open":
            g = Node(m.group(), m.start(), -1)
            stack[-1].children.append(g)
            stack.append(g)
        elif kind == "close":
            g = stack.pop()
            g.end = m.end()
        elif kind not in ("ws", "comment"):
            stack[-1].children.append(Node(kind, m.start(), m.end(), m.group()))
        pos = m.end()
    if len(stack) != 1:
        raise ValueError("unbalanced parentheses")
    return root


def items(group):
    """A group's children as elements: an atom followed by its quantifiers and captures, a field
    prefix joined to what follows it. [(start, end, atom)]."""
    out, cur = [], None
    for c in group.children:
        if c.kind in ("quant", "capture") and cur is not None:
            cur[1] = c.end
            continue
        if cur is not None and cur[3]:  # a pending field prefix: this child completes it
            cur[1], cur[2], cur[3] = c.end, c, False
            continue
        cur = [c.start, c.end, c, c.kind == "field"]
        out.append(cur)
    return [(s, e, a) for s, e, a, _ in out]


def predicates(node):
    """Every predicate group inside [node]: (group, name, args)."""
    for n in node.walk():
        if n.kind == "(" and n.children and n.children[0].kind == "pred":
            yield n, n.children[0].text[1:], n.children[1:]


def unquote(s):
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "t": "\t", "r": "\r", "0": "\0"}.get(m.group(1), m.group(1)), s[1:-1])


def quote(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


# Lua character classes (C locale, as nvim's Lua uses them) -> regex class contents.
LUA_CLASS = {"a": "A-Za-z", "d": "0-9", "l": "a-z", "u": "A-Z", "s": " \\t\\n\\r\\f\\v", "w": "A-Za-z0-9",
             "x": "0-9A-Fa-f", "p": "!-/:-@\\[-`{-~", "c": "\\x00-\\x1f\\x7f", "g": "!-~"}
LUA_NEG = {"S": "\\S", "D": "[^0-9]", "W": "[^A-Za-z0-9]", "A": "[^A-Za-z]", "L": "[^a-z]", "U": "[^A-Z]"}
REGEX_SPECIAL = set("\\.^$|?*+()[]{}")


def lua_to_regex(p):
    """A Lua pattern as an equivalent regex, or None when it uses what regexes spell differently
    (lazy `-`, %b, %f, back-references, position captures)."""
    out, i, in_class = [], 0, False
    while i < len(p):
        c = p[i]
        if c == "%":
            if i + 1 >= len(p):
                return None
            n = p[i + 1]
            if n in LUA_CLASS:
                out.append(LUA_CLASS[n] if in_class else "[" + LUA_CLASS[n] + "]")
            elif n in LUA_NEG and not in_class:
                out.append(LUA_NEG[n])
            elif n.isalnum():
                return None
            else:
                out.append("\\" + n if n in REGEX_SPECIAL or n in "-" else n)
            i += 2
            continue
        if in_class:
            if c == "]" and out[-1] not in ("[", "[^"):
                in_class = False
            elif c in "\\[":
                out.append("\\" + c)
                i += 1
                continue
            out.append(c)
        elif c == "[":
            in_class = True
            if p[i + 1:i + 2] == "^":
                out.append("[^")
                i += 2
                continue
            out.append("[")
        elif c == "-":
            return None  # Lua's lazy *
        elif c == "(" and p[i + 1:i + 2] == ")":
            return None  # a position capture
        elif c in "{}|\\":
            out.append("\\" + c)
        elif c == "^" and i != 0:
            out.append("\\^")
        elif c == "$" and i != len(p) - 1:
            out.append("\\$")
        else:
            out.append(c)
        i += 1
    return None if in_class else "".join(out)


# Rust regex syntax that Kotlin's Regex reads differently. Kotlin's Regex is java.util.regex on the JVM
# (ASCII \\w \\d \\s \\b), ICU on Android (Unicode classes) and Kotlin/Native's own engine on iOS; the
# queries are written for Rust's regex crate, where \\w and \\d are Unicode and POSIX classes ASCII.
# Spell those out so every platform agrees with Rust: [:alpha:] -> A-Za-z, \\w -> [\\p{L}\\p{M}\\p{Nd}\\p{Pc}].
POSIX = {"alpha": "A-Za-z", "digit": "0-9", "alnum": "A-Za-z0-9", "upper": "A-Z", "lower": "a-z",
         "space": " \\t\\n\\r\\f\\x0B", "punct": "!-/:-@\\[-`{-~", "xdigit": "0-9A-Fa-f", "word": "A-Za-z0-9_"}
WORD = "\\p{L}\\p{M}\\p{Nd}\\p{Pc}"


def rust_to_kotlin_regex(r):
    out, i, depth = [], 0, 0
    while i < len(r):
        c = r[i]
        if c == "\\" and i + 1 < len(r):
            n = r[i + 1]
            if n == "w": out.append(WORD if depth else "[" + WORD + "]")
            elif n == "W" and not depth: out.append("[^" + WORD + "]")
            elif n == "d": out.append("\\p{Nd}")
            elif n == "D" and not depth: out.append("\\P{Nd}")
            else: out.append(r[i:i + 2])
            i += 2
            continue
        if c == "[":
            m = re.match(r"\[:([a-z]+):\]", r[i:])
            if depth and m and m.group(1) in POSIX:
                out.append(POSIX[m.group(1)])
                i += len(m.group(0))
                continue
            depth += 1
        elif c == "]" and depth:
            depth -= 1
        out.append(c)
        i += 1
    return "".join(out)


def rewrite(src, notes, where, unavailable=()):
    """Rewrite or drop the patterns of [src] whose predicates the backend does not evaluate, and
    injections into a language listed in [unavailable] (no grammar here: they would only cost)."""
    root = parse(src)
    patterns = []
    for s, e, atom in items(root):
        text = src[s:e]
        edits = []  # (start, end, replacement) relative to src
        drop = None
        for g, name, args in predicates(atom):
            gs = src[g.start:g.end]
            if name == "set!" and unavailable:
                vals = [unquote(a.text) if a.kind == "string" else a.text for a in args if a.kind in ("string", "word")]
                if len(vals) >= 2 and vals[0] == "injection.language" and vals[1] in unavailable:
                    drop = "injects %s, which has no grammar here" % vals[1]
                    break
            if name in SUPPORTED:
                if name.endswith("match?"):
                    for a in args:
                        if a.kind == "string":
                            r = unquote(a.text)
                            k = rust_to_kotlin_regex(r)
                            if k != r:
                                edits.append((a.start, a.end, quote(k)))
                                notes.append("%s: regex %s -> %s (Rust's meaning of its classes, spelled out)" % (where, quote(r), quote(k)))
                continue
            if name in ("lua-match?", "not-lua-match?"):
                strs = [a for a in args if a.kind == "string"]
                caps = [a for a in args if a.kind == "capture"]
                conv = lua_to_regex(unquote(strs[0].text)) if len(strs) == 1 and len(caps) == 1 else None
                if conv is None:
                    drop = "#%s %s does not convert to a regex" % (name, strs[0].text if strs else "")
                    break
                new = "(#%s %s %s)" % ("match?" if name == "lua-match?" else "not-match?", caps[0].text, quote(conv))
                edits.append((g.start, g.end, new))
                notes.append("%s: %s -> %s" % (where, gs, new))
            elif name in ("contains?", "any-contains?") and all(a.kind in ("capture", "string") for a in args):
                caps = [a for a in args if a.kind == "capture"]
                strs = [unquote(a.text) for a in args if a.kind == "string"]
                if len(caps) != 1 or not strs:
                    drop = "malformed #%s" % name
                    break
                new = "(#%s %s %s)" % ("match?" if name == "contains?" else "any-match?", caps[0].text,
                                       quote("|".join(re.escape(x) for x in strs)))
                edits.append((g.start, g.end, new))
                notes.append("%s: %s -> %s" % (where, gs, new))
            elif name == "trim!":
                edits.append((g.start, g.end, ""))
                notes.append("%s: removed %s (it only trims a fold's trailing blank lines)" % (where, gs))
            elif name == "is-not?" and [a.text for a in args] == ["local"]:
                edits.append((g.start, g.end, ""))
                notes.append("%s: removed %s (locals are not tracked; it only narrows the match)" % (where, gs))
            else:
                drop = "#%s is not evaluated by the backend" % name
                break
        if drop:
            notes.append("%s: dropped pattern (%s): %s" % (where, drop, one_line(text)))
            continue
        for es, ee, rep in sorted(edits, reverse=True):
            text = text[:es - s] + rep + text[ee - s:]
        patterns.append(text)
    return patterns


def one_line(t, n=160):
    t = re.sub(r"\s+", " ", re.sub(r";[^\n]*", "", t)).strip()
    return t if len(t) <= n else t[:n] + " ..."


CATCH_ALL = re.compile(r"^\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*@[A-Za-z0-9_.\-]+\s*$")


def convention(patterns):
    """("first" | "last", evidence) for a file of highlights patterns. A catch-all `(T) @x` placed
    AFTER the patterns that capture T in a context means the file expects the earlier pattern to
    win; placed before them, the later one. No catch-all with context: "last" (tree-sitter 0.25)."""
    before = after = 0
    shown = []
    for i, p in enumerate(patterns):
        m = CATCH_ALL.match(re.sub(r";[^\n]*", "", p).strip())
        if not m:
            continue
        node = re.compile(r"\(\s*%s\s*\)\s*@" % re.escape(m.group(1)))
        b = sum(1 for q in patterns[:i] if node.search(q) and not CATCH_ALL.match(q.strip()))
        a = sum(1 for q in patterns[i + 1:] if node.search(q) and not CATCH_ALL.match(q.strip()))
        if a or b:
            shown.append("(%s) catch-all after %d, before %d contextual patterns" % (m.group(1), b, a))
        before += b
        after += a
    if before > after:
        return "first", "; ".join(shown)
    return "last", "; ".join(shown) or "no catch-all with contextual patterns"


# ----------------------------------------------------------------------- the compile check (Mac) --

class Oracle:
    """ses_query_new of the macOS library: does a query compile against OUR grammar versions?"""
    def __init__(self, lib, gen):
        self.lib = ctypes.CDLL(lib)
        self.lib.ses_query_new.restype = ctypes.c_void_p
        self.lib.ses_query_new.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32),
                                           ctypes.POINTER(ctypes.c_int32), ctypes.POINTER(ctypes.c_int32)]
        self.lib.ses_query_free.argtypes = [ctypes.c_void_p]
        self.lib.ses_query_flags.argtypes = [ctypes.c_void_p]
        self.lib.ses_query_flags.restype = ctypes.c_uint32
        self.lib.ses_language_has_tables.argtypes = [ctypes.c_char_p]
        self.lib.ses_language_provide_tables.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
        self.gen = gen

    def ensure(self, lang):
        if self.lib.ses_language_has_tables(lang.encode()) == 0:
            blob = open(os.path.join(self.gen, lang, lang + ".sesz"), "rb").read()
            st = self.lib.ses_language_provide_tables(lang.encode(), blob, len(blob))
            if st != 0:
                sys.exit("provide tables %s: %d" % (lang, st))

    def check(self, lang, src):
        """None if [src] compiles, else (byte offset, error type)."""
        b = src.encode()
        off, typ, st = ctypes.c_uint32(), ctypes.c_int32(), ctypes.c_int32()
        q = self.lib.ses_query_new(lang.encode(), b, len(b), ctypes.byref(off), ctypes.byref(typ), ctypes.byref(st))
        if q:
            flags = self.lib.ses_query_flags(q)
            self.lib.ses_query_free(q)
            if flags & 1:
                sys.exit("%s: a query still uses #lua-match?" % lang)
            return None
        if st.value != -11:
            sys.exit("%s: ses_query_new status %d" % (lang, st.value))
        return off.value, typ.value


ERRORS = {-1: "malformed predicate", 1: "syntax", 2: "unknown node type", 3: "unknown field", 4: "unknown capture",
          5: "impossible structure", 6: "language"}


def fit(oracle, lang, patterns, notes, where):
    """Drop what does not compile against our grammar: an alternative of a [...] list, else the pattern."""
    patterns = list(patterns)
    while True:
        src = "\n\n".join(patterns) + "\n"
        err = oracle.check(lang, src)
        if err is None:
            return patterns
        off_b, typ = err
        off = len(src.encode()[:off_b].decode(errors="ignore"))
        # which pattern
        start = 0
        idx = None
        for i, p in enumerate(patterns):
            if start <= off < start + len(p) + 2:
                idx = i
                break
            start += len(p) + 2
        if typ == -1 or idx is None:
            # no usable offset: compile each pattern alone
            bad = [i for i, p in enumerate(patterns) if oracle.check(lang, p + "\n") is not None]
            if not bad:
                sys.exit("%s: %s but every pattern compiles alone" % (where, ERRORS.get(typ, typ)))
            for i in reversed(bad):
                notes.append("%s: dropped pattern (%s against our grammar): %s" % (where, ERRORS.get(typ, typ), one_line(patterns[i])))
                del patterns[i]
            continue
        p = patterns[idx]
        rel = off - start
        what = src[off:off + 40].split("\n")[0]
        # the innermost [...] element containing the offset
        tree = parse(p)
        best = None
        for n in tree.walk():
            if n.kind == "[" and n.start <= rel < n.end:
                els = items(n)
                for s, e, a in els:
                    if s <= rel < e and len(els) > 1:
                        if best is None or (e - s) < (best[1] - best[0]):
                            best = (s, e)
        if best and typ in (2, 3):
            s, e = best
            notes.append("%s: removed alternative %s (%s against our grammar)" % (where, one_line(p[s:e], 80), ERRORS[typ]))
            patterns[idx] = p[:s] + p[e:]
        else:
            notes.append("%s: dropped pattern (%s at %r against our grammar): %s" % (where, ERRORS.get(typ, typ), what, one_line(p)))
            del patterns[idx]


# ----------------------------------------------------------------------------------------- main --

def expand(sources, ref, files, seen=()):
    """The text of [ref] with `; inherits:` lines replaced by the inherited files, recursively."""
    data, where = sources.raw(ref)
    if data is None:
        if not seen:
            sys.exit("%s: not found (%s)" % (ref, where))
        return ""  # an inherited language without this query kind: Helix and nvim skip it too
    files.append((ref, sha256(data), where))
    text = data.decode("utf-8")
    kind, _, rest = ref.partition(":")
    d, _, fname = rest.rpartition("/")

    def inherit(m):
        out = []
        for lang in [x.strip() for x in m.group(1).split(",") if x.strip()]:
            r = "%s:%s/%s" % (kind, lang, fname)
            if r in seen:
                sys.exit("inherits cycle: %s" % r)
            out.append(expand(sources, r, files, seen + (ref,)))
        return "\n".join(out)

    if kind == "npm":
        return text  # an npm package's files are composed by the lock itself, as its tree-sitter.json does
    return re.sub(r"^;+ *inherits *:? *([A-Za-z0-9_,. -]+?) *$", inherit, text, flags=re.M)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pin", action="store_true", help="record the sha256 of every source file in the lock")
    ap.add_argument("--lib", default=os.path.join(HERE, "build/natives/macos-arm64/lib/libsupermux_syntax_jni.dylib"))
    ap.add_argument("--gen", default=os.path.join(HERE, "build/gen"))
    ap.add_argument("--only", help="comma-separated languages")
    args = ap.parse_args()
    lock = json.load(open(LOCK))
    sources = Sources(lock)
    oracle = Oracle(args.lib, args.gen)
    only = set(args.only.split(",")) if args.only else None
    notes = dict(lock.get("notes", {}))
    stats = []
    for lang, kinds in sorted(lock["languages"].items()):
        if only and lang not in only:
            continue
        oracle.ensure(lang)
        for kind in KINDS:
            entry = kinds.get(kind)
            key = "%s/%s" % (lang, kind)
            path = os.path.join(OUT, lang, kind + ".scm")
            if not entry:
                notes.pop(key, None)
                if os.path.exists(path):
                    os.remove(path)
                continue
            files, knotes, patterns, header = [], [], [], []
            for item in entry["sources"]:
                # a ref, or {"ref": ..., "precedence": ...} for a file whose convention is not its source's
                ref = item if isinstance(item, str) else item["ref"]
                src_kind = ref.partition(":")[0]
                precedence = lock["sources"][src_kind]["precedence"] if isinstance(item, str) else \
                    item.get("precedence", lock["sources"][src_kind]["precedence"])
                before = len(files)
                text = expand(sources, ref, files)
                pats = rewrite(text, knotes, "%s (%s)" % (key, ref),
                               lock.get("unavailableInjectionLanguages", []) if kind == "injections" else ())
                if kind == "highlights" and precedence == "auto":
                    precedence, why = convention(pats)
                    if precedence == "first":
                        knotes.append("%s (%s): written earlier-pattern-wins (%s): patterns reversed" % (key, ref, why))
                reverse = kind == "highlights" and precedence == "first"
                if reverse:
                    pats.reverse()
                header.append((files[before:], reverse))
                patterns += pats
            got = {r: h for r, h, _ in files}
            if args.pin:
                entry["files"] = got
            elif entry.get("files") != got:
                sys.exit("%s: source files differ from the lock (rerun with --pin and review):\n  lock %s\n  got  %s"
                         % (key, entry.get("files"), got))
            n0 = len(patterns)
            patterns = fit(oracle, lang, patterns, knotes, key)
            if not patterns:
                knotes.append("%s: nothing left, no file shipped" % key)
            if knotes:
                notes[key] = knotes
            else:
                notes.pop(key, None)
            if not patterns:
                if os.path.exists(path):
                    os.remove(path)
                stats.append("%-18s %-10s    - (every pattern dropped)" % (lang, kind))
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write("; editor-syntax %s query for %s. GENERATED by tools/fetch-queries.py from\n" % (kind, lang))
                f.write("; native/queries.lock.json: do not edit. Sources, in order:\n")
                for fs, reverse in header:
                    for r, h, where in fs:
                        f.write(";   %s\n" % where)
                    if reverse:
                        f.write(";   (patterns reversed: written for EARLIER-pattern-wins; this file gives the LATER pattern precedence)\n")
                f.write("; Each file keeps its source's licence. Rewritten and dropped patterns: the lock's notes[%s].\n\n" % json.dumps(key))
                f.write("\n\n".join(patterns) + "\n")
            stats.append("%-18s %-10s %4d patterns (%d dropped or changed)" % (lang, kind, len(patterns), len(knotes)))
    # The repositories' licence texts, for THIRD-PARTY-NOTICES.md (tools/make-notices.py reads them here).
    for kind, src in lock["sources"].items():
        if "repo" not in src:
            continue
        url = "https://raw.githubusercontent.com/%s/%s/LICENSE" % (src["repo"], src["commit"])
        data = fetch(url, "%s-%s-LICENSE" % (kind, src["commit"][:12]))
        if data is None:
            sys.exit("%s: no LICENSE at %s" % (kind, url))
        if args.pin:
            src["licenseSha256"] = sha256(data)
        elif src.get("licenseSha256") != sha256(data):
            sys.exit("%s: LICENSE differs from the lock (rerun with --pin and review)" % kind)
    lock["notes"] = dict(sorted(notes.items()))
    with open(LOCK, "w") as f:
        json.dump(lock, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("\n".join(stats))


if __name__ == "__main__":
    main()
