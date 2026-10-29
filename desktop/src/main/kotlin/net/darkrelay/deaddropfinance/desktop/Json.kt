package net.darkrelay.deaddropfinance.desktop

/**
 * A small strict JSON reader for GitHub's release list: objects become Map, arrays List, numbers Double or Long,
 * plus String, Boolean and null. No library, so the program ships nothing extra for one answer it reads.
 */
object Json {

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw ParseException("nested too deep")
            if (i >= s.length) throw ParseException("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ParseException("bad token at $i")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseException("expected a key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw ParseException("expected : at $i")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw ParseException("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw ParseException("expected , or } at ${i - 1}")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l += value(depth + 1)
                ws()
                if (i >= s.length) throw ParseException("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw ParseException("expected , or ] at ${i - 1}")
                }
            }
        }

        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw ParseException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw ParseException("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("bad \\u escape")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseException("bad \\u escape"))
                                i += 4
                            }
                            else -> throw ParseException("bad escape")
                        }
                    }
                    c < ' ' -> throw ParseException("control character in a string")
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") throw ParseException("bad value at $start")
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseException("bad number at $start")
        }
    }
}
