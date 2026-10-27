package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Generates and validates golden test vectors for P06 (estimation) and P07 (training pipeline).
 * Stored in testdata/golden/colorscience/ with a SHA-256 manifest.
 */
class GoldenVectorsTest {

    private val goldenDir: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return File(dir, "testdata/golden/colorscience").canonicalFile
                }
                dir = dir.parentFile
            }
            return File("../../../testdata/golden/colorscience").canonicalFile
        }

    @Test
    fun testGenerateAndVerifyGoldenVectors() {
        goldenDir.mkdirs()

        val conversionsFile = File(goldenDir, "golden_color_conversions.json")
        val correctionFile = File(goldenDir, "golden_patch_correction.json")
        val manifestFile = File(goldenDir, "manifest.sha256")

        val conversionsJson = buildConversionsGoldenJson()
        conversionsFile.writeText(conversionsJson)

        val correctionJson = buildCorrectionGoldenJson()
        correctionFile.writeText(correctionJson)

        val conversionsSha256 = sha256(conversionsFile)
        val correctionSha256 = sha256(correctionFile)

        val manifestContent = buildString {
            appendLine("$conversionsSha256  golden_color_conversions.json")
            appendLine("$correctionSha256  golden_patch_correction.json")
        }
        manifestFile.writeText(manifestContent)

        // Verification checks
        assertThat(conversionsFile.exists()).isTrue()
        assertThat(correctionFile.exists()).isTrue()
        assertThat(manifestFile.exists()).isTrue()

        // Read back and verify manifest matches
        val manifestLines = manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertThat(manifestLines).hasSize(2)

        val line1Parts = manifestLines[0].split(Regex("\\s+"))
        assertThat(line1Parts[0]).isEqualTo(conversionsSha256)
        assertThat(line1Parts[1]).isEqualTo("golden_color_conversions.json")

        val line2Parts = manifestLines[1].split(Regex("\\s+"))
        assertThat(line2Parts[0]).isEqualTo(correctionSha256)
        assertThat(line2Parts[1]).isEqualTo("golden_patch_correction.json")
    }

    private fun buildConversionsGoldenJson(): String {
        // Nominal Card v1 patches (Lab D65)
        val patches = listOf(
            Triple("G1", Lab(90.0, 0.0, 0.0), "gray ramp step 1"),
            Triple("G2", Lab(77.0, 0.0, 0.0), "gray ramp step 2"),
            Triple("G3", Lab(64.0, 0.0, 0.0), "gray ramp step 3"),
            Triple("G4", Lab(51.0, 0.0, 0.0), "gray ramp step 4"),
            Triple("G5", Lab(39.0, 0.0, 0.0), "gray ramp step 5"),
            Triple("G6", Lab(28.0, 0.0, 0.0), "gray ramp step 6"),
            Triple("G7", Lab(18.0, 0.0, 0.0), "gray ramp step 7"),
            Triple("K1", Lab(72.0, 10.0, 12.0), "light skin, low yellowness"),
            Triple("K2", Lab(72.0, 8.0, 20.0), "light skin, mid yellowness"),
            Triple("K3", Lab(72.0, 5.0, 30.0), "light skin, high yellowness"),
            Triple("K4", Lab(56.0, 12.0, 14.0), "medium skin, low yellowness"),
            Triple("K5", Lab(56.0, 10.0, 22.0), "medium skin, mid yellowness"),
            Triple("K6", Lab(56.0, 6.0, 32.0), "medium skin, high yellowness"),
            Triple("K7", Lab(40.0, 12.0, 12.0), "dark skin, low yellowness"),
            Triple("K8", Lab(40.0, 9.0, 20.0), "dark skin, mid yellowness"),
            Triple("K9", Lab(40.0, 6.0, 27.0), "dark skin, high yellowness"),
            Triple("X1", Lab(80.0, 2.0, 70.0), "saturated yellow"),
            Triple("X2", Lab(45.0, 52.0, 35.0), "saturated red"),
            Triple("X3", Lab(35.0, 8.0, -42.0), "saturated blue"),
            Triple("G2D", Lab(77.0, 0.0, 0.0), "duplicate gray 2"),
            Triple("G4D", Lab(51.0, 0.0, 0.0), "duplicate gray 4"),
            Triple("K5D", Lab(56.0, 10.0, 22.0), "duplicate medium skin"),
            Triple("K8D", Lab(40.0, 9.0, 20.0), "duplicate dark skin")
        )

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": \"1.0.0\",\n")
        sb.append(
            "  \"description\": \"Golden color conversion vectors across sRGB, Linear RGB, XYZ (D65), CIELAB, and CIELCh\",\n"
        )
        sb.append("  \"illuminant\": \"D65\",\n")
        sb.append("  \"vectors\": [\n")

        for ((index, item) in patches.withIndex()) {
            val (id, lab, desc) = item
            val xyz = labToXyz(lab)
            val linearRgb = xyzToLinearRgb(xyz)
            val srgb = linearToSRgb(linearRgb)
            val lch = labToLch(lab)

            sb.append("    {\n")
            sb.append("      \"id\": \"$id\",\n")
            sb.append("      \"description\": \"$desc\",\n")
            sb.append("      \"lab_d65\": [${fmt(lab.l)}, ${fmt(lab.a)}, ${fmt(lab.b)}],\n")
            sb.append("      \"lch_d65\": [${fmt(lch.l)}, ${fmt(lch.c)}, ${fmt(lch.hDeg)}],\n")
            sb.append("      \"xyz_d65\": [${fmt(xyz.x)}, ${fmt(xyz.y)}, ${fmt(xyz.z)}],\n")
            sb.append("      \"linear_rgb\": [${fmt(linearRgb.r)}, ${fmt(linearRgb.g)}, ${fmt(linearRgb.b)}],\n")
            sb.append("      \"srgb\": [${fmt(srgb.r)}, ${fmt(srgb.g)}, ${fmt(srgb.b)}]\n")
            sb.append(if (index == patches.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun buildCorrectionGoldenJson(): String {
        // Ground truth gray ramp ref reflectances
        val grayReflectances = listOf(0.763, 0.515, 0.328, 0.192, 0.106, 0.055, 0.025) // G1 to G7

        // Simulated camera observed grays under mild gamma 2.0 tone curve and sensor gain
        val observedGrays = listOf(
            Rgb(0.88, 0.89, 0.87), // G1
            Rgb(0.72, 0.73, 0.71), // G2
            Rgb(0.57, 0.58, 0.56), // G3
            Rgb(0.44, 0.45, 0.43), // G4
            Rgb(0.33, 0.34, 0.32), // G5
            Rgb(0.24, 0.25, 0.23), // G6
            Rgb(0.16, 0.17, 0.15) // G7
        )

        val linearizer = PerChannelLinearizer.fit(observedGrays, grayReflectances)

        // Training patches (all 23 patches)
        val patchLabs = listOf(
            "G1" to Lab(90.0, 0.0, 0.0),
            "G2" to Lab(77.0, 0.0, 0.0),
            "G3" to Lab(64.0, 0.0, 0.0),
            "G4" to Lab(51.0, 0.0, 0.0),
            "G5" to Lab(39.0, 0.0, 0.0),
            "G6" to Lab(28.0, 0.0, 0.0),
            "G7" to Lab(18.0, 0.0, 0.0),
            "K1" to Lab(72.0, 10.0, 12.0),
            "K2" to Lab(72.0, 8.0, 20.0),
            "K3" to Lab(72.0, 5.0, 30.0),
            "K4" to Lab(56.0, 12.0, 14.0),
            "K5" to Lab(56.0, 10.0, 22.0),
            "K6" to Lab(56.0, 6.0, 32.0),
            "K7" to Lab(40.0, 12.0, 12.0),
            "K8" to Lab(40.0, 9.0, 20.0),
            "K9" to Lab(40.0, 6.0, 27.0),
            "X1" to Lab(80.0, 2.0, 70.0),
            "X2" to Lab(45.0, 52.0, 35.0),
            "X3" to Lab(35.0, 8.0, -42.0),
            "G2D" to Lab(77.0, 0.0, 0.0),
            "G4D" to Lab(51.0, 0.0, 0.0),
            "K5D" to Lab(56.0, 10.0, 22.0),
            "K8D" to Lab(40.0, 9.0, 20.0)
        )

        // Simulate camera observed raw RGB for all 23 patches
        val rawObserved = patchLabs.map { (id, lab) ->
            val xyz = labToXyz(lab)
            val lin = xyzToLinearRgb(xyz)
            // Apply slight camera distortion: gamma 2.0 and color crosstalk
            val cr = StrictMath.sqrt(StrictMath.max(0.0, 0.98 * lin.r + 0.02 * lin.g))
            val cg = StrictMath.sqrt(StrictMath.max(0.0, 0.01 * lin.r + 0.97 * lin.g + 0.02 * lin.b))
            val cb = StrictMath.sqrt(StrictMath.max(0.0, 0.03 * lin.g + 0.97 * lin.b))
            Rgb(cr, cg, cb)
        }

        // Linearize
        val linearizedPatches = rawObserved.map { linearizer.linearize(it) }
        val referenceXyzs = patchLabs.map { labToXyz(it.second) }

        // Fit 3x3 CCM
        val ccm = ColorCorrectionFitter.fit(
            linearizedPatches,
            referenceXyzs,
            useRootPolynomial = false
        ) as Linear3x3CorrectionModel
        val residual = QualityResidualCalculator.computeResidual(ccm, linearizedPatches, patchLabs.map { it.second })

        // Fit 2nd-order root-polynomial CCM
        val rootPoly = ColorCorrectionFitter.fit(
            linearizedPatches,
            referenceXyzs,
            useRootPolynomial = true
        ) as RootPolynomialCorrectionModel

        // Skin window simulation (newborn sternum with medium jaundice: Lab ~ (62.0, 11.0, 26.0))
        val trueSkinLab = Lab(62.0, 11.0, 26.0)
        val trueSkinXyz = labToXyz(trueSkinLab)
        val trueSkinLin = xyzToLinearRgb(trueSkinXyz)
        val rawSkinObserved = Rgb(
            StrictMath.sqrt(StrictMath.max(0.0, 0.98 * trueSkinLin.r + 0.02 * trueSkinLin.g)),
            StrictMath.sqrt(StrictMath.max(0.0, 0.01 * trueSkinLin.r + 0.97 * trueSkinLin.g + 0.02 * trueSkinLin.b)),
            StrictMath.sqrt(StrictMath.max(0.0, 0.03 * trueSkinLin.g + 0.97 * trueSkinLin.b))
        )
        val linearizedSkin = linearizer.linearize(rawSkinObserved)
        val correctedSkinXyz = ccm.correct(linearizedSkin)
        val correctedSkinLab = xyzToLab(correctedSkinXyz)

        val bOverL = correctedSkinLab.b / correctedSkinLab.l
        val aOverB = correctedSkinLab.a / correctedSkinLab.b

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": \"1.0.0\",\n")
        sb.append(
            "  \"description\": \"Golden calibration and color correction vectors for Card v1 with skin window\",\n"
        )
        sb.append("  \"card_layout_version\": 1,\n")
        sb.append("  \"illuminant\": \"D65\",\n")
        sb.append("  \"correction_3x3_matrix\": [\n")
        for (r in 0 until 3) {
            sb.append("    [${fmt(ccm.matrix[r, 0])}, ${fmt(ccm.matrix[r, 1])}, ${fmt(ccm.matrix[r, 2])}]")
            sb.append(if (r == 2) "\n" else ",\n")
        }
        sb.append("  ],\n")
        sb.append("  \"root_polynomial_matrix\": [\n")
        for (r in 0 until 6) {
            sb.append(
                "    [${fmt(rootPoly.matrix[r, 0])}, ${fmt(rootPoly.matrix[r, 1])}, ${fmt(rootPoly.matrix[r, 2])}]"
            )
            sb.append(if (r == 5) "\n" else ",\n")
        }
        sb.append("  ],\n")
        sb.append("  \"quality_gate\": {\n")
        sb.append("    \"mean_delta_e\": ${fmt(residual.meanDeltaE)},\n")
        sb.append("    \"max_delta_e\": ${fmt(residual.maxDeltaE)}\n")
        sb.append("  },\n")
        sb.append("  \"patches\": [\n")
        for (i in patchLabs.indices) {
            val id = patchLabs[i].first
            val refLab = patchLabs[i].second
            val raw = rawObserved[i]
            val lin = linearizedPatches[i]
            val corXyz = ccm.correct(lin)
            val corLab = xyzToLab(corXyz)
            val dE = residual.patchDeltaEs[i]

            sb.append("    {\n")
            sb.append("      \"id\": \"$id\",\n")
            sb.append("      \"raw_observed_rgb\": [${fmt(raw.r)}, ${fmt(raw.g)}, ${fmt(raw.b)}],\n")
            sb.append("      \"linearized_rgb\": [${fmt(lin.r)}, ${fmt(lin.g)}, ${fmt(lin.b)}],\n")
            sb.append("      \"corrected_xyz\": [${fmt(corXyz.x)}, ${fmt(corXyz.y)}, ${fmt(corXyz.z)}],\n")
            sb.append("      \"corrected_lab\": [${fmt(corLab.l)}, ${fmt(corLab.a)}, ${fmt(corLab.b)}],\n")
            sb.append("      \"reference_lab\": [${fmt(refLab.l)}, ${fmt(refLab.a)}, ${fmt(refLab.b)}],\n")
            sb.append("      \"residual_delta_e\": ${fmt(dE)}\n")
            sb.append(if (i == patchLabs.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ],\n")
        sb.append("  \"skin_window\": {\n")
        sb.append(
            "    \"raw_observed_rgb\": [${fmt(
                rawSkinObserved.r
            )}, ${fmt(rawSkinObserved.g)}, ${fmt(rawSkinObserved.b)}],\n"
        )
        sb.append(
            "    \"linearized_rgb\": [${fmt(linearizedSkin.r)}, ${fmt(linearizedSkin.g)}, ${fmt(linearizedSkin.b)}],\n"
        )
        sb.append(
            "    \"corrected_xyz\": [${fmt(
                correctedSkinXyz.x
            )}, ${fmt(correctedSkinXyz.y)}, ${fmt(correctedSkinXyz.z)}],\n"
        )
        sb.append(
            "    \"corrected_lab\": [${fmt(
                correctedSkinLab.l
            )}, ${fmt(correctedSkinLab.a)}, ${fmt(correctedSkinLab.b)}],\n"
        )
        sb.append("    \"features\": {\n")
        sb.append("      \"l_star\": ${fmt(correctedSkinLab.l)},\n")
        sb.append("      \"a_star\": ${fmt(correctedSkinLab.a)},\n")
        sb.append("      \"b_star\": ${fmt(correctedSkinLab.b)},\n")
        sb.append("      \"b_over_l\": ${fmt(bOverL)},\n")
        sb.append("      \"a_over_b\": ${fmt(aOverB)}\n")
        sb.append("    }\n")
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.8f", d)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
