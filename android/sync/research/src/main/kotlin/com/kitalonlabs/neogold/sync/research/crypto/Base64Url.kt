package com.kitalonlabs.neogold.sync.research.crypto

import java.security.MessageDigest
import java.util.Base64

object Base64Url {
    fun encode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(str: String): ByteArray {
        val padded = when (str.length % 4) {
            2 -> "$str=="
            3 -> "$str="
            else -> str
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    fun encodeStandard(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeStandard(str: String): ByteArray {
        return Base64.getDecoder().decode(str)
    }

    fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
