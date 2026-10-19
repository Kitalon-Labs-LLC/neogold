package com.kitalonlabs.neogold.core.designsystem

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TC-UI-04: Banned words (H11, doc 6 Section 10.2).
 * TC-A11Y-01: Every string translated (NFR-06).
 *
 * Verifies that:
 * 1. Both English and Bangla strings files have identical keys with zero missing entries.
 * 2. None of the banned words in English or Bangla appear anywhere in the string resources.
 */
class StringLintTest {

    private lateinit var englishStrings: Map<String, String>
    private lateinit var banglaStrings: Map<String, String>

    // Banned words list from doc 6 Section 10.2
    private val englishBannedWords = listOf(
        "safe",
        "normal",
        "healthy",
        "fine",
        "no jaundice",
        "diagnosis",
        "diagnose",
        "accurate",
        "precise",
        "ai",
        "artificial intelligence"
    )

    private val banglaBannedWords = listOf(
        "নিরাপদ",
        "স্বাভাবিক",
        "সুস্থ",
        "ঠিক আছে",
        "জন্ডিস নেই",
        "রোগ নির্ণয়",
        "নির্ভুল",
        "সঠিক মাপ",
        "এআই",
        "কৃত্রিম বুদ্ধিমত্তা"
    )

    @Before
    fun setUp() {
        val rootDir = findRepoRoot()
        val resDir = File(rootDir, "android/core/designsystem/src/main/res")
        val enFile = File(resDir, "values/strings.xml")
        val bnFile = File(resDir, "values-bn/strings.xml")

        assertWithMessage("English strings file must exist: ${enFile.absolutePath}").that(enFile.exists()).isTrue()
        assertWithMessage("Bangla strings file must exist: ${bnFile.exists()}").that(bnFile.exists()).isTrue()

        englishStrings = parseStringsXml(enFile)
        banglaStrings = parseStringsXml(bnFile)
    }

    @Test
    fun testTc_A11y_01_EveryStringTranslated_NoMissingKeys() {
        val enKeys = englishStrings.keys
        val bnKeys = banglaStrings.keys

        val missingInBangla = enKeys - bnKeys
        val missingInEnglish = bnKeys - enKeys

        assertWithMessage("Keys present in English but missing in Bangla: $missingInBangla")
            .that(missingInBangla)
            .isEmpty()

        assertWithMessage("Keys present in Bangla but missing in English: $missingInEnglish")
            .that(missingInEnglish)
            .isEmpty()

        // Verify core clinical keys exist from doc 6 Section 10.4
        val coreKeys = listOf(
            "action_refer_urgent",
            "action_blood_test",
            "action_recheck",
            "action_no_further",
            "next_no_further",
            "confirm_blood",
            "experimental",
            "cannot_screen",
            "q_early_jaundice",
            "q_danger_signs",
            "q_phototherapy",
            "birth_time",
            "ga_label",
            "place_card",
            "hold_still",
            "phone_qualified",
            "phone_research_only",
            "result_suggested",
            "result_you_decide"
        )
        for (key in coreKeys) {
            assertThat(englishStrings).containsKey(key)
            assertThat(banglaStrings).containsKey(key)
        }
    }

    @Test
    fun testTc_Ui_04_NoBannedWordsInEnglishStrings() {
        val violations = mutableListOf<String>()

        for ((key, value) in englishStrings) {
            val lower = value.lowercase()
            for (banned in englishBannedWords) {
                // Check whole word match or phrase match
                val regex = Regex("\\b${Regex.escape(banned)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(lower)) {
                    violations.add("Key '$key' contains English banned word '$banned' in: \"$value\"")
                }
            }
        }

        assertWithMessage("Found banned words in English strings:\n" + violations.joinToString("\n"))
            .that(violations)
            .isEmpty()
    }

    @Test
    fun testTc_Ui_04_NoBannedWordsInBanglaStrings() {
        val violations = mutableListOf<String>()

        for ((key, value) in banglaStrings) {
            for (banned in banglaBannedWords) {
                val pattern = if (banned == "স্বাভাবিক") {
                    // "অস্বাভাবিক" is the danger sign "unusually sleepy" from doc 6 Section 10.4,
                    // so we ensure "স্বাভাবিক" (normal/fine) does not match inside "অস্বাভাবিক".
                    Regex("(?<!অ)স্বাভাবিক")
                } else {
                    Regex(Regex.escape(banned))
                }

                if (pattern.containsMatchIn(value)) {
                    violations.add("Key '$key' contains Bangla banned word '$banned' in: \"$value\"")
                }
            }
        }

        assertWithMessage("Found banned words in Bangla strings:\n" + violations.joinToString("\n"))
            .that(violations)
            .isEmpty()
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val strings = mutableMapOf<String, String>()

        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            val text = node.textContent.trim()
            strings[name] = text
        }
        return strings
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "android").isDirectory && File(dir, "docs").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        return File("../../..").canonicalFile
    }
}
