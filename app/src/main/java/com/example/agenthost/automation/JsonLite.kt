package com.example.agenthost.automation

sealed class JsonLiteValue {
    data class Obj(val fields: Map<String, JsonLiteValue>) : JsonLiteValue()
    data class Arr(val items: List<JsonLiteValue>) : JsonLiteValue()
    data class Str(val value: String) : JsonLiteValue()
    data class Num(val value: Double) : JsonLiteValue()
    data class Bool(val value: Boolean) : JsonLiteValue()
    data object Null : JsonLiteValue()
}

class JsonLiteParseException(message: String) : IllegalArgumentException(message)

object JsonLite {
    fun parse(text: String): JsonLiteValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEnd()) {
            throw JsonLiteParseException("Trailing content after JSON value")
        }
        return value
    }

    private class Parser(private val source: String) {
        private var index: Int = 0

        fun isEnd(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (!isEnd() && source[index].isWhitespace()) {
                index += 1
            }
        }

        fun parseValue(): JsonLiteValue {
            skipWhitespace()
            if (isEnd()) {
                throw JsonLiteParseException("Unexpected end of JSON")
            }
            return when (val ch = source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonLiteValue.Str(parseString())
                't' -> parseTrue()
                'f' -> parseFalse()
                'n' -> parseNull()
                '-', in '0'..'9' -> JsonLiteValue.Num(parseNumber())
                else -> throw JsonLiteParseException("Unexpected token '$ch' at position=$index")
            }
        }

        private fun parseObject(): JsonLiteValue.Obj {
            expect('{')
            skipWhitespace()
            val fields = linkedMapOf<String, JsonLiteValue>()
            if (peek('}')) {
                expect('}')
                return JsonLiteValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWhitespace()
                when {
                    peek(',') -> {
                        expect(',')
                    }

                    peek('}') -> {
                        expect('}')
                        return JsonLiteValue.Obj(fields)
                    }

                    else -> throw JsonLiteParseException("Expected ',' or '}' at position=$index")
                }
            }
        }

        private fun parseArray(): JsonLiteValue.Arr {
            expect('[')
            skipWhitespace()
            val items = mutableListOf<JsonLiteValue>()
            if (peek(']')) {
                expect(']')
                return JsonLiteValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> expect(',')
                    peek(']') -> {
                        expect(']')
                        return JsonLiteValue.Arr(items)
                    }

                    else -> throw JsonLiteParseException("Expected ',' or ']' at position=$index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (!isEnd()) {
                val ch = source[index++]
                if (ch == '"') {
                    return out.toString()
                }
                if (ch != '\\') {
                    out.append(ch)
                    continue
                }
                if (isEnd()) {
                    throw JsonLiteParseException("Unexpected end in escape sequence")
                }
                val escaped = source[index++]
                when (escaped) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> out.append(parseUnicode())
                    else -> throw JsonLiteParseException("Unsupported escape \\$escaped")
                }
            }
            throw JsonLiteParseException("Unterminated string")
        }

        private fun parseUnicode(): Char {
            if (index + 4 > source.length) {
                throw JsonLiteParseException("Invalid unicode escape at position=$index")
            }
            val hex = source.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar()
                ?: throw JsonLiteParseException("Invalid unicode hex=$hex")
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek('-')) {
                index += 1
            }
            consumeDigits()
            if (peek('.')) {
                index += 1
                consumeDigits(requireAtLeastOne = true)
            }
            if (peek('e') || peek('E')) {
                index += 1
                if (peek('+') || peek('-')) {
                    index += 1
                }
                consumeDigits(requireAtLeastOne = true)
            }
            val text = source.substring(start, index)
            return text.toDoubleOrNull()
                ?: throw JsonLiteParseException("Invalid number=$text")
        }

        private fun consumeDigits(requireAtLeastOne: Boolean = false) {
            val start = index
            while (!isEnd() && source[index].isDigit()) {
                index += 1
            }
            if (requireAtLeastOne && start == index) {
                throw JsonLiteParseException("Expected digit at position=$index")
            }
        }

        private fun parseTrue(): JsonLiteValue.Bool {
            expectKeyword("true")
            return JsonLiteValue.Bool(true)
        }

        private fun parseFalse(): JsonLiteValue.Bool {
            expectKeyword("false")
            return JsonLiteValue.Bool(false)
        }

        private fun parseNull(): JsonLiteValue {
            expectKeyword("null")
            return JsonLiteValue.Null
        }

        private fun expectKeyword(keyword: String) {
            if (source.regionMatches(index, keyword, 0, keyword.length)) {
                index += keyword.length
            } else {
                throw JsonLiteParseException("Expected '$keyword' at position=$index")
            }
        }

        private fun expect(expected: Char) {
            if (isEnd() || source[index] != expected) {
                throw JsonLiteParseException("Expected '$expected' at position=$index")
            }
            index += 1
        }

        private fun peek(expected: Char): Boolean {
            return !isEnd() && source[index] == expected
        }
    }
}

internal fun JsonLiteValue.asObjectOrNull(): JsonLiteValue.Obj? = this as? JsonLiteValue.Obj
internal fun JsonLiteValue.asArrayOrNull(): JsonLiteValue.Arr? = this as? JsonLiteValue.Arr

internal fun JsonLiteValue.Obj.has(key: String): Boolean = fields.containsKey(key)

internal fun JsonLiteValue.Obj.requireString(key: String): String {
    val value = fields[key] ?: throw JsonLiteParseException("missing field=$key")
    return (value as? JsonLiteValue.Str)?.value
        ?: throw JsonLiteParseException("field=$key must be string")
}

internal fun JsonLiteValue.Obj.optionalString(key: String, default: String = ""): String {
    val value = fields[key] ?: return default
    return (value as? JsonLiteValue.Str)?.value
        ?: throw JsonLiteParseException("field=$key must be string")
}

internal fun JsonLiteValue.Obj.requireInt(key: String): Int {
    val value = fields[key] ?: throw JsonLiteParseException("missing field=$key")
    val number = (value as? JsonLiteValue.Num)?.value
        ?: throw JsonLiteParseException("field=$key must be number")
    return number.toInt()
}

internal fun JsonLiteValue.Obj.optionalLong(key: String, default: Long): Long {
    val value = fields[key] ?: return default
    val number = (value as? JsonLiteValue.Num)?.value
        ?: throw JsonLiteParseException("field=$key must be number")
    return number.toLong()
}
