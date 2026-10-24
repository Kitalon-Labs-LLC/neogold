package com.kitalonlabs.neogold.engine.estimation

/**
 * Pure Kotlin parser and validator for model.json.
 * Zero external dependencies.
 */
object EstimationModelParser {

    private val SEMVER_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[a-z0-9.]+)?$")
    private val DATE_REGEX = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    /**
     * Parses and validates a JSON string into an [EstimationModel].
     *
     * @param json Raw model JSON text.
     * @return Validated [EstimationModel].
     * @throws EstimationException.InvalidModelException on syntax or semantic validation failure.
     */
    fun parse(json: String): EstimationModel {
        val root = try {
            SimpleJsonParser.parse(json) as? Map<*, *>
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Model root must be a JSON object"
                )
        } catch (e: EstimationException.InvalidModelException) {
            throw e
        } catch (e: Exception) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Failed to parse model JSON: ${e.message}",
                cause = e
            )
        }

        val modelVersion = parseString(root, "modelVersion", required = true)
        if (!modelVersion.matches(SEMVER_REGEX)) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.UNSUPPORTED_VERSION,
                message = "modelVersion '$modelVersion' is not a valid SemVer format"
            )
        }

        val minAppVersion = parseString(root, "minAppVersion", required = true)
        if (!minAppVersion.matches(SEMVER_REGEX)) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.UNSUPPORTED_VERSION,
                message = "minAppVersion '$minAppVersion' is not a valid SemVer format"
            )
        }

        val units = parseString(root, "units", required = true)
        if (units != "mg/dL") {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "units must be 'mg/dL' (found '$units')"
            )
        }

        val algorithm = parseString(root, "algorithm", required = true)
        if (algorithm != "ridge_regression_split_conformal") {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Unsupported algorithm '$algorithm'"
            )
        }

        val colorSpace = parseString(root, "colorSpace", required = true)
        if (colorSpace != "CIELAB_D65") {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Unsupported colorSpace '$colorSpace'"
            )
        }

        // Training Data Summary
        val summaryMap = root["trainingDataSummary"] as? Map<*, *>
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Missing or invalid object 'trainingDataSummary'"
            )
        val sampleCount = parseLong(summaryMap, "sampleCount", required = true).toInt()
        val participantCount = parseLong(summaryMap, "participantCount", required = true).toInt()
        val description = parseString(summaryMap, "description", required = true)
        val trainedDate = parseString(summaryMap, "trainedDate", required = true)
        if (!trainedDate.matches(DATE_REGEX)) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "trainedDate '$trainedDate' is not formatted as YYYY-MM-DD"
            )
        }
        val populations = parseStringList(summaryMap, "populations")
        val devices = parseStringList(summaryMap, "devices")
        val metricsMap = summaryMap["metrics"] as? Map<*, *>
        val metrics = mutableMapOf<String, Double>()
        if (metricsMap != null) {
            for ((k, v) in metricsMap) {
                if (k is String && v is Number) {
                    metrics[k] = v.toDouble()
                }
            }
        }
        val trainingDataSummary = TrainingDataSummary(
            sampleCount = sampleCount,
            participantCount = participantCount,
            description = description,
            trainedDate = trainedDate,
            populations = populations,
            devices = devices,
            metrics = metrics
        )

        // Feature list
        val featureList = parseStringList(root, "featureList")
        if (featureList.isEmpty()) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "featureList cannot be empty"
            )
        }
        if (featureList.size != featureList.toSet().size) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "featureList contains duplicate feature names"
            )
        }

        // Sites
        val sitesMap = root["sites"] as? Map<*, *>
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Missing or invalid object 'sites'"
            )

        val parsedSites = mutableMapOf<BodySite, SiteModel>()
        for ((siteKey, siteVal) in sitesMap) {
            if (siteKey !is String) continue
            val site = try {
                BodySite.fromString(siteKey)
            } catch (e: Exception) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Unknown site key in sites: '$siteKey'"
                )
            }
            val siteObj = siteVal as? Map<*, *>
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Site '$siteKey' definition must be a JSON object"
                )

            val means = parseDoubleList(siteObj, "standardizationMeans")
            val scales = parseDoubleList(siteObj, "standardizationScales")
            val coeffs = parseDoubleList(siteObj, "ridgeCoefficients")
            val intercept = parseDouble(siteObj, "intercept", required = true)
            val quantile = parseDouble(siteObj, "residualQuantile90", required = true)

            if (means.size != featureList.size) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.DIMENSION_MISMATCH,
                    message = "Site '$siteKey' standardizationMeans length (${means.size}) != featureList length (${featureList.size})"
                )
            }
            if (scales.size != featureList.size) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.DIMENSION_MISMATCH,
                    message = "Site '$siteKey' standardizationScales length (${scales.size}) != featureList length (${featureList.size})"
                )
            }
            if (coeffs.size != featureList.size) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.DIMENSION_MISMATCH,
                    message = "Site '$siteKey' ridgeCoefficients length (${coeffs.size}) != featureList length (${featureList.size})"
                )
            }

            for (i in scales.indices) {
                val s = scales[i]
                if (s <= 0.0 || s.isNaN() || s.isInfinite()) {
                    throw EstimationException.InvalidModelException(
                        reason = EstimationException.InvalidModelException.Reason.INVALID_SCALE,
                        message = "Site '$siteKey' scale at index $i must be strictly positive and finite (found $s)"
                    )
                }
            }

            if (quantile <= 0.0 || quantile.isNaN() || quantile.isInfinite()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_QUANTILE,
                    message = "Site '$siteKey' residualQuantile90 must be strictly positive and finite (found $quantile)"
                )
            }

            parsedSites[site] = SiteModel(
                standardizationMeans = means,
                standardizationScales = scales,
                ridgeCoefficients = coeffs,
                intercept = intercept,
                residualQuantile90 = quantile
            )
        }

        if (!parsedSites.containsKey(BodySite.STERNUM)) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Model missing required 'sternum' site parameters"
            )
        }

        // Multi-site weights
        val weightsMap = root["multiSiteWeights"] as? Map<*, *>
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Missing or invalid object 'multiSiteWeights'"
            )

        val parsedWeights = mutableMapOf<BodySite, Double>()
        for ((wKey, wVal) in weightsMap) {
            if (wKey !is String) continue
            val site = try {
                BodySite.fromString(wKey)
            } catch (e: Exception) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Unknown site key in multiSiteWeights: '$wKey'"
                )
            }
            if (wVal !is Number) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS,
                    message = "Weight for site '$wKey' must be a number"
                )
            }
            val w = wVal.toDouble()
            if (w < 0.0 || w.isNaN() || w.isInfinite()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS,
                    message = "Weight for site '$wKey' must be non-negative finite number (found $w)"
                )
            }
            parsedWeights[site] = w
        }

        if (!parsedWeights.containsKey(BodySite.STERNUM)) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "multiSiteWeights missing required weight for 'sternum'"
            )
        }

        return EstimationModel(
            modelVersion = modelVersion,
            minAppVersion = minAppVersion,
            units = units,
            algorithm = algorithm,
            colorSpace = colorSpace,
            trainingDataSummary = trainingDataSummary,
            featureList = featureList,
            sites = parsedSites,
            multiSiteWeights = parsedWeights
        )
    }

    private fun parseString(map: Map<*, *>, key: String, required: Boolean): String {
        val value = map[key]
        if (value == null) {
            if (required) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                    message = "Missing required string property '$key'"
                )
            }
            return ""
        }
        val s = value as? String
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be a string"
            )
        if (required && s.isBlank()) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must not be blank"
            )
        }
        return s
    }

    private fun parseDouble(map: Map<*, *>, key: String, required: Boolean): Double {
        val value = map[key]
        if (value == null) {
            if (required) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                    message = "Missing required number property '$key'"
                )
            }
            return 0.0
        }
        val num = value as? Number
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be a number"
            )
        val d = num.toDouble()
        if (d.isNaN() || d.isInfinite()) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be a finite number"
            )
        }
        return d
    }

    private fun parseLong(map: Map<*, *>, key: String, required: Boolean): Long {
        val value = map[key]
        if (value == null) {
            if (required) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                    message = "Missing required integer property '$key'"
                )
            }
            return 0L
        }
        val num = value as? Number
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be an integer"
            )
        return num.toLong()
    }

    private fun parseStringList(map: Map<*, *>, key: String): List<String> {
        val value = map[key]
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Missing required list property '$key'"
            )
        val list = value as? List<*>
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be a JSON array"
            )
        val result = mutableListOf<String>()
        for (item in list) {
            val str = item as? String
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Items in array '$key' must be strings"
                )
            result.add(str)
        }
        return result
    }

    private fun parseDoubleList(map: Map<*, *>, key: String): List<Double> {
        val value = map[key]
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Missing required list property '$key'"
            )
        val list = value as? List<*>
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                message = "Property '$key' must be a JSON array"
            )
        val result = mutableListOf<Double>()
        for (item in list) {
            val num = item as? Number
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Items in array '$key' must be numbers"
                )
            val d = num.toDouble()
            if (d.isNaN() || d.isInfinite()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION,
                    message = "Item in array '$key' must be a finite number"
                )
            }
            result.add(d)
        }
        return result
    }

    /**
     * Pure Kotlin recursive-descent JSON parser.
     */
    internal object SimpleJsonParser {
        fun parse(text: String): Any? {
            val tokenizer = Tokenizer(text)
            val result = parseValue(tokenizer)
            tokenizer.skipWhitespace()
            if (!tokenizer.isEof()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                    message = "Trailing characters after JSON payload at position ${tokenizer.pos}"
                )
            }
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
                else -> throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                    message = "Unexpected character '$ch' at position ${tokenizer.pos}"
                )
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
                        else -> throw EstimationException.InvalidModelException(
                            reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                            message = "Invalid escape sequence: \\$esc"
                        )
                    }
                } else {
                    sb.append(c)
                }
            }
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Unterminated string at position ${tokenizer.pos}"
            )
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
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Expected boolean at position ${tokenizer.pos}"
            )
        }

        private fun parseNull(tokenizer: Tokenizer): Any? {
            if (tokenizer.match("null")) return null
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Expected null at position ${tokenizer.pos}"
            )
        }

        private class Tokenizer(val text: String) {
            var pos = 0
            fun isEof(): Boolean = pos >= text.length
            fun peek(): Char = if (isEof()) throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Unexpected EOF"
            ) else text[pos]

            fun next(): Char = if (isEof()) throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                message = "Unexpected EOF"
            ) else text[pos++]

            fun consume(expected: Char) {
                val ch = next()
                if (ch != expected) {
                    throw EstimationException.InvalidModelException(
                        reason = EstimationException.InvalidModelException.Reason.SYNTAX_ERROR,
                        message = "Expected '$expected' but found '$ch' at position $pos"
                    )
                }
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
