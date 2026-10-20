package com.kitalonlabs.neogold.core.security.export

import org.json.JSONObject

/**
 * Metadata header stored in header.json inside the encrypted export ZIP.
 */
data class ExportHeader(
    val exportFormatVersion: Int = 1,
    val csvSchemaVersion: Int = 1,
    val studyCode: String,
    val exportedAt: String,
    val totalParticipants: Int,
    val totalSessions: Int,
    val totalImages: Int,
    val hpkeKem: String = "DHKEM_X25519_HKDF_SHA256",
    val hpkeKdf: String = "HKDF_SHA256",
    val hpkeAead: String = "AES_256_GCM",
    val fileCipher: String = "AES256_GCM_HKDF_4KB",
    val contextInfo: String = "neogold-export-v1",
    val wrappedDataKeyB64: String,
    val manifestSha256: String
) {
    fun toJsonString(): String {
        val obj = JSONObject().apply {
            put("export_format_version", exportFormatVersion)
            put("csv_schema_version", csvSchemaVersion)
            put("study_code", studyCode)
            put("exported_at", exportedAt)
            put("total_participants", totalParticipants)
            put("total_sessions", totalSessions)
            put("total_images", totalImages)
            put("hpke_kem", hpkeKem)
            put("hpke_kdf", hpkeKdf)
            put("hpke_aead", hpkeAead)
            put("file_cipher", fileCipher)
            put("context_info", contextInfo)
            put("wrapped_data_key_b64", wrappedDataKeyB64)
            put("manifest_sha256", manifestSha256)
        }
        return obj.toString(2)
    }

    companion object {
        fun fromJsonString(jsonStr: String): ExportHeader {
            val obj = JSONObject(jsonStr)
            return ExportHeader(
                exportFormatVersion = obj.optInt("export_format_version", 1),
                csvSchemaVersion = obj.optInt("csv_schema_version", 1),
                studyCode = obj.getString("study_code"),
                exportedAt = obj.getString("exported_at"),
                totalParticipants = obj.optInt("total_participants", 0),
                totalSessions = obj.optInt("total_sessions", 0),
                totalImages = obj.optInt("total_images", 0),
                hpkeKem = obj.optString("hpke_kem", "DHKEM_X25519_HKDF_SHA256"),
                hpkeKdf = obj.optString("hpke_kdf", "HKDF_SHA256"),
                hpkeAead = obj.optString("hpke_aead", "AES_256_GCM"),
                fileCipher = obj.optString("file_cipher", "AES256_GCM_HKDF_4KB"),
                contextInfo = obj.optString("context_info", "neogold-export-v1"),
                wrappedDataKeyB64 = obj.getString("wrapped_data_key_b64"),
                manifestSha256 = obj.getString("manifest_sha256")
            )
        }
    }
}

/**
 * Checksum entry in manifest.sha256
 */
data class ManifestEntry(
    val sha256Hex: String,
    val relativePath: String
)

/**
 * Manifest parsing and generation utilities.
 */
object ManifestParser {
    fun parse(manifestContent: String): List<ManifestEntry> {
        val entries = mutableListOf<ManifestEntry>()
        manifestContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    entries.add(ManifestEntry(parts[0].lowercase(), parts[1].trim().replace('\\', '/')))
                }
            }
        }
        return entries
    }

    fun format(entries: List<ManifestEntry>): String {
        val sb = StringBuilder()
        entries.forEach { entry ->
            sb.append("${entry.sha256Hex.lowercase()}  ${entry.relativePath.replace('\\', '/')}\n")
        }
        return sb.toString()
    }
}

open class ExportSecurityException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

class DecryptionException(message: String, cause: Throwable? = null) : ExportSecurityException(message, cause)

class ManifestVerificationException(message: String, cause: Throwable? = null) : ExportSecurityException(message, cause)

class TamperedCiphertextException(message: String, cause: Throwable? = null) : ExportSecurityException(message, cause)
