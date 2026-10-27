package com.kitalonlabs.neogold.engine.cardtrust

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Public key certified in trust-list.json.
 */
data class TrustKey(
    val keyId: String,
    val purpose: KeyPurpose,
    val algorithm: String,
    val publicKeyHex: String,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val description: String,
    val testOnly: Boolean = false
) {
    val publicKeyBytes: ByteArray by lazy {
        hexToBytes(publicKeyHex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 64) { "Ed25519 public key hex must be 64 characters, got ${hex.length}" }
        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high != -1 && low != -1) { "Invalid hex character in key $keyId" }
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }
}

/**
 * Root trust configuration shipped with the application (config/trust-list.json).
 */
data class TrustList(
    val schemaVersion: Int,
    val version: Int,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val keys: List<TrustKey>,
    val revokedCardBatches: List<String>,
    val revokedStudyCodes: List<String>
)

/**
 * Pure Kotlin parser for trust-list.json with zero external dependencies.
 */
object TrustListParser {

    @Suppress("ThrowsCount") // one error per malformed field
    fun parse(jsonString: String): TrustList {
        val root = JsonParser.parse(jsonString) as? Map<*, *>
            ?: throw IllegalArgumentException("Trust list root must be a JSON object")

        val schemaVersion = (root["schema_version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing or invalid 'schema_version'")
        require(schemaVersion == 1) { "Unsupported schema_version: $schemaVersion" }

        val version = (root["version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing or invalid 'version'")

        val validFromStr = root["valid_from"] as? String
            ?: throw IllegalArgumentException("Missing or invalid 'valid_from'")
        val validFrom = try {
            LocalDate.parse(validFromStr)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Malformed 'valid_from' date: $validFromStr", e)
        }

        val validUntilStr = root["valid_until"] as? String
            ?: throw IllegalArgumentException("Missing or invalid 'valid_until'")
        val validUntil = try {
            LocalDate.parse(validUntilStr)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Malformed 'valid_until' date: $validUntilStr", e)
        }

        val keysRaw = root["keys"] as? List<*>
            ?: throw IllegalArgumentException("Missing or invalid 'keys' array")

        val keys = keysRaw.mapIndexed { idx, kObj ->
            val kMap = kObj as? Map<*, *>
                ?: throw IllegalArgumentException("Key at index $idx is not an object")

            val keyId = kMap["key_id"] as? String
                ?: throw IllegalArgumentException("Missing 'key_id' at index $idx")
            require(keyId.matches(Regex("^[A-Za-z0-9_-]{3,32}$"))) {
                "Invalid key_id pattern at index $idx: '$keyId'"
            }

            val purposeStr = kMap["purpose"] as? String
                ?: throw IllegalArgumentException("Missing 'purpose' in key $keyId")
            val purpose = when (purposeStr) {
                "CARD_ISSUER" -> KeyPurpose.CARD_ISSUER
                "STUDY_ISSUER" -> KeyPurpose.STUDY_ISSUER
                else -> throw IllegalArgumentException("Unsupported purpose '$purposeStr' in key $keyId")
            }

            val algorithm = kMap["algorithm"] as? String
                ?: throw IllegalArgumentException("Missing 'algorithm' in key $keyId")
            require(algorithm == "Ed25519") { "Unsupported algorithm '$algorithm' in key $keyId" }

            val pubHex = kMap["public_key_hex"] as? String
                ?: throw IllegalArgumentException("Missing 'public_key_hex' in key $keyId")
            require(pubHex.matches(Regex("^[a-f0-9]{64}$"))) {
                "Invalid public_key_hex format in key $keyId"
            }

            val kFromStr = kMap["valid_from"] as? String
                ?: throw IllegalArgumentException("Missing 'valid_from' in key $keyId")
            val kFrom = try {
                LocalDate.parse(kFromStr)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Malformed 'valid_from' in key $keyId: $kFromStr", e)
            }

            val kUntilStr = kMap["valid_until"] as? String
                ?: throw IllegalArgumentException("Missing 'valid_until' in key $keyId")
            val kUntil = try {
                LocalDate.parse(kUntilStr)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Malformed 'valid_until' in key $keyId: $kUntilStr", e)
            }

            val description = kMap["description"] as? String ?: ""
            val testOnly = (kMap["test_only"] as? Boolean) ?: false

            TrustKey(
                keyId = keyId,
                purpose = purpose,
                algorithm = algorithm,
                publicKeyHex = pubHex,
                validFrom = kFrom,
                validUntil = kUntil,
                description = description,
                testOnly = testOnly
            )
        }

        val revokedBatches = (root["revoked_card_batches"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()

        val revokedStudies = (root["revoked_study_codes"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()

        return TrustList(
            schemaVersion = schemaVersion,
            version = version,
            validFrom = validFrom,
            validUntil = validUntil,
            keys = keys,
            revokedCardBatches = revokedBatches,
            revokedStudyCodes = revokedStudies
        )
    }

    private object JsonParser {
        fun parse(json: String): Any? {
            val tokenizer = Tokenizer(json.trim())
            val result = parseValue(tokenizer)
            tokenizer.skipWhitespace()
            require(tokenizer.isEof()) { "Trailing data after JSON root at ${tokenizer.pos}" }
            return result
        }

        private fun parseValue(tokenizer: Tokenizer): Any? {
            tokenizer.skipWhitespace()
            require(!tokenizer.isEof()) { "Unexpected EOF" }
            return when (val ch = tokenizer.peek()) {
                '{' -> parseObject(tokenizer)
                '[' -> parseArray(tokenizer)
                '"' -> parseString(tokenizer)
                't', 'f' -> parseBoolean(tokenizer)
                'n' -> parseNull(tokenizer)
                '-', in '0'..'9' -> parseNumber(tokenizer)
                else -> throw IllegalArgumentException("Unexpected character '$ch' at ${tokenizer.pos}")
            }
        }

        private fun parseObject(tokenizer: Tokenizer): Map<String, Any?> {
            tokenizer.consume('{')
            val map = mutableMapOf<String, Any?>()
            tokenizer.skipWhitespace()
            if (tokenizer.peek() == '}') {
                tokenizer.consume('}')
                return map
            }
            while (true) {
                tokenizer.skipWhitespace()
                val key = parseString(tokenizer)
                tokenizer.skipWhitespace()
                tokenizer.consume(':')
                val value = parseValue(tokenizer)
                map[key] = value
                tokenizer.skipWhitespace()
                val next = tokenizer.peek()
                if (next == '}') {
                    tokenizer.consume('}')
                    break
                }
                tokenizer.consume(',')
            }
            return map
        }

        private fun parseArray(tokenizer: Tokenizer): List<Any?> {
            tokenizer.consume('[')
            val list = mutableListOf<Any?>()
            tokenizer.skipWhitespace()
            if (tokenizer.peek() == ']') {
                tokenizer.consume(']')
                return list
            }
            while (true) {
                val value = parseValue(tokenizer)
                list.add(value)
                tokenizer.skipWhitespace()
                val next = tokenizer.peek()
                if (next == ']') {
                    tokenizer.consume(']')
                    break
                }
                tokenizer.consume(',')
            }
            return list
        }

        private fun parseString(tokenizer: Tokenizer): String {
            tokenizer.consume('"')
            val sb = StringBuilder()
            while (!tokenizer.isEof()) {
                val c = tokenizer.next()
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    when (val esc = tokenizer.next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = (1..4).map { tokenizer.next() }.joinToString("")
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("Invalid escape: \\$esc")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(tokenizer: Tokenizer): Number {
            val start = tokenizer.pos
            if (tokenizer.peek() == '-') tokenizer.next()
            while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') tokenizer.next()
            var isFloat = false
            if (!tokenizer.isEof() && tokenizer.peek() == '.') {
                isFloat = true
                tokenizer.next()
                while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') tokenizer.next()
            }
            if (!tokenizer.isEof() && (tokenizer.peek() == 'e' || tokenizer.peek() == 'E')) {
                isFloat = true
                tokenizer.next()
                if (!tokenizer.isEof() && (tokenizer.peek() == '+' || tokenizer.peek() == '-')) tokenizer.next()
                while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') tokenizer.next()
            }
            val numStr = tokenizer.text.substring(start, tokenizer.pos)
            return if (isFloat) numStr.toDouble() else numStr.toLong()
        }

        private fun parseBoolean(tokenizer: Tokenizer): Boolean {
            if (tokenizer.match("true")) return true
            if (tokenizer.match("false")) return false
            throw IllegalArgumentException("Expected boolean at ${tokenizer.pos}")
        }

        private fun parseNull(tokenizer: Tokenizer): Any? {
            if (tokenizer.match("null")) return null
            throw IllegalArgumentException("Expected null at ${tokenizer.pos}")
        }

        private class Tokenizer(val text: String) {
            var pos = 0
            fun isEof(): Boolean = pos >= text.length
            fun peek(): Char {
                require(!isEof()) { "Unexpected EOF" }
                return text[pos]
            }
            fun next(): Char {
                require(!isEof()) { "Unexpected EOF" }
                return text[pos++]
            }
            fun consume(expected: Char) {
                val ch = next()
                require(ch == expected) { "Expected '$expected' but found '$ch' at $pos" }
            }
            fun match(expected: String): Boolean {
                if (text.startsWith(expected, pos)) {
                    pos += expected.length
                    return true
                }
                return false
            }
            fun skipWhitespace() {
                while (!isEof() && text[pos] in " \t\r\n") pos++
            }
        }
    }
}
