package com.jianyu.ds_pet.pet

internal sealed class JsonValue {
    class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue()
    class Arr(val items: List<JsonValue>) : JsonValue()
    class Str(val text: String) : JsonValue()
    class Num(val raw: String) : JsonValue()
    class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
}

internal fun JsonValue.Obj.str(key: String): String? = (fields[key] as? JsonValue.Str)?.text

internal fun JsonValue.Obj.bool(key: String): Boolean? = (fields[key] as? JsonValue.Bool)?.value

internal fun JsonValue.Obj.num(key: String): Double? = (fields[key] as? JsonValue.Num)?.raw?.toDoubleOrNull()

internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

internal fun JsonValue.Obj.arr(key: String): JsonValue.Arr? = fields[key] as? JsonValue.Arr

internal fun quoteJson(text: String): String {
    val sb = StringBuilder(text.length + 2)
    sb.append('"')
    for (ch in text) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

internal object JsonText {
    fun parse(source: String): JsonValue {
        val text = if (source.startsWith("\uFEFF")) source.substring(1) else source
        return Parser(text).parseRoot()
    }

    fun stringify(value: JsonValue): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(value.raw)
            is JsonValue.Str -> sb.append(quoteJson(value.text))
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var index = 0
                for ((key, item) in value.fields) {
                    if (index++ > 0) sb.append(',')
                    sb.append(quoteJson(key)).append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
        }
    }
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseRoot(): JsonValue {
        skip()
        val value = value()
        skip()
        if (i != s.length) fail("trailing")
        return value
    }

    private fun value(): JsonValue {
        skip()
        if (i >= s.length) fail("eof")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonValue.Str(str())
            't' -> lit("true", JsonValue.Bool(true))
            'f' -> lit("false", JsonValue.Bool(false))
            'n' -> lit("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') num() else fail("value")
        }
    }

    private fun obj(): JsonValue.Obj {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skip()
        if (peek('}')) {
            i++
            return JsonValue.Obj(map)
        }
        while (true) {
            skip()
            if (i >= s.length || s[i] != '"') fail("key")
            val key = str()
            skip()
            expect(':')
            map[key] = value()
            skip()
            when {
                peek(',') -> i++
                peek('}') -> {
                    i++
                    return JsonValue.Obj(map)
                }
                else -> fail("object")
            }
        }
    }

    private fun arr(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skip()
        if (peek(']')) {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += value()
            skip()
            when {
                peek(',') -> i++
                peek(']') -> {
                    i++
                    return JsonValue.Arr(items)
                }
                else -> fail("array")
            }
        }
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (i < s.length) {
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("escape")
                    when (val escaped = s[i++]) {
                        '"', '\\', '/' -> sb.append(escaped)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("unicode")
                            val hex = s.substring(i, i + 4)
                            i += 4
                            sb.append((hex.toIntOrNull(16) ?: fail("unicode")).toChar())
                        }
                        else -> fail("escape")
                    }
                }
                else -> sb.append(c)
            }
        }
        fail("string")
    }

    private fun num(): JsonValue.Num {
        val start = i
        if (peek('-')) i++
        if (i >= s.length || s[i] !in '0'..'9') fail("number")
        if (s[i] == '0') {
            i++
        } else {
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (peek('.')) {
            i++
            if (i >= s.length || s[i] !in '0'..'9') fail("number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') fail("number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        return JsonValue.Num(s.substring(start, i))
    }

    private fun lit(word: String, value: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) fail(word)
        i += word.length
        return value
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun peek(c: Char): Boolean = i < s.length && s[i] == c

    private fun expect(c: Char) {
        if (!peek(c)) fail("expected $c")
        i++
    }

    private fun fail(what: String): Nothing = throw IllegalArgumentException("json $what at $i")
}
