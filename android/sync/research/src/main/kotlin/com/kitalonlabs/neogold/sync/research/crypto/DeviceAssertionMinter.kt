package com.kitalonlabs.neogold.sync.research.crypto

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

object DeviceAssertionMinter {

    val EMPTY_BODY_BSH: String by lazy {
        Base64Url.encode(Base64Url.sha256(ByteArray(0)))
    }

    @Suppress("LongParameterList") // one parameter per claim input; the claims are the contract in doc 7 Section 4.2
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
    ): String = mintToken(
        deviceId, keyAlias, baseAudUrl, httpMethod, requestUrl, bodyBytes, imageSha256Hex, keyManager,
        issuedAt = clockManager.currentServerAdjustedEpochSeconds(),
        jti = UUID.randomUUID().toString()
    )

    /** The same token with the time and the identifier given, so a test can compare it with the shared vector. */
    @Suppress("LongParameterList") // one parameter per claim input; the claims are the contract in doc 7 Section 4.2
    internal fun mintToken(
        deviceId: String,
        keyAlias: String,
        baseAudUrl: String,
        httpMethod: String,
        requestUrl: String,
        bodyBytes: ByteArray?,
        imageSha256Hex: String?,
        keyManager: DeviceKeyManager,
        issuedAt: Long,
        jti: String
    ): String {
        val iat = issuedAt
        val exp = iat + ASSERTION_TTL_SECONDS

        val bsh = when {
            imageSha256Hex != null -> Base64Url.encode(Base64Url.hexToBytes(imageSha256Hex))
            bodyBytes != null && bodyBytes.isNotEmpty() -> Base64Url.encode(Base64Url.sha256(bodyBytes))
            else -> EMPTY_BODY_BSH
        }

        val aud = baseAudUrl.trimEnd('/')
        // The server compares `aud` with its public base URL, which ends in /v1 (doc 7 Section 4.2). A host without
        // it was a 401 on every call (audit finding, package B2): refuse to mint one.
        require(aud.endsWith("/v1")) { "aud must be the API base URL, ending in /v1" }
        val audUrl = aud.toHttpUrlOrNull()
        require(
            audUrl != null && audUrl.username.isEmpty() && audUrl.password.isEmpty() && audUrl.query == null && audUrl.fragment == null
        ) {
            "aud must be a plain address: no user name, query or fragment"
        }
        // Normalize request URL (strip query parameters if any)
        val htu = requestUrl.substringBefore('?').substringBefore('#')

        // Compact JSON serialization for JOSE header and claims
        val headerJson = "{\"alg\":\"ES256\",\"typ\":\"ng-device+jwt\",\"kid\":\"$deviceId\"}"
        val claimsJson = "{\"iss\":\"$deviceId\",\"aud\":\"$aud\",\"iat\":$iat,\"exp\":$exp,\"jti\":\"$jti\"," +
            "\"htm\":\"${httpMethod.uppercase()}\",\"htu\":\"$htu\",\"bsh\":\"$bsh\"}"

        val headerB64 = Base64Url.encode(headerJson.toByteArray(Charsets.UTF_8))
        val claimsB64 = Base64Url.encode(claimsJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimsB64"

        val joseSig = keyManager.signPayload(keyAlias, signingInput.toByteArray(Charsets.US_ASCII))
        val sigB64 = Base64Url.encode(joseSig)

        return "$signingInput.$sigB64"
    }
}

private const val ASSERTION_TTL_SECONDS = 300
