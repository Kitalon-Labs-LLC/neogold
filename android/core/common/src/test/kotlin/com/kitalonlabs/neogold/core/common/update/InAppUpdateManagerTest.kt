package com.kitalonlabs.neogold.core.common.update

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * TC-ONB-06: In-app update prompt (NG-055, doc 12 Section 6).
 *
 * Verifies with FakeAppUpdateManager that:
 * - Priority 2 shows the flexible prompt
 * - Priority 4 or 5 starts the immediate flow
 */
class InAppUpdateManagerTest {

    private lateinit var fakeUpdateManager: FakeAppUpdateManager

    @Before
    fun setUp() {
        fakeUpdateManager = FakeAppUpdateManager()
    }

    @Test
    fun testPriority2TriggersFlexiblePrompt() {
        fakeUpdateManager.setUpdateAvailable(priority = 2)
        val status = fakeUpdateManager.checkForUpdate()

        assertThat(status.isUpdateAvailable).isTrue()
        assertThat(status.updatePriority).isEqualTo(2)
        assertThat(status.flowType).isEqualTo(UpdateFlowType.FLEXIBLE)

        fakeUpdateManager.startUpdate(status.flowType)
        assertThat(fakeUpdateManager.lastTriggeredFlow).isEqualTo(UpdateFlowType.FLEXIBLE)
    }

    @Test
    fun testPriority4StartsImmediateFlow() {
        fakeUpdateManager.setUpdateAvailable(priority = 4)
        val status = fakeUpdateManager.checkForUpdate()

        assertThat(status.isUpdateAvailable).isTrue()
        assertThat(status.updatePriority).isEqualTo(4)
        assertThat(status.flowType).isEqualTo(UpdateFlowType.IMMEDIATE)

        fakeUpdateManager.startUpdate(status.flowType)
        assertThat(fakeUpdateManager.lastTriggeredFlow).isEqualTo(UpdateFlowType.IMMEDIATE)
    }

    @Test
    fun testPriority5StartsImmediateFlow() {
        fakeUpdateManager.setUpdateAvailable(priority = 5)
        val status = fakeUpdateManager.checkForUpdate()

        assertThat(status.isUpdateAvailable).isTrue()
        assertThat(status.updatePriority).isEqualTo(5)
        assertThat(status.flowType).isEqualTo(UpdateFlowType.IMMEDIATE)

        fakeUpdateManager.startUpdate(status.flowType)
        assertThat(fakeUpdateManager.lastTriggeredFlow).isEqualTo(UpdateFlowType.IMMEDIATE)
    }

    @Test
    fun testNoUpdateReturnsNone() {
        fakeUpdateManager.setUpdateNotAvailable()
        val status = fakeUpdateManager.checkForUpdate()

        assertThat(status.isUpdateAvailable).isFalse()
        assertThat(status.flowType).isEqualTo(UpdateFlowType.NONE)
    }
}
