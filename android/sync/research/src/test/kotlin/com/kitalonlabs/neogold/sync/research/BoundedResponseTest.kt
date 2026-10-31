package com.kitalonlabs.neogold.sync.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.sync.research.api.MAX_RESPONSE_BYTES
import com.kitalonlabs.neogold.sync.research.api.boundedText
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A study server's answer is read only up to a limit, so a hostile or broken server cannot use up the phone's memory
 * (BUG-2026-083 item 34, doc 8 Section 6.1; TC-SYNC-03 for the failure being retried).
 */
class BoundedResponseTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(): String =
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.boundedText() }

    @Test
    fun anAnswerWithinTheLimitIsRead() {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        assertThat(get()).isEqualTo("""{"status":"ok"}""")
    }

    @Test
    fun anAnswerThatIsExactlyAtTheLimitIsRead() {
        server.enqueue(MockResponse().setBody("a".repeat(MAX_RESPONSE_BYTES.toInt())))

        assertThat(get()).hasLength(MAX_RESPONSE_BYTES.toInt())
    }

    @Test
    fun anAnswerOverTheLimitIsRefused_whetherItSaysItsSizeOrNot() {
        server.enqueue(MockResponse().setBody("a".repeat(MAX_RESPONSE_BYTES.toInt() + 1)))
        try {
            get()
            fail("expected an IOException")
        } catch (_: IOException) {
            // refused by the declared size
        }

        // a chunked answer states no size: it is cut at the limit instead
        server.enqueue(MockResponse().setChunkedBody("a".repeat(MAX_RESPONSE_BYTES.toInt() + 10), 8 * 1024))
        try {
            get()
            fail("expected an IOException")
        } catch (_: IOException) {
            // refused after the limit was passed
        }
    }

    @Test
    fun anEmptyAnswerIsEmptyText() {
        server.enqueue(MockResponse().setResponseCode(204))

        assertThat(get()).isEmpty()
    }
}
