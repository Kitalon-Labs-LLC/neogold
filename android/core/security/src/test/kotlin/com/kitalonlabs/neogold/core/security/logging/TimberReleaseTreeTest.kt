package com.kitalonlabs.neogold.core.security.logging

import android.util.Log
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class TimberReleaseTreeTest {

    @Test
    fun releaseTree_dropsBelowWarn_TC_SEC_03() {
        val tree = TimberReleaseTree()

        assertThat(tree.isLoggable("TAG", Log.VERBOSE)).isFalse()
        assertThat(tree.isLoggable("TAG", Log.DEBUG)).isFalse()
        assertThat(tree.isLoggable("TAG", Log.INFO)).isFalse()

        assertThat(tree.isLoggable("TAG", Log.WARN)).isTrue()
        assertThat(tree.isLoggable("TAG", Log.ERROR)).isTrue()
        assertThat(tree.isLoggable("TAG", Log.ASSERT)).isTrue()
    }

    @Test
    fun releaseTree_redactsExceptionMessage() {
        ShadowLog.clear()
        val tree = TimberReleaseTree()
        timber.log.Timber.uprootAll()
        timber.log.Timber.plant(tree)

        val exception = RuntimeException("SensitiveRegNo: 12345")
        timber.log.Timber.tag("NeoGold").e(exception, "Error occurred: SensitiveRegNo: 12345")

        val logs = ShadowLog.getLogs()
        val entry = logs.firstOrNull { it.tag == "NeoGold" } ?: logs.last()
        assertThat(entry.msg).doesNotContain("12345")
        assertThat(entry.msg).contains("[REDACTED_MESSAGE]")
        timber.log.Timber.uprootAll()
    }
}
