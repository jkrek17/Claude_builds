package com.compositioncoach.app.di

/**
 * The smallest JSON the photo journal needs, hand-written so the app takes no new dependency: one flat
 * object per line whose values are strings, numbers, booleans, null, or arrays of strings. [parseObject]
 * returns null for anything it does not understand (a torn last line after a crash, a future format), so a
 * bad line is skipped rather than failing the whole journal.
 */
internal object MiniJson {

    /** Encodes a flat object; values must be String, Number, Boolean, null or List<String>. Key order is kept. */
    fun encode(fields: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) append(',')
            first = false
            appendString(key)
            append(':')
            appendValue(value)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Boolean -> append(value.toString())
            is Float -> append(if (value.isFinite()) value.toString() else "null")
            is Double -> append(if (value.isFinite()) value.toString() else "null")
            is Number -> append(value.toString())
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> appendString(value.toString())
        }
    }

    private fun StringBuilder.appendString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    /** Parses one flat object; numbers come back as Long when integral, else Double. Null on any syntax error. */
    fun parseObject(text: String): Map<String, Any?>? = try {
        Parser(text).run {
            val result = readObject()
            skipWhitespace()
            if (pos != text.length) null else result
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(pos < s.length && s[pos] == c) { "expected $c at $pos" }
            pos++
        }

        fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("bad object at $pos")
                }
            }
        }

        private fun readValue(): Any? {
            skipWhitespace()
            return when (val c = s[pos]) {
                '"' -> readString()
                '[' -> readArray()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("bad value at $pos")
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += readValue()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("bad array at $pos")
                }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $text")
        }

        private fun readString(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
