package com.kitalonlabs.neogold.sync.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.sync.research.api.apiBaseUrl
import com.kitalonlabs.neogold.sync.research.api.requestUrl
import com.kitalonlabs.neogold.sync.research.crypto.DeviceAssertionMinter
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The device assertion's `aud` is the API base URL with /v1, and `htu` is that base plus the path (doc 7 Section 4.2).
 * The phone used to put the study host, without /v1, in `aud`, so the server answered 401 AUTH_CLAIM_MISMATCH to every
 * authenticated call (package B2). `testdata/api/assertion_claims_vector.json` is read by this test and by the
 * backend's `test_assertion_claims_vector.py`, so the two sides cannot drift apart.
 */
class DeviceAssertionAudienceTest {

    private val vector: JsonObject by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "testdata/api/assertion_claims_vector.json").isFile) {
            dir = dir.parentFile
        }
        Json.parseToJsonElement(File(dir, "testdata/api/assertion_claims_vector.json").readText()).jsonObject
    }

    private fun text(name: String) = vector.getValue(name).jsonPrimitive.content

    private fun claimsOf(token: String): JsonObject {
        val payload = Base64.getUrlDecoder().decode(token.split(".")[1])
        return Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
    }

    private fun mint(baseAudUrl: String, requestUrl: String): String {
        val keys = SoftwareDeviceKeyManager()
        keys.generateDeviceKey("alias", "7K2X-M49P-3ABC")
        return DeviceAssertionMinter.mintToken(
            deviceId = text("device_id"),
            keyAlias = "alias",
            baseAudUrl = baseAudUrl,
            httpMethod = text("method"),
            requestUrl = requestUrl,
            bodyBytes = text("body").toByteArray(Charsets.UTF_8),
            imageSha256Hex = null,
            keyManager = keys,
            issuedAt = vector.getValue("iat").jsonPrimitive.content.toLong(),
            jti = text("jti")
        )
    }

    @Test
    fun theClaimsTheClientMints_matchTheVectorTheServerChecks() {
        // The study profile carries a host; the client derives both the audience and the URL from it.
        val host = "https://" + text("study_host")
        val claims = claimsOf(mint(apiBaseUrl(host), requestUrl(host, text("path"))))

        val expected = vector.getValue("expected_claims").jsonObject
        for ((name, value) in expected) {
            assertThat(claims.getValue(name)).isEqualTo(value)
        }
        assertThat(claims.keys).containsExactlyElementsIn(expected.keys)
    }

    @Test
    fun theAudienceEndsInV1_whetherOrNotTheProfileHostDoes() {
        assertThat(apiBaseUrl("https://intake.example.bd")).isEqualTo("https://intake.example.bd/v1")
        assertThat(apiBaseUrl("https://intake.example.bd/")).isEqualTo("https://intake.example.bd/v1")
        assertThat(apiBaseUrl("https://intake.example.bd/v1")).isEqualTo("https://intake.example.bd/v1")
        assertThat(apiBaseUrl("https://intake.example.bd/v1/")).isEqualTo("https://intake.example.bd/v1")
        assertThat(requestUrl("https://intake.example.bd", "/device"))
            .isEqualTo(requestUrl("https://intake.example.bd/v1", "device"))
    }

    @Test
    fun anAudienceWithoutV1_isRefusedInsteadOfMintingATokenTheServerWillReject() {
        val host = "https://" + text("study_host")

        assertThrows(IllegalArgumentException::class.java) {
            mint(baseAudUrl = host, requestUrl = requestUrl(host, text("path")))
        }
    }

    @Test
    fun theVectorItselfIsWhatTheDocumentationSays() {
        val expected = vector.getValue("expected_claims").jsonObject
        assertThat(expected.getValue("aud")).isEqualTo(JsonPrimitive(text("public_base_url")))
        assertThat(expected.getValue("htu").jsonPrimitive.content)
            .isEqualTo(text("public_base_url") + text("path"))
    }
}
