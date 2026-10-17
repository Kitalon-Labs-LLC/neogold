package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppArchitectureTest {
    @Test
    fun testAppNamespace() {
        val packageName = MainActivity::class.java.`package`?.name
        assertThat(packageName).isEqualTo("com.kitalonlabs.neogold")
    }
}
