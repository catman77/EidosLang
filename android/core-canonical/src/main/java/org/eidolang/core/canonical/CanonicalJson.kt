package org.eidolang.core.canonical

object CanonicalJson {
    fun string(s: String): String = buildString {
        append('"')
        s.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun obj(fields: Map<String, String>): String = fields.toSortedMap().entries.joinToString(prefix="{", postfix="}", separator=",") { (k,v) -> string(k)+":"+v }
    fun arr(items: Iterable<String>): String = items.joinToString(prefix="[", postfix="]", separator=",")
    fun int(v: Int): String = v.toString()
    fun long(v: Long): String = v.toString()
    fun nullableString(v: String?): String = if (v == null) "null" else string(v)
}
