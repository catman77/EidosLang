package org.eidolang.core.archive

import java.io.ByteArrayOutputStream

sealed interface BValue {
    data class Bytes(val value: ByteArray) : BValue {
        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class IntVal(val value: Long) : BValue
    data class ListVal(val value: List<BValue>) : BValue
    data class Dict(val value: Map<ByteKey, BValue>) : BValue
}

data class ByteKey(val bytes: ByteArray) : Comparable<ByteKey> {
    override fun compareTo(other: ByteKey): Int {
        val n = minOf(bytes.size, other.bytes.size)
        for (i in 0 until n) {
            val a = bytes[i].toInt() and 0xff
            val b = other.bytes[i].toInt() and 0xff
            if (a != b) return a - b
        }
        return bytes.size - other.bytes.size
    }
    override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    fun utf8(): String = bytes.toString(Charsets.UTF_8)
    companion object { fun utf8(s: String) = ByteKey(s.toByteArray(Charsets.UTF_8)) }
}

object Bencode {
    fun bytes(v: ByteArray) = BValue.Bytes(v)
    fun text(v: String) = BValue.Bytes(v.toByteArray(Charsets.UTF_8))
    fun int(v: Long) = BValue.IntVal(v)
    fun list(vararg v: BValue) = BValue.ListVal(v.toList())
    fun dict(vararg fields: Pair<String, BValue>): BValue.Dict = BValue.Dict(fields.associate { ByteKey.utf8(it.first) to it.second })

    fun encode(v: BValue): ByteArray {
        val out = ByteArrayOutputStream()
        encodeTo(v, out)
        return out.toByteArray()
    }



    fun decode(bytes: ByteArray): BValue {
        class Parser {
            var i = 0
            fun value(): BValue {
                require(i < bytes.size) { "unexpected EOF" }
                return when (bytes[i].toInt().toChar()) {
                    'i' -> integer()
                    'l' -> list()
                    'd' -> dictValue()
                    in '0'..'9' -> byteString()
                    else -> error("invalid bencode token at $i")
                }
            }
            fun integer(): BValue.IntVal {
                i++
                val start = i
                while (i < bytes.size && bytes[i].toInt().toChar() != 'e') i++
                require(i < bytes.size)
                val text = bytes.copyOfRange(start, i).toString(Charsets.US_ASCII)
                i++
                require(text.matches(Regex("-?(0|[1-9][0-9]*)")) && text != "-0") { "noncanonical integer" }
                return BValue.IntVal(text.toLong())
            }
            fun byteString(): BValue.Bytes {
                val start = i
                while (i < bytes.size && bytes[i].toInt().toChar() != ':') {
                    require(bytes[i].toInt().toChar() in '0'..'9')
                    i++
                }
                require(i < bytes.size)
                val lenText = bytes.copyOfRange(start, i).toString(Charsets.US_ASCII)
                require(lenText.matches(Regex("0|[1-9][0-9]*"))) { "noncanonical string length" }
                val len = lenText.toInt()
                i++
                require(i + len <= bytes.size)
                val out = bytes.copyOfRange(i, i + len)
                i += len
                return BValue.Bytes(out)
            }
            fun list(): BValue.ListVal {
                i++
                val out = mutableListOf<BValue>()
                while (i < bytes.size && bytes[i].toInt().toChar() != 'e') out += value()
                require(i < bytes.size); i++
                return BValue.ListVal(out)
            }
            fun dictValue(): BValue.Dict {
                i++
                val out = linkedMapOf<ByteKey,BValue>()
                var last: ByteKey? = null
                while (i < bytes.size && bytes[i].toInt().toChar() != 'e') {
                    val keyBytes = byteString().value
                    val key = ByteKey(keyBytes)
                    require(last == null || last < key) { "dictionary keys not strictly sorted" }
                    require(key !in out)
                    out[key] = value()
                    last = key
                }
                require(i < bytes.size); i++
                return BValue.Dict(out)
            }
        }
        val p = Parser()
        val v = p.value()
        require(p.i == bytes.size) { "trailing bencode bytes" }
        require(encode(v).contentEquals(bytes)) { "noncanonical bencoding" }
        return v
    }

    private fun encodeTo(v: BValue, out: ByteArrayOutputStream) {
        when (v) {
            is BValue.Bytes -> {
                out.write(v.value.size.toString().toByteArray(Charsets.US_ASCII)); out.write(':'.code); out.write(v.value)
            }
            is BValue.IntVal -> {
                out.write('i'.code); out.write(v.value.toString().toByteArray(Charsets.US_ASCII)); out.write('e'.code)
            }
            is BValue.ListVal -> {
                out.write('l'.code); v.value.forEach { encodeTo(it, out) }; out.write('e'.code)
            }
            is BValue.Dict -> {
                out.write('d'.code)
                v.value.toSortedMap().forEach { (k, value) -> encodeTo(BValue.Bytes(k.bytes), out); encodeTo(value, out) }
                out.write('e'.code)
            }
        }
    }
}
