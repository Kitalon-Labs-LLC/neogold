package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.RectPx

/**
 * Patch geometric and radiometric definition in canonical card coordinates.
 */
data class PatchDef(
    val id: String,
    val group: String,
    val outerRect: RectPx,
    val innerSampleRect: RectPx,
    val nominalLab: Lab
)

/**
 * Source of truth for Card v1 geometry and reference targets (card/spec/card-v1.json).
 */
object CardGeometry {
    const val CANONICAL_WIDTH = 1200
    const val CANONICAL_HEIGHT = 760
    const val PX_PER_MM = 10.0

    // Marker dimensions: 16 mm x 16 mm (160 px x 160 px)
    const val MARKER_SIZE_PX = 160

    // Marker centers in canonical pixels
    val MARKER_CENTERS = mapOf(
        0 to Point2D(110.0, 110.0), // Top-Left (TL): x=30+80, y=30+80
        1 to Point2D(1090.0, 110.0), // Top-Right (TR): x=1010+80, y=30+80
        2 to Point2D(1090.0, 650.0), // Bottom-Right (BR): x=1010+80, y=570+80
        3 to Point2D(110.0, 650.0) // Bottom-Left (BL): x=30+80, y=570+80
    )

    // Marker bounding boxes in canonical pixels
    val MARKER_RECTS = mapOf(
        0 to RectPx(30, 30, 160, 160),
        1 to RectPx(1010, 30, 160, 160),
        2 to RectPx(1010, 570, 160, 160),
        3 to RectPx(30, 570, 160, 160)
    )

    // Punched skin window: 24 mm x 24 mm at x=36 mm, y=26 mm
    val WINDOW_OUTER_RECT = RectPx(360, 260, 240, 240)

    // Inner 70% linear region: 16.8 mm x 16.8 mm at x=39.6 mm, y=29.6 mm
    val WINDOW_INNER_RECT = RectPx(396, 296, 168, 168)

    // Calibration QR keepout area: 36.5 mm x 36.5 mm
    val QR_CLEAR_RECT = RectPx(630, 197, 365, 365)

    // Duplicate patch pairs for uniformity / local tone mapping check
    val DUPLICATE_PAIRS = listOf(
        Pair("G2", "G2D"), // Gray 77 (top center vs bottom right)
        Pair("G4", "G4D"), // Gray 51 (top left vs bottom right)
        Pair("K5", "K5D"), // Medium skin, mid-yellow (top right vs bottom left)
        Pair("K8", "K8D") // Dark skin, mid-yellow (top center vs bottom left)
    )

    // Gray ramp patches (ordered lightest G1 to darkest G7)
    val GRAY_RAMP_PATCH_IDS = listOf("G1", "G2", "G3", "G4", "G5", "G6", "G7")

    // Comprehensive list of all 23 patches per Table 4.2
    val PATCHES: Map<String, PatchDef> = listOf(
        // Gray ramp (7 steps)
        PatchDef("G1", "GRAY", RectPx(450, 30, 90, 90), RectPx(468, 48, 54, 54), Lab(90.0, 0.0, 0.0)),
        PatchDef("G2", "GRAY", RectPx(345, 30, 90, 90), RectPx(363, 48, 54, 54), Lab(77.0, 0.0, 0.0)),
        PatchDef("G3", "GRAY", RectPx(555, 30, 90, 90), RectPx(573, 48, 54, 54), Lab(64.0, 0.0, 0.0)),
        PatchDef("G4", "GRAY", RectPx(240, 30, 90, 90), RectPx(258, 48, 54, 54), Lab(51.0, 0.0, 0.0)), // Mid-gray
        PatchDef("G5", "GRAY", RectPx(660, 30, 90, 90), RectPx(678, 48, 54, 54), Lab(39.0, 0.0, 0.0)),
        PatchDef("G6", "GRAY", RectPx(450, 640, 90, 90), RectPx(468, 658, 54, 54), Lab(28.0, 0.0, 0.0)),
        PatchDef("G7", "GRAY", RectPx(555, 640, 90, 90), RectPx(573, 658, 54, 54), Lab(18.0, 0.0, 0.0)),

        // Skin & yellow tones (9 steps)
        PatchDef("K1", "SKIN", RectPx(240, 230, 90, 90), RectPx(258, 248, 54, 54), Lab(72.0, 10.0, 12.0)),
        PatchDef("K2", "SKIN", RectPx(1045, 230, 90, 90), RectPx(1063, 248, 54, 54), Lab(72.0, 8.0, 20.0)),
        PatchDef("K3", "SKIN", RectPx(65, 230, 90, 90), RectPx(83, 248, 54, 54), Lab(72.0, 5.0, 30.0)),
        PatchDef("K4", "SKIN", RectPx(240, 335, 90, 90), RectPx(258, 353, 54, 54), Lab(56.0, 12.0, 14.0)),
        PatchDef("K5", "SKIN", RectPx(870, 30, 90, 90), RectPx(888, 48, 54, 54), Lab(56.0, 10.0, 22.0)),
        PatchDef("K6", "SKIN", RectPx(65, 335, 90, 90), RectPx(83, 353, 54, 54), Lab(56.0, 6.0, 32.0)),
        PatchDef("K7", "SKIN", RectPx(240, 440, 90, 90), RectPx(258, 458, 54, 54), Lab(40.0, 12.0, 12.0)),
        PatchDef("K8", "SKIN", RectPx(765, 30, 90, 90), RectPx(783, 48, 54, 54), Lab(40.0, 9.0, 20.0)),
        PatchDef("K9", "SKIN", RectPx(65, 440, 90, 90), RectPx(83, 458, 54, 54), Lab(40.0, 6.0, 27.0)),

        // Saturated chromatic references (3 steps)
        PatchDef("X1", "SATURATED", RectPx(1045, 335, 90, 90), RectPx(1063, 353, 54, 54), Lab(80.0, 2.0, 70.0)),
        PatchDef("X2", "SATURATED", RectPx(660, 640, 90, 90), RectPx(678, 658, 54, 54), Lab(45.0, 52.0, 35.0)),
        PatchDef("X3", "SATURATED", RectPx(1045, 440, 90, 90), RectPx(1063, 458, 54, 54), Lab(35.0, 8.0, -42.0)),

        // Duplicate patches in opposite corners (4 patches)
        PatchDef("G2D", "GRAY", RectPx(765, 640, 90, 90), RectPx(783, 658, 54, 54), Lab(77.0, 0.0, 0.0)),
        PatchDef("G4D", "GRAY", RectPx(870, 640, 90, 90), RectPx(888, 658, 54, 54), Lab(51.0, 0.0, 0.0)),
        PatchDef("K5D", "SKIN", RectPx(240, 640, 90, 90), RectPx(258, 658, 54, 54), Lab(56.0, 10.0, 22.0)),
        PatchDef("K8D", "SKIN", RectPx(345, 640, 90, 90), RectPx(363, 658, 54, 54), Lab(40.0, 9.0, 20.0))
    ).associateBy { it.id }
}
