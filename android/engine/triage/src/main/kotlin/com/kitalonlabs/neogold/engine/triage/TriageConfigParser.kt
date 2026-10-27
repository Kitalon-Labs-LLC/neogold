package com.kitalonlabs.neogold.engine.triage

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Pure Kotlin JSON parser and semantic validator for triage configurations.
 * Zero external dependencies.
 */
object TriageConfigParser {

    /**
     * Parses and semantically validates a JSON string into a [TriageConfig].
     *
     * @param jsonString Raw JSON configuration text.
     * @return Validated [TriageConfig].
     * @throws IllegalArgumentException on syntax or semantic validation errors.
     */
    fun parse(jsonString: String): TriageConfig {
        val root = JsonParser.parse(jsonString) as? Map<*, *>
            ?: throw IllegalArgumentException("Triage config root must be a JSON object")

        val configVersion = root["configVersion"] as? String
            ?: throw IllegalArgumentException("Missing or invalid string 'configVersion'")
        require(configVersion.isNotBlank()) { "configVersion cannot be blank" }

        val guideline = root["guideline"] as? String
            ?: throw IllegalArgumentException("Missing or invalid string 'guideline'")
        require(guideline.isNotBlank()) { "guideline cannot be blank" }

        val units = root["units"] as? String
            ?: throw IllegalArgumentException("Missing or invalid string 'units'")
        require(units == "mg/dL" || units == "umol/L") { "units must be 'mg/dL' or 'umol/L'" }

        val triageEnabled = root["triageEnabled"] as? Boolean
            ?: throw IllegalArgumentException("Missing or invalid boolean 'triageEnabled'")

        val validUntilStr = root["validUntil"] as? String
            ?: throw IllegalArgumentException("Missing or invalid string 'validUntil'")
        val validUntil = try {
            LocalDate.parse(validUntilStr)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Malformed date 'validUntil': $validUntilStr", e)
        }

        // Clinical Sign-off
        val signoffMap = root["clinicalSignoff"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing or invalid object 'clinicalSignoff'")
        val statusStr = signoffMap["status"] as? String
            ?: throw IllegalArgumentException("Missing or invalid string 'clinicalSignoff.status'")
        val signoffStatus = when (statusStr) {
            "DRAFT" -> SignoffStatus.DRAFT
            "SIGNED" -> SignoffStatus.SIGNED
            else -> throw IllegalArgumentException("Unknown clinicalSignoff.status: '$statusStr'")
        }
        val recordSha = signoffMap["signoffRecordSha256"] as? String
        if (recordSha != null) {
            require(recordSha.matches(Regex("^[a-f0-9]{64}$"))) {
                "signoffRecordSha256 must be a 64-character lowercase hex SHA-256 hash"
            }
        }

        // Scope
        val scopeMap = root["scope"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing or invalid object 'scope'")
        val minGA = (scopeMap["minGestationalAgeWeeks"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing or invalid number 'scope.minGestationalAgeWeeks'")
        require(minGA == 35) { "scope.minGestationalAgeWeeks must be 35" }
        val maxAgeHours = (scopeMap["maxAgeHours"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing or invalid number 'scope.maxAgeHours'")
        require(maxAgeHours == 336.0) { "scope.maxAgeHours must be 336.0" }
        val scope = ScopeConfig(minGestationalAgeWeeks = minGA, maxAgeHours = maxAgeHours)

        // Hard Rules
        val rulesList = root["hardRules"] as? List<*>
            ?: throw IllegalArgumentException("Missing or invalid array 'hardRules'")
        require(rulesList.size == 5) { "hardRules must contain exactly 5 rules R1 to R5" }
        val expectedRuleIds = listOf("R1", "R2", "R3", "R4", "R5")
        val hardRules = rulesList.mapIndexed { index, item ->
            val ruleMap = item as? Map<*, *>
                ?: throw IllegalArgumentException("hardRules[$index] must be an object")
            val id = ruleMap["id"] as? String ?: throw IllegalArgumentException("hardRules[$index] missing id")
            require(id == expectedRuleIds[index]) { "Expected rule ${expectedRuleIds[index]}, found $id" }
            val whenCond = ruleMap["when"] as? String ?: throw IllegalArgumentException("Rule $id missing 'when'")
            val actionStr = ruleMap["action"] as? String ?: throw IllegalArgumentException("Rule $id missing 'action'")
            val action = try {
                TriageAction.valueOf(actionStr)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Rule $id invalid action '$actionStr'")
            }
            HardRuleConfig(id, whenCond, action)
        }

        // Phototherapy Thresholds
        val thresholdsMap = root["phototherapyThresholds"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing or invalid object 'phototherapyThresholds'")
        val requiredBands = listOf("ga35", "ga36", "ga38plus")
        for (b in requiredBands) {
            require(thresholdsMap.containsKey(b)) { "phototherapyThresholds missing required band '$b'" }
        }

        val nonMonotonic = mutableListOf<String>()
        val parsedThresholds = thresholdsMap.mapNotNull { (bandKey, tableRaw) ->
            val key = bandKey as? String ?: return@mapNotNull null
            val rawList = tableRaw as? List<*>
                ?: throw IllegalArgumentException("Band '$key' threshold table must be an array")
            require(rawList.size >= 2) { "Band '$key' must contain at least 2 coordinate points" }

            var prevAge = -1.0
            var isMonotonic = true
            var prevThreshold = -1.0

            val points = rawList.mapIndexed { idx, pt ->
                val pairList = pt as? List<*>
                    ?: throw IllegalArgumentException("Band '$key' point $idx must be a [age, threshold] pair")
                require(pairList.size == 2) { "Band '$key' point $idx must have exactly 2 elements" }
                val age = (pairList[0] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Band '$key' point $idx age must be a number")
                val thresh = (pairList[1] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Band '$key' point $idx threshold must be a number")
                require(age in 0.0..336.0) { "Band '$key' age $age out of range [0, 336]" }
                require(thresh >= 0.0) { "Band '$key' threshold $thresh must be non-negative" }
                require(
                    age > prevAge
                ) { "Band '$key' points must be in strictly ascending age order ($age <= $prevAge)" }

                if (prevThreshold >= 0.0 && thresh < prevThreshold) {
                    isMonotonic = false
                }
                prevAge = age
                prevThreshold = thresh
                Pair(age, thresh)
            }

            require(points.first().first == 0.0) { "Band '$key' threshold table must start at age 0.0 h" }
            require(points.last().first >= 336.0 || points.any { it.first == 336.0 }) {
                "Band '$key' threshold table must cover up to 336.0 h"
            }

            if (!isMonotonic) {
                nonMonotonic.add(key)
            }

            key to points
        }.toMap()

        // Blood Test Zone
        val bzMap = root["bloodTestZone"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing or invalid object 'bloodTestZone'")
        val bzWithin = (bzMap["withinOfThreshold"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing or invalid number 'bloodTestZone.withinOfThreshold'")
        require(bzWithin > 0.0) { "bloodTestZone.withinOfThreshold must be positive" }
        val bzAbs = (bzMap["absoluteAtOrAbove"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing or invalid number 'bloodTestZone.absoluteAtOrAbove'")
        require(bzAbs > 0.0) { "bloodTestZone.absoluteAtOrAbove must be positive" }
        val bloodTestZone = BloodTestZoneConfig(bzWithin, bzAbs)

        // Recheck
        val rcMap = root["recheck"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing or invalid object 'recheck'")
        val rcWithin = (rcMap["withinOfThreshold"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing or invalid number 'recheck.withinOfThreshold'")
        require(rcWithin > 0.0) { "recheck.withinOfThreshold must be positive" }
        val rcHours = (rcMap["hours"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing or invalid integer 'recheck.hours'")
        require(rcHours > 0) { "recheck.hours must be positive" }
        val recheck = RecheckConfig(rcWithin, rcHours)

        // Max Interval Width
        val maxWidth = (root["maxIntervalWidth"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing or invalid number 'maxIntervalWidth'")
        require(maxWidth > 0.0) { "maxIntervalWidth must be positive" }

        // Quality Thresholds
        val quality = (root["qualityThresholds"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()

        return TriageConfig(
            configVersion = configVersion,
            guideline = guideline,
            units = units,
            triageEnabled = triageEnabled,
            validUntil = validUntil,
            clinicalSignoff = ClinicalSignoff(signoffStatus, recordSha),
            scope = scope,
            hardRules = hardRules,
            phototherapyThresholds = parsedThresholds,
            bloodTestZone = bloodTestZone,
            recheck = recheck,
            maxIntervalWidth = maxWidth,
            qualityThresholds = quality,
            nonMonotonicBands = nonMonotonic
        )
    }

    /**
     * Minimal JSON tokenizer and recursive descent parser.
     */
    internal object JsonParser {
        fun parse(text: String): Any? {
            val tokenizer = Tokenizer(text)
            val result = parseValue(tokenizer)
            tokenizer.skipWhitespace()
            require(tokenizer.isEof()) { "Trailing characters after JSON payload" }
            return result
        }

        private fun parseValue(tokenizer: Tokenizer): Any? {
            tokenizer.skipWhitespace()
            return when (val ch = tokenizer.peek()) {
                '{' -> parseObject(tokenizer)
                '[' -> parseArray(tokenizer)
                '"' -> parseString(tokenizer)
                't', 'f' -> parseBoolean(tokenizer)
                'n' -> parseNull(tokenizer)
                in '0'..'9', '-' -> parseNumber(tokenizer)
                else -> throw IllegalArgumentException("Unexpected character '$ch' at position ${tokenizer.pos}")
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
                if (c == '"') {
                    return sb.toString()
                }
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
                        else -> throw IllegalArgumentException("Invalid escape sequence: \\$esc")
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
            while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') {
                tokenizer.next()
            }
            var isFloat = false
            if (!tokenizer.isEof() && tokenizer.peek() == '.') {
                isFloat = true
                tokenizer.next()
                while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') {
                    tokenizer.next()
                }
            }
            if (!tokenizer.isEof() && (tokenizer.peek() == 'e' || tokenizer.peek() == 'E')) {
                isFloat = true
                tokenizer.next()
                if (!tokenizer.isEof() && (tokenizer.peek() == '+' || tokenizer.peek() == '-')) {
                    tokenizer.next()
                }
                while (!tokenizer.isEof() && tokenizer.peek() in '0'..'9') {
                    tokenizer.next()
                }
            }
            val numStr = tokenizer.text.substring(start, tokenizer.pos)
            return if (isFloat) numStr.toDouble() else numStr.toLong()
        }

        private fun parseBoolean(tokenizer: Tokenizer): Boolean {
            if (tokenizer.match("true")) return true
            if (tokenizer.match("false")) return false
            throw IllegalArgumentException("Expected boolean at position ${tokenizer.pos}")
        }

        private fun parseNull(tokenizer: Tokenizer): Any? {
            if (tokenizer.match("null")) return null
            throw IllegalArgumentException("Expected null at position ${tokenizer.pos}")
        }

        private class Tokenizer(val text: String) {
            var pos = 0
            fun isEof(): Boolean = pos >= text.length
            fun peek(): Char = if (isEof()) throw IllegalArgumentException("Unexpected EOF") else text[pos]
            fun next(): Char = if (isEof()) throw IllegalArgumentException("Unexpected EOF") else text[pos++]
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
                while (!isEof() && text[pos] in " \t\r\n") {
                    pos++
                }
            }
        }
    }
}
