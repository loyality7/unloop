package com.gainandshine.unloop.core.fixture

/**
 * Minimal JSON for fixtures. Values: null, Boolean, Long, Double, String, List, Map<String, Any?>.
 * No external dependency on purpose (the core stays dependency-free).
 */
internal object Json {
    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is String -> quote(v, sb)
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(e, sb)
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, e) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(e, sb)
                }
                sb.append('}')
            }
            else -> error("unsupported json value ${v::class.simpleName}")
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(HEX_RADIX).padStart(UNICODE_DIGITS, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        require(p.atEnd()) { "trailing json at ${p.pos}" }
        return v
    }

    private const val HEX_RADIX = 16
    private const val UNICODE_DIGITS = 4

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }

        fun value(): Any? {
            ws()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("bad json char at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun num(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                when (val e = s[pos++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(s.substring(pos, pos + UNICODE_DIGITS).toInt(HEX_RADIX).toChar())
                        pos += UNICODE_DIGITS
                    }
                    else -> sb.append(e)
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            ws()
            if (s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += value()
                ws()
                if (s[pos] == ',') pos++ else break
            }
            expect(']')
            return out
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                ws()
                val k = str()
                ws()
                expect(':')
                out[k] = value()
                ws()
                if (s[pos] == ',') pos++ else break
            }
            expect('}')
            return out
        }
    }
}
