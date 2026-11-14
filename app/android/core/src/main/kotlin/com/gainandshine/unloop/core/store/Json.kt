package com.gainandshine.unloop.core.store

/** Thrown for malformed JSON. */
class JsonException(message: String) : RuntimeException(message)

/**
 * Tiny JSON codec. Values: Map<String, Any?>, List<Any?>, String, Boolean, Long, Double, null.
 * Not streaming and not fast; backup files are small.
 */
object Json {
    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    quote(sb, e.key.toString()); sb.append(':'); put(sb, e.value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); put(sb, x) }
                sb.append(']')
            }
            else -> throw JsonException("unsupported type ${v::class}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    /** Parses [text]; throws [JsonException] on malformed input or trailing garbage. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (!p.eof()) throw JsonException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(m: String): Nothing = throw JsonException("$m at $i")
        private fun peek(): Char = if (eof()) fail("unexpected end") else s[i]

        fun value(): Any? {
            ws()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("bad literal")
            i += w.length
            return v
        }

        private fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number")
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = peek(); i++
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                when (val e = peek().also { i++ }) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val h = s.substring(i, minOf(i + 4, s.length))
                        sb.append(h.toIntOrNull(16)?.toChar() ?: fail("bad escape"))
                        i += 4
                    }
                    else -> fail("bad escape")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                when (peek().also { i++ }) { ',' -> Unit; ']' -> return out; else -> fail("expected , or ]") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                if (peek() != '"') fail("expected key")
                val k = str(); ws()
                if (peek().also { i++ } != ':') fail("expected :")
                out[k] = value(); ws()
                when (peek().also { i++ }) { ',' -> Unit; '}' -> return out; else -> fail("expected , or }") }
            }
        }
    }
}
