package dev.supermux.editor.syntax

/** A tree-sitter query's tokens, enough for tests to read predicates and captures. */
internal object QueryText {
    sealed class Tok {
        data class Open(val c: Char) : Tok()
        data class Close(val c: Char) : Tok()
        data class Str(val value: String) : Tok()
        data class Pred(val name: String) : Tok()
        data class Capture(val name: String) : Tok()
        data class Word(val text: String) : Tok()
    }

    fun tokens(q: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        fun word(start: Int): String {
            var j = start
            while (j < q.length && !q[j].isWhitespace() && q[j] !in "()[]\";") j++
            return q.substring(start, j).also { i = j }
        }
        while (i < q.length) {
            val c = q[i]
            when {
                c.isWhitespace() -> i++
                c == ';' -> { while (i < q.length && q[i] != '\n') i++ }
                c == '(' || c == '[' -> { out += Tok.Open(c); i++ }
                c == ')' || c == ']' -> { out += Tok.Close(c); i++ }
                c == '"' -> {
                    // tree-sitter's escapes: \n \r \t \0, any other escaped char stands for itself
                    val sb = StringBuilder()
                    i++
                    while (i < q.length && q[i] != '"') {
                        if (q[i] == '\\' && i + 1 < q.length) {
                            i++
                            sb.append(when (q[i]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '0' -> '\u0000'; else -> q[i] })
                        } else sb.append(q[i])
                        i++
                    }
                    i++
                    out += Tok.Str(sb.toString())
                }
                c == '#' -> out += Tok.Pred(word(i + 1))
                c == '@' -> out += Tok.Capture(word(i + 1))
                else -> out += Tok.Word(word(i))
            }
        }
        return out
    }

    /** Every predicate or directive: name (without '#') and its arguments. */
    fun predicates(q: String): List<Pair<String, List<Tok>>> {
        val t = tokens(q)
        val out = ArrayList<Pair<String, List<Tok>>>()
        for (k in t.indices) {
            val p = t[k] as? Tok.Pred ?: continue
            val args = ArrayList<Tok>()
            var j = k + 1
            while (j < t.size && t[j] !is Tok.Close) args += t[j++]
            out += p.name to args
        }
        return out
    }

    /** The regex of every #match?-family predicate. */
    fun regexes(q: String): List<String> = predicates(q)
        .filter { it.first in setOf("match?", "not-match?", "any-match?", "any-not-match?") }
        .mapNotNull { (_, a) -> (a.getOrNull(1) as? Tok.Str)?.value }
}
