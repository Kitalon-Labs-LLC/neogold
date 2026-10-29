package com.kitalonlabs.neogold.sync.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.sync.research.api.apiBaseUrl
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.DeviceAssertionMinter
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.enrollment.studyBaseUrl
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The server a study profile names is where the phone sends research data and its device assertions, so the address is
 * checked before anything is sent (TC-API-07, doc 7 Section 4.2; privacy review of package B2).
 */
class StudyBaseUrlTest {

    @Test
    fun aPlainHostBecomesTheHttpsBaseUrlEndingInV1() {
        assertThat(studyBaseUrl("intake.example.bd")).isEqualTo("https://intake.example.bd/v1")
        assertThat(studyBaseUrl("https://intake.example.bd/v1")).isEqualTo("https://intake.example.bd/v1")
        assertThat(studyBaseUrl("https://intake.example.bd/v1/")).isEqualTo("https://intake.example.bd/v1")
        assertThat(studyBaseUrl("https://intake.example.bd:8443")).isEqualTo("https://intake.example.bd:8443/v1")
    }

    @Test
    fun theHostIsLowerCasedAndTheDefaultPortDropped_asTheServerComparesThem() {
        assertThat(studyBaseUrl("HTTPS://Intake.Example.BD:443/v1")).isEqualTo("https://intake.example.bd/v1")
        assertThat(apiBaseUrl("https://Intake.Example.BD:443")).isEqualTo("https://intake.example.bd/v1")
    }

    @Test
    fun cleartextIsRefusedUnlessTheTestsAskForIt() {
        assertThrows(IllegalArgumentException::class.java) { studyBaseUrl("http://intake.example.bd") }
        assertThat(studyBaseUrl("http://localhost:8080", allowCleartext = true)).isEqualTo("http://localhost:8080/v1")
    }

    @Test
    fun anAddressWithAUserNameAQueryAFragmentOrAnotherPathIsRefused() {
        val refused = listOf(
            "https://good@evil.example/v1",
            "https://good:secret@evil.example",
            "https://intake.example.bd?x=/v1",
            "https://intake.example.bd/v1?x=1",
            "https://intake.example.bd/v1#frag",
            "https://intake.example.bd/v1/v1",
            "https://intake.example.bd/api",
            "not an address at all"
        )
        for (host in refused) {
            assertThrows(host, IllegalArgumentException::class.java) { studyBaseUrl(host) }
        }
    }

    @Test
    fun aTokenAudienceWithAUserNameOrAQueryIsNotMinted() {
        val keys = SoftwareDeviceKeyManager()
        keys.generateDeviceKey("alias", "7K2X-M49P-3ABC")
        for (aud in listOf("https://good@evil.example/v1", "https://intake.example.bd/v1?x=/v1")) {
            assertThrows(aud, IllegalArgumentException::class.java) {
                DeviceAssertionMinter.mintToken(
                    deviceId = "d",
                    keyAlias = "alias",
                    baseAudUrl = aud,
                    httpMethod = "GET",
                    requestUrl = "https://intake.example.bd/v1/device",
                    bodyBytes = null,
                    imageSha256Hex = null,
                    clockManager = ClockManager(),
                    keyManager = keys
                )
            }
        }
    }
}
