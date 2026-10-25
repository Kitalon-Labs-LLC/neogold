package com.kitalonlabs.neogold.sync.research.crypto

import java.util.UUID

object DeviceAssertionMinter {

    val EMPTY_BODY_BSH: String by lazy {
        Base64Url.encode(Base64Url.sha256(ByteArray(0)))
    }

    fun mintToken(
        deviceId: String,
        keyAlias: String,
        baseAudUrl: String,
        httpMethod: String,
        requestUrl: String,
        bodyBytes: ByteArray?,
        imageSha256Hex: String?,
        clockManager: ClockManager,
        keyManager: DeviceKeyManager
    ): String {
        val iat = clockManager.currentServerAdjustedEpochSeconds()
        val exp = iat + 300
        val jti = UUID.randomUUID().toString()

        val bsh = when {
            imageSha256Hex != null -> Base64Url.encode(Base64Url.hexToBytes(imageSha256Hex))
            bodyBytes != null && bodyBytes.isNotEmpty() -> Base64Url.encode(Base64Url.sha256(bodyBytes))
            else -> EMPTY_BODY_BSH
        }

        val aud = baseAudUrl.trimEnd('/')
        // Normalize request URL (strip query parameters if any)
        val htu = requestUrl.substringBefore('?').substringBefore('#')

        // Compact JSON serialization for JOSE header and claims
        val headerJson = "{\"alg\":\"ES256\",\"typ\":\"ng-device+jwt\",\"kid\":\"$deviceId\"}"
        val claimsJson = "{\"iss\":\"$deviceId\",\"aud\":\"$aud\",\"iat\":$iat,\"exp\":$exp,\"jti\":\"$jti\",\"htm\":\"${httpMethod.uppercase()}\",\"htu\":\"$htu\",\"bsh\":\"$bsh\"}"

        val headerB64 = Base64Url.encode(headerJson.toByteArray(Charsets.UTF_8))
        val claimsB64 = Base64Url.encode(claimsJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimsB64"

        val joseSig = keyManager.signPayload(keyAlias, signingInput.toByteArray(Charsets.US_ASCII))
        val sigB64 = Base64Url.encode(joseSig)

        return "$signingInput.$sigB64"
    }
}
