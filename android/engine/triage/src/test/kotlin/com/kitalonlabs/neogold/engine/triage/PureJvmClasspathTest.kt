package com.kitalonlabs.neogold.engine.triage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PureJvmClasspathTest {
    @Test
    fun testNoAndroidClassesOnClasspath() {
        val hasAndroid = try {
            Class.forName("android.os.Bundle")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        assertThat(hasAndroid).isFalse()
    }
}
