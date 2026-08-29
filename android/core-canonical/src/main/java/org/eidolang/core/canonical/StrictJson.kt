package org.eidolang.core.canonical

sealed interface JValue {
    data class Obj(val fields: LinkedHashMap<String, JValue>) : JValue
    data class Arr(val items: List<JValue>) : JValue
    data class Str(val value: String) : JValue
    data class IntNum(val value: Long) : JValue
    data object Null : JValue
}

class StrictJsonParser(private val text: String) {
    private var i = 0

    fun parse(): JValue {
        val v = value()
        require(i == text.length) { "Trailing bytes after JSON value at offset $i" }
        return v
    }

    private fun value(): JValue {
        require(i < text.length) { "Unexpected EOF" }
        return when (text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JValue.Str(string())
            'n' -> {
                expect("null")
                JValue.Null
            }
            '-', in '0'..'9' -> JValue.IntNum(integer())
            else -> error("Unsupported JSON token '${text[i]}' at offset $i")
        }
    }

    private fun obj(): JValue.Obj {
        expectChar('{')
        val out = linkedMapOf<String, JValue>()
        if (peek('}')) {
            i++
            return JValue.Obj(out)
        }
        while (true) {
            require(peek('"')) { "Object key must be a string at offset $i" }
            val key = string()
            require(key !in out) { "Duplicate key '$key'" }
            expectChar(':')
            out[key] = value()
            if (peek('}')) {
                i++
                return JValue.Obj(out)
            }
            expectChar(',')
        }
    }

    private fun arr(): JValue.Arr {
        expectChar('[')
        val out = mutableListOf<JValue>()
        if (peek(']')) {
            i++
            return JValue.Arr(out)
        }
        while (true) {
            out += value()
            if (peek(']')) {
                i++
                return JValue.Arr(out)
            }
            expectChar(',')
        }
    }

    private fun string(): String {
        expectChar('"')
        val out = StringBuilder()
        while (true) {
            require(i < text.length) { "Unterminated string" }
            val c = text[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    require(i < text.length) { "Bad escape at EOF" }
                    when (val e = text[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(i + 4 <= text.length) { "Short unicode escape" }
                            val hex = text.substring(i, i + 4)
                            require(hex.all { it in "0123456789abcdefABCDEF" }) { "Bad unicode escape" }
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> error("Bad escape \\$e")
                    }
                }
                else -> {
                    require(c.code >= 0x20) { "Control character in string" }
                    out.append(c)
                }
            }
        }
    }

    private fun integer(): Long {
        val start = i
        if (peek('-')) i++
        require(i < text.length) { "Bad integer" }
        if (peek('0')) {
            i++
        } else {
            require(text[i] in '1'..'9') { "Bad integer at offset $i" }
            while (i < text.length && text[i].isDigit()) i++
        }
        require(i >= text.length || (text[i] != '.' && text[i] != 'e' && text[i] != 'E')) {
            "Floating point / exponent numbers are forbidden"
        }
        return text.substring(start, i).toLong()
    }

    private fun expect(lit: String) {
        require(text.startsWith(lit, i)) { "Expected '$lit' at offset $i" }
        i += lit.length
    }

    private fun expectChar(c: Char) {
        require(i < text.length && text[i] == c) { "Expected '$c' at offset $i" }
        i++
    }

    private fun peek(c: Char): Boolean = i < text.length && text[i] == c
}
