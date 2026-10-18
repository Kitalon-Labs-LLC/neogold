package com.kitalonlabs.neogold.engine.colorscience

/**
 * Standard CIE illuminant reference white points (2° standard observer).
 */
object IlluminantD65 {
    /** Normalized D65 reference white (Y = 1.0). */
    val D65_NORMALIZED = Xyz(0.95047, 1.00000, 1.08883)

    /** 100-scale D65 reference white (Y = 100.0). */
    val D65_100 = Xyz(95.047, 100.0, 108.883)
}

/**
 * Color tuple in RGB color space with channel values in [0.0, 1.0].
 */
data class Rgb(
    val r: Double,
    val g: Double,
    val b: Double
) {
    init {
        require(!r.isNaN() && !g.isNaN() && !b.isNaN()) { "RGB components must not be NaN" }
    }

    /** Returns this RGB with all channels clamped to [0.0, 1.0]. */
    fun clamp(): Rgb = Rgb(
        r = StrictMath.min(1.0, StrictMath.max(0.0, r)),
        g = StrictMath.min(1.0, StrictMath.max(0.0, g)),
        b = StrictMath.min(1.0, StrictMath.max(0.0, b))
    )

    /** Relative luminance using standard Rec. 709 / sRGB coefficients. */
    fun luminance(): Double = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b

    /** Arithmetic average intensity (R + G + B) / 3. */
    fun averageIntensity(): Double = (r + g + b) / 3.0

    companion object {
        fun from8Bit(r: Int, g: Int, b: Int): Rgb = Rgb(
            r = r.coerceIn(0, 255) / 255.0,
            g = g.coerceIn(0, 255) / 255.0,
            b = b.coerceIn(0, 255) / 255.0
        )
    }
}

/**
 * Color tuple in CIE 1931 XYZ color space.
 * Default scale is normalized to standard illuminant D65 with Yn = 1.0.
 */
data class Xyz(
    val x: Double,
    val y: Double,
    val z: Double
) {
    init {
        require(!x.isNaN() && !y.isNaN() && !z.isNaN()) { "XYZ components must not be NaN" }
    }
}

/**
 * Color tuple in CIE 1976 L*a*b* (CIELAB) color space.
 * L* represents lightness [0, 100], a* represents green-red, b* represents blue-yellow.
 */
data class Lab(
    val l: Double,
    val a: Double,
    val b: Double
) {
    init {
        require(!l.isNaN() && !a.isNaN() && !b.isNaN()) { "CIELAB components must not be NaN" }
    }
}

/**
 * Color tuple in CIELCh cylindrical color space.
 * L represents lightness [0, 100], C represents chroma [0, max], hDeg represents hue angle in degrees [0, 360).
 */
data class Lch(
    val l: Double,
    val c: Double,
    val hDeg: Double
) {
    init {
        require(!l.isNaN() && !c.isNaN() && !hDeg.isNaN()) { "CIELCh components must not be NaN" }
    }
}
