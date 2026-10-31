package com.kitalonlabs.neogold.engine.estimation

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.colorscience.Lab
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * TC-EST-01: Parity test with golden test vectors at 1e-4.
 * Validates features against P04's golden_patch_correction.json and golden estimation vectors.
 * Compares the engine's output with the committed testdata/golden/estimation/golden_estimation.json and its manifest.sha256.
 */
class EstimationGoldenVectorsTest {

    private val repoRoot: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return dir
                }
                dir = dir.parentFile
            }
            return File("../../..").canonicalFile
        }

    private val goldenEstimationDir: File
        get() = File(repoRoot, "testdata/golden/estimation").canonicalFile

    private val goldenColorScienceDir: File
        get() = File(repoRoot, "testdata/golden/colorscience").canonicalFile

    private val syntheticModelFile: File
        get() = File(repoRoot, "testdata/models/model.synthetic.json").canonicalFile

    @Test
    fun testTcEst01GoldenVectorsParity() {
        // Step 1: Verify parity with P04 skin window features at 1e-4
        val patchCorrectionFile = File(goldenColorScienceDir, "golden_patch_correction.json")
        assertThat(patchCorrectionFile.exists()).isTrue()

        // P04 nominal skin window Lab: [62.27276993, 10.71970422, 26.04906880]
        val p04WindowLab = Lab(62.27276993, 10.71970422, 26.04906880)
        val p04Features = FeatureExtractor.extractFeatures(p04WindowLab)

        val tol = 1e-4
        assertThat(p04Features["l_star"]!!).isWithin(tol).of(62.27276993)
        assertThat(p04Features["a_star"]!!).isWithin(tol).of(10.71970422)
        assertThat(p04Features["b_star"]!!).isWithin(tol).of(26.04906880)
        assertThat(p04Features["b_over_l"]!!).isWithin(tol).of(0.41830593)
        assertThat(p04Features["a_over_b"]!!).isWithin(tol).of(0.41151967)

        // Step 2: compare the engine's output with the committed, reviewed golden file (BUG-2026-045, hazard H7).
        // Nothing is written unless NEOGOLD_REGENERATE_GOLDEN=1 is set on purpose; then the test fails with a reminder.
        val goldenFile = File(goldenEstimationDir, "golden_estimation.json")
        val manifestFile = File(goldenEstimationDir, "manifest.sha256")

        val modelJson = syntheticModelFile.readText()
        val engine = EstimationEngine.fromJson(modelJson)

        val goldenJson = buildGoldenEstimationJson(engine)
        if (System.getenv(REGENERATE_ENV) == "1") {
            goldenEstimationDir.mkdirs()
            goldenFile.writeText(goldenJson)
            manifestFile.writeText("${sha256(goldenFile)}  golden_estimation.json\n")
            throw AssertionError(
                "Golden vectors regenerated. Read the diff of testdata/golden/estimation, commit it, run again."
            )
        }

        // Step 3: the committed file is the expectation (line endings do not matter), and the manifest describes it
        assertThat(goldenFile.exists()).isTrue()
        assertThat(manifestFile.exists()).isTrue()
        assertThat(lf(goldenFile.readText())).isEqualTo(lf(goldenJson))

        val manifestLines = manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertThat(manifestLines).hasSize(1)
        val parts = manifestLines[0].split(Regex("\\s+"))
        assertThat(parts[0]).isEqualTo(sha256(goldenFile))
        assertThat(parts[1]).isEqualTo("golden_estimation.json")

        // Step 4: Verify inference against golden expectations within 1e-4
        val estSternum = engine.estimateSite(BodySite.STERNUM, p04WindowLab)
        assertThat(estSternum.estimate).isWithin(tol).of(10.78934137)
        assertThat(estSternum.lowerBound).isWithin(tol).of(8.98934137)
        assertThat(estSternum.upperBound).isWithin(tol).of(12.58934137)

        val tenths = estSternum.toIntegerTenths()
        assertThat(tenths.estimateTenths).isEqualTo(108)
        assertThat(tenths.lowerTenths).isEqualTo(89)
        assertThat(tenths.upperTenths).isEqualTo(126)
    }

    private fun SingleSiteEstimate.toIntegerTenths(): IntegerTenthsInterval = IntegerTenthsInterval(
        estimateTenths = EstimationOutput.toTenthsCeiling(estimate),
        lowerTenths = EstimationOutput.toTenthsFloor(lowerBound),
        upperTenths = EstimationOutput.toTenthsCeiling(upperBound)
    )

    private fun buildGoldenEstimationJson(engine: EstimationEngine): String {
        // Diverse clinical testing cases:
        // 1. P04 Nominal skin window (intermediate jaundice)
        // 2. Light skin, high yellowness (elevated jaundice)
        // 3. Medium skin, low yellowness (normal/low bilirubin)
        // 4. Dark skin, high yellowness (elevated jaundice in dark skin)
        // 5. Forehead reading for multi-site
        val cases = listOf(
            Triple("EST-01-NOMINAL", Lab(62.27276993, 10.71970422, 26.04906880), "P04 golden skin window nominal case"),
            Triple("EST-02-LIGHT-HIGH-Y", Lab(72.0, 5.0, 30.0), "Light skin with high yellowness"),
            Triple("EST-03-MED-LOW-Y", Lab(56.0, 12.0, 14.0), "Medium skin with low yellowness"),
            Triple("EST-04-DARK-HIGH-Y", Lab(40.0, 6.0, 27.0), "Dark skin with high yellowness"),
            Triple("EST-05-FOREHEAD", Lab(64.0, 9.0, 24.0), "Representative forehead reading for multi-site")
        )

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": \"1.0.0\",\n")
        sb.append(
            "  \"description\": \"Golden estimation vectors for on-device ridge regression and 90% split-conformal intervals\",\n"
        )
        sb.append("  \"model_version\": \"${engine.model.modelVersion}\",\n")
        sb.append("  \"feature_list\": [\"l_star\", \"a_star\", \"b_star\", \"b_over_l\", \"a_over_b\"],\n")
        sb.append("  \"vectors\": [\n")

        for (idx in cases.indices) {
            val (id, lab, desc) = cases[idx]
            val features = FeatureExtractor.extractFeatures(lab)
            val sternum = engine.estimateSite(BodySite.STERNUM, lab)
            val forehead = engine.estimateSite(BodySite.FOREHEAD, lab)
            val combined = engine.estimateMultiSite(lab, lab)

            sb.append("    {\n")
            sb.append("      \"id\": \"$id\",\n")
            sb.append("      \"description\": \"$desc\",\n")
            sb.append("      \"input_lab\": [${fmt(lab.l)}, ${fmt(lab.a)}, ${fmt(lab.b)}],\n")
            sb.append("      \"extracted_features\": {\n")
            sb.append("        \"l_star\": ${fmt(features["l_star"]!!)},\n")
            sb.append("        \"a_star\": ${fmt(features["a_star"]!!)},\n")
            sb.append("        \"b_star\": ${fmt(features["b_star"]!!)},\n")
            sb.append("        \"b_over_l\": ${fmt(features["b_over_l"]!!)},\n")
            sb.append("        \"a_over_b\": ${fmt(features["a_over_b"]!!)}\n")
            sb.append("      },\n")
            sb.append("      \"sternum\": {\n")
            sb.append(
                "        \"standardized_features\": [${sternum.standardizedFeatures.joinToString(", ") { fmt(it) }}],\n"
            )
            sb.append("        \"estimate\": ${fmt(sternum.estimate)},\n")
            sb.append("        \"residual_quantile_90\": ${fmt(sternum.residualQuantile90)},\n")
            sb.append("        \"lower_bound\": ${fmt(sternum.lowerBound)},\n")
            sb.append("        \"upper_bound\": ${fmt(sternum.upperBound)},\n")
            val sTenths = sternum.toIntegerTenths()
            sb.append(
                "        \"integer_tenths\": {\"estimate\": ${sTenths.estimateTenths}, \"lower\": ${sTenths.lowerTenths}, " +
                    "\"upper\": ${sTenths.upperTenths}}\n"
            )
            sb.append("      },\n")
            sb.append("      \"forehead\": {\n")
            sb.append(
                "        \"standardized_features\": [${forehead.standardizedFeatures.joinToString(
                    ", "
                ) { fmt(it) }}],\n"
            )
            sb.append("        \"estimate\": ${fmt(forehead.estimate)},\n")
            sb.append("        \"residual_quantile_90\": ${fmt(forehead.residualQuantile90)},\n")
            sb.append("        \"lower_bound\": ${fmt(forehead.lowerBound)},\n")
            sb.append("        \"upper_bound\": ${fmt(forehead.upperBound)},\n")
            val fTenths = forehead.toIntegerTenths()
            sb.append(
                "        \"integer_tenths\": {\"estimate\": ${fTenths.estimateTenths}, \"lower\": ${fTenths.lowerTenths}, " +
                    "\"upper\": ${fTenths.upperTenths}}\n"
            )
            sb.append("      },\n")
            sb.append("      \"combined_multisite\": {\n")
            sb.append("        \"estimate\": ${fmt(combined.estimate)},\n")
            sb.append("        \"lower_bound\": ${fmt(combined.lowerBound)},\n")
            sb.append("        \"upper_bound\": ${fmt(combined.upperBound)},\n")
            val cTenths = combined.integerTenths
            sb.append(
                "        \"integer_tenths\": {\"estimate\": ${cTenths.estimateTenths}, \"lower\": ${cTenths.lowerTenths}, " +
                    "\"upper\": ${cTenths.upperTenths}}\n"
            )
            sb.append("      }\n")
            sb.append(if (idx == cases.size - 1) "    }\n" else "    },\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.8f", d)

    private fun lf(text: String): String = text.replace("\r\n", "\n")

    /** SHA-256 of the file with LF line endings, which is how the manifest was written and how git stores it. */
    private fun sha256(file: File): String {
        val bytes = lf(file.readText()).toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REGENERATE_ENV = "NEOGOLD_REGENERATE_GOLDEN"
    }
}
