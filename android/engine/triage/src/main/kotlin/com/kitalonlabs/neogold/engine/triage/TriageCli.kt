package com.kitalonlabs.neogold.engine.triage

import java.io.File

/**
 * Command-line entry point for evaluating triage batches from stdin.
 * Used for differential testing against external reference implementations.
 */
object TriageCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val configPath = if (args.isNotEmpty()) args[0] else "testdata/triage/config.synthetic.json"
        val configFile = File(configPath).takeIf { it.exists() }
            ?: File("../$configPath").takeIf { it.exists() }
            ?: File("../../$configPath").takeIf { it.exists() }
            ?: File("../../../$configPath").takeIf { it.exists() }
            ?: File("d:/Project/neogold-android/$configPath")
        val config = TriageConfigParser.parse(configFile.readText())
        val engine = TriageEngine(config = config)

        val input = System.`in`.bufferedReader().readText()
        val parsed = TriageConfigParser.JsonParser.parse(input) as List<*>
        val results = parsed.map { item ->
            val map = item as Map<*, *>
            val danger = map["danger"] as? Boolean ?: false
            val onset24 = map["onset24"] as? Boolean ?: false
            val ga = (map["ga"] as? Number)?.toInt() ?: 40
            val photo = map["photo"] as? Boolean ?: false
            val ageH = (map["age_h"] as? Number)?.toDouble() ?: 48.0
            val qFail = if (map["quality_failed_3"] as? Boolean == true) 3 else 0
            val e = (map["E"] as? Number)?.toDouble()
            val l = (map["L"] as? Number)?.toDouble()
            val u = (map["U"] as? Number)?.toDouble()

            val res = engine.evaluate(
                TriageInputs(
                    dangerSigns = danger,
                    jaundiceOnsetUnder24h = onset24,
                    gestationalAgeWeeks = ga,
                    priorPhototherapy = photo,
                    ageHours = ageH,
                    qualityFailedAttempts = qFail,
                    estimate = e,
                    lowerBound = l,
                    upperBound = u
                )
            )
            when (res) {
                is TriageResult.Action -> listOf(res.action.name, res.reasonCode)
                is TriageResult.Unavailable -> listOf("UNAVAILABLE", res.reason.name)
            }
        }

        val sb = StringBuilder()
        sb.append("[")
        results.forEachIndexed { i, r ->
            if (i > 0) sb.append(",")
            sb.append("[\"${r[0]}\",\"${r[1]}\"]")
        }
        sb.append("]")
        print(sb.toString())
    }
}
