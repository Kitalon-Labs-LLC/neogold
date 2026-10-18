package com.kitalonlabs.neogold.engine.colorscience

/**
 * Exact, deterministic color conversions across sRGB, Linear RGB, CIE XYZ (D65), CIELAB, and CIELCh.
 * Uses StrictMath for all transcendentals to maintain bit-identical consistency across ABIs.
 */
object ColorConversions {

    // CIE standard constants (CIE 15:2004)
    // epsilon = (6/29)^3 = 216 / 24389
    private const val CIE_EPSILON = 216.0 / 24389.0

    // kappa = (29/3)^3 = 24389 / 27
    private const val CIE_KAPPA = 24389.0 / 27.0

    // sRGB to XYZ (D65, Yn = 1.0) matrix coefficients
    private const val M00 = 0.4124564
    private const val M01 = 0.3575761
    private const val M02 = 0.1804375

    private const val M10 = 0.2126729
    private const val M11 = 0.7151522
    private const val M12 = 0.0721750

    private const val M20 = 0.0193339
    private const val M21 = 0.1191920
    private const val M22 = 0.9503041

    // Exact inverse matrix coefficients (XYZ to sRGB, D65)
    private const val INV_M00 = 3.2404548360214087
    private const val INV_M01 = -1.5371388501025751
    private const val INV_M02 = -0.4985315468684810

    private const val INV_M10 = -0.9692663898756538
    private const val INV_M11 = 1.8760109288424911
    private const val INV_M12 = 0.0415560823466735

    private const val INV_M20 = 0.0556434196042137
    private const val INV_M21 = -0.2040258542676982
    private const val INV_M22 = 1.0572251624579290

    // Thresholds for sRGB companding
    private const val SRGB_LINEAR_THRESHOLD = 0.04045
    private const val LINEAR_SRGB_THRESHOLD = 0.04045 / 12.92

    fun sRgbToLinear(rgb: Rgb): Rgb = Rgb(
        r = sRgbChannelToLinear(rgb.r),
        g = sRgbChannelToLinear(rgb.g),
        b = sRgbChannelToLinear(rgb.b)
    )

    fun linearToSRgb(rgb: Rgb): Rgb = Rgb(
        r = linearChannelToSRgb(rgb.r),
        g = linearChannelToSRgb(rgb.g),
        b = linearChannelToSRgb(rgb.b)
    )

    private fun sRgbChannelToLinear(c: Double): Double {
        return if (c <= SRGB_LINEAR_THRESHOLD) {
            c / 12.92
        } else {
            StrictMath.pow((c + 0.055) / 1.055, 2.4)
        }
    }

    private fun linearChannelToSRgb(c: Double): Double {
        return if (c <= LINEAR_SRGB_THRESHOLD) {
            c * 12.92
        } else {
            1.055 * StrictMath.pow(c, 1.0 / 2.4) - 0.055
        }
    }

    fun linearRgbToXyz(rgb: Rgb): Xyz = Xyz(
        x = M00 * rgb.r + M01 * rgb.g + M02 * rgb.b,
        y = M10 * rgb.r + M11 * rgb.g + M12 * rgb.b,
        z = M20 * rgb.r + M21 * rgb.g + M22 * rgb.b
    )

    fun xyzToLinearRgb(xyz: Xyz): Rgb = Rgb(
        r = INV_M00 * xyz.x + INV_M01 * xyz.y + INV_M02 * xyz.z,
        g = INV_M10 * xyz.x + INV_M11 * xyz.y + INV_M12 * xyz.z,
        b = INV_M20 * xyz.x + INV_M21 * xyz.y + INV_M22 * xyz.z
    )

    fun sRgbToXyz(rgb: Rgb): Xyz {
        val linear = sRgbToLinear(rgb)
        return linearRgbToXyz(linear)
    }

    fun xyzToSRgb(xyz: Xyz): Rgb {
        val linear = xyzToLinearRgb(xyz)
        return linearToSRgb(linear)
    }

    fun xyzToLab(xyz: Xyz, whitePoint: Xyz = IlluminantD65.D65_NORMALIZED): Lab {
        val xr = xyz.x / whitePoint.x
        val yr = xyz.y / whitePoint.y
        val zr = xyz.z / whitePoint.z

        val fx = labF(xr)
        val fy = labF(yr)
        val fz = labF(zr)

        val l = 116.0 * fy - 16.0
        val a = 500.0 * (fx - fy)
        val b = 200.0 * (fy - fz)

        return Lab(l, a, b)
    }

    fun labToXyz(lab: Lab, whitePoint: Xyz = IlluminantD65.D65_NORMALIZED): Xyz {
        val fy = (lab.l + 16.0) / 116.0
        val fx = lab.a / 500.0 + fy
        val fz = fy - lab.b / 200.0

        val xr = labInvF(fx)
        val yr = labInvF(fy)
        val zr = labInvF(fz)

        return Xyz(
            x = xr * whitePoint.x,
            y = yr * whitePoint.y,
            z = zr * whitePoint.z
        )
    }

    private fun labF(t: Double): Double {
        return if (t > CIE_EPSILON) {
            StrictMath.cbrt(t)
        } else {
            (CIE_KAPPA * t + 16.0) / 116.0
        }
    }

    private fun labInvF(f: Double): Double {
        val f3 = f * f * f
        return if (f3 > CIE_EPSILON) {
            f3
        } else {
            (116.0 * f - 16.0) / CIE_KAPPA
        }
    }

    fun labToLch(lab: Lab): Lch {
        val c = StrictMath.hypot(lab.a, lab.b)
        var hDeg = StrictMath.toDegrees(StrictMath.atan2(lab.b, lab.a))
        if (hDeg < 0.0) {
            hDeg += 360.0
        }
        return Lch(l = lab.l, c = c, hDeg = hDeg)
    }

    fun lchToLab(lch: Lch): Lab {
        val hRad = StrictMath.toRadians(lch.hDeg)
        val a = lch.c * StrictMath.cos(hRad)
        val b = lch.c * StrictMath.sin(hRad)
        return Lab(l = lch.l, a = a, b = b)
    }
}

// Package-level convenience extension/wrapper functions
fun sRgbToLinear(rgb: Rgb): Rgb = ColorConversions.sRgbToLinear(rgb)
fun linearToSRgb(rgb: Rgb): Rgb = ColorConversions.linearToSRgb(rgb)
fun linearRgbToXyz(rgb: Rgb): Xyz = ColorConversions.linearRgbToXyz(rgb)
fun xyzToLinearRgb(xyz: Xyz): Rgb = ColorConversions.xyzToLinearRgb(xyz)
fun sRgbToXyz(rgb: Rgb): Xyz = ColorConversions.sRgbToXyz(rgb)
fun xyzToSRgb(xyz: Xyz): Rgb = ColorConversions.xyzToSRgb(xyz)
fun xyzToLab(xyz: Xyz, whitePoint: Xyz = IlluminantD65.D65_NORMALIZED): Lab = ColorConversions.xyzToLab(xyz, whitePoint)
fun labToXyz(lab: Lab, whitePoint: Xyz = IlluminantD65.D65_NORMALIZED): Xyz = ColorConversions.labToXyz(lab, whitePoint)
fun labToLch(lab: Lab): Lch = ColorConversions.labToLch(lab)
fun lchToLab(lch: Lch): Lab = ColorConversions.lchToLab(lch)
