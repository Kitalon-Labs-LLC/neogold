package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Validates CIEDE2000 implementation against the 34 published test pairs from:
 * Gaurav Sharma, Wencheng Wu, and Edul N. Dalal,
 * "The CIEDE2000 color-difference formula: Implementation notes, supplementary test data,
 * and mathematical observations," Color Research & Application, vol. 30, no. 1, pp. 21-30, 2005.
 *
 * Tolerance: all pairs within 1e-4 of published values.
 */
class Ciede2000SharmaTest {

    private data class SharmaPair(
        val lab1: Lab,
        val lab2: Lab,
        val expectedDeltaE: Double
    )

    // The 34 test pairs from Sharma et al. (2005) Table 2 / ciede2000testdata.txt
    private val sharmaTestPairs = listOf(
        SharmaPair(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485), 2.0425),
        SharmaPair(Lab(50.0, 3.1571, -77.2803), Lab(50.0, 0.0, -82.7485), 2.8615),
        SharmaPair(Lab(50.0, 2.8361, -74.0200), Lab(50.0, 0.0, -82.7485), 3.4412),
        SharmaPair(Lab(50.0, -1.3802, -84.2814), Lab(50.0, 0.0, -82.7485), 1.0000),
        SharmaPair(Lab(50.0, -1.1848, -84.8006), Lab(50.0, 0.0, -82.7485), 1.0000),
        SharmaPair(Lab(50.0, -0.9009, -85.5211), Lab(50.0, 0.0, -82.7485), 1.0000),
        SharmaPair(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0), 2.3669),
        SharmaPair(Lab(50.0, -1.0, 2.0), Lab(50.0, 0.0, 0.0), 2.3669),
        SharmaPair(Lab(50.0, 2.49, -0.001), Lab(50.0, -2.49, 0.0009), 7.1792),
        SharmaPair(Lab(50.0, 2.49, -0.001), Lab(50.0, -2.49, 0.0010), 7.1792),
        SharmaPair(Lab(50.0, 2.49, -0.001), Lab(50.0, -2.49, 0.0011), 7.2195),
        SharmaPair(Lab(50.0, 2.49, -0.001), Lab(50.0, -2.49, 0.0012), 7.2195),
        SharmaPair(Lab(50.0, -0.001, 2.49), Lab(50.0, 0.0009, -2.49), 4.8045),
        SharmaPair(Lab(50.0, -0.001, 2.49), Lab(50.0, 0.0010, -2.49), 4.8045),
        SharmaPair(Lab(50.0, -0.001, 2.49), Lab(50.0, 0.0011, -2.49), 4.7461),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(50.0, 0.0, -2.5), 4.3065),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0), 27.1492),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(61.0, -5.0, 29.0), 22.8977),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(56.0, -27.0, -3.0), 31.9030),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(58.0, 24.0, 15.0), 19.4535),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(50.0, 3.1736, 0.5854), 1.0000),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(50.0, 3.2972, 0.0), 1.0000),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(50.0, 1.8634, 0.5757), 1.0000),
        SharmaPair(Lab(50.0, 2.5, 0.0), Lab(50.0, 3.2592, 0.3350), 1.0000),
        SharmaPair(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387), 1.2644),
        SharmaPair(Lab(63.0109, -31.0961, -5.8663), Lab(62.8187, -29.7946, -4.0864), 1.2630),
        SharmaPair(Lab(61.2901, 3.7196, -5.3901), Lab(61.4292, 2.2480, -4.9620), 1.8731),
        SharmaPair(Lab(35.0831, -44.1164, 3.7933), Lab(35.0232, -40.0716, 1.5901), 1.8645),
        SharmaPair(Lab(22.7233, 20.0904, -46.6940), Lab(23.0331, 14.9730, -42.5619), 2.0373),
        SharmaPair(Lab(36.4612, 47.8580, 18.3852), Lab(36.2715, 50.5065, 21.2231), 1.4146),
        SharmaPair(Lab(90.8027, -2.0831, 1.4410), Lab(91.1528, -1.6435, 0.0447), 1.4441),
        SharmaPair(Lab(90.9257, -0.5406, -0.9208), Lab(88.6381, -0.8985, -0.7239), 1.5381),
        SharmaPair(Lab(6.7747, -0.2908, -2.4247), Lab(5.8714, -0.0985, -2.2286), 0.6377),
        SharmaPair(Lab(2.0776, 0.0795, -1.1350), Lab(0.9033, -0.0636, -0.5514), 0.9082)
    )

    @Test
    fun testAll34SharmaPairsWithinTolerance() {
        assertThat(sharmaTestPairs).hasSize(34)
        for ((index, pair) in sharmaTestPairs.withIndex()) {
            val actual = ciede2000(pair.lab1, pair.lab2)
            val diff = StrictMath.abs(actual - pair.expectedDeltaE)
            assertWithMessage(
                "Pair #%s failed: lab1=%s, lab2=%s, expected=%s, actual=%s, diff=%s",
                index + 1, pair.lab1, pair.lab2, pair.expectedDeltaE, actual, diff
            ).that(diff).isAtMost(1e-4)
        }
    }

    @Test
    fun testSymmetry() {
        for (pair in sharmaTestPairs) {
            val d12 = ciede2000(pair.lab1, pair.lab2)
            val d21 = ciede2000(pair.lab2, pair.lab1)
            assertThat(StrictMath.abs(d12 - d21)).isAtMost(1e-12)
        }
    }

    @Test
    fun testIdenticalColorIsZero() {
        val lab = Lab(50.0, 12.0, -30.0)
        val d = ciede2000(lab, lab)
        assertThat(d).isEqualTo(0.0)
    }
}
