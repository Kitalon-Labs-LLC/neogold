package com.kitalonlabs.neogold.engine.vision

import java.io.File
import javax.imageio.ImageIO

data class BadManifestEntry(
    val filename: String,
    val expectedReasons: List<QualityReasonCode>,
    val description: String,
    val sha256: String
)

data class GoldenManifestEntry(
    val filename: String,
    val expectedPass: Boolean,
    val description: String,
    val sha256: String
)

object TestImageUtils {

    /**
     * Resolves repo root from current working directory or test execution directory.
     */
    fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "card/spec/card-v1.json").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        val parent = File("..").canonicalFile
        if (File(parent, "card/spec/card-v1.json").exists()) {
            return parent
        }
        return File(".")
    }

    /**
     * Loads a PNG image into an ImageFrame.
     */
    fun loadPngImage(file: File): ImageFrame {
        require(file.exists()) { "File not found: ${file.absolutePath}" }
        val img = ImageIO.read(file)
            ?: throw IllegalArgumentException("Could not decode image from ${file.absolutePath}")
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        return ImageFrame(w, h, pixels)
    }

    /**
     * Parses testdata/bad/manifest.json without using Android stub classes.
     */
    fun parseBadManifest(file: File): List<BadManifestEntry> {
        val lines = file.readLines()
        val entries = mutableListOf<BadManifestEntry>()
        var curFilename = ""
        var curDesc = ""
        var curSha = ""
        val curReasons = mutableListOf<QualityReasonCode>()
        var inReasons = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("\"filename\"")) {
                curFilename = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("\"expected_reasons\"")) {
                inReasons = true
                curReasons.clear()
            } else if (inReasons) {
                if (line.startsWith("]")) {
                    inReasons = false
                } else {
                    val codeStr = line.trim('"', ',')
                    if (codeStr.isNotEmpty()) {
                        curReasons.add(QualityReasonCode.valueOf(codeStr))
                    }
                }
            } else if (line.startsWith("\"description\"")) {
                curDesc = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("\"sha256\"")) {
                curSha = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("}") && curFilename.isNotEmpty()) {
                entries.add(
                    BadManifestEntry(
                        filename = curFilename,
                        expectedReasons = curReasons.toList(),
                        description = curDesc,
                        sha256 = curSha
                    )
                )
                curFilename = ""
                curDesc = ""
                curSha = ""
                curReasons.clear()
            }
        }

        return entries
    }

    /**
     * Parses testdata/golden/vision/manifest.json without using Android stub classes.
     */
    fun parseGoldenManifest(file: File): List<GoldenManifestEntry> {
        val lines = file.readLines()
        val entries = mutableListOf<GoldenManifestEntry>()
        var curFilename = ""
        var curPass = true
        var curDesc = ""
        var curSha = ""

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("\"filename\"")) {
                curFilename = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("\"expected_pass\"")) {
                curPass = line.substringAfter(":").trim().trim(',').toBoolean()
            } else if (line.startsWith("\"description\"")) {
                curDesc = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("\"sha256\"")) {
                curSha = line.substringAfter(":").trim().trim('"', ',')
            } else if (line.startsWith("}") && curFilename.isNotEmpty()) {
                entries.add(
                    GoldenManifestEntry(
                        filename = curFilename,
                        expectedPass = curPass,
                        description = curDesc,
                        sha256 = curSha
                    )
                )
                curFilename = ""
                curPass = true
                curDesc = ""
                curSha = ""
            }
        }

        return entries
    }
}
