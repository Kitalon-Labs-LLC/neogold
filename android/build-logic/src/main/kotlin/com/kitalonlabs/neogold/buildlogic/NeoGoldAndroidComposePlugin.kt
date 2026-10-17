package com.kitalonlabs.neogold.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class NeoGoldAndroidComposePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            if (!pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose")) {
                pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            }

            extensions.findByType(CommonExtension::class.java)?.apply {
                buildFeatures.compose = true
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                val bom = libs.findLibrary("androidx.compose.bom").get()
                "implementation"(platform(bom))
                "implementation"(libs.findLibrary("androidx.compose.ui").get())
                "implementation"(libs.findLibrary("androidx.compose.ui.graphics").get())
                "implementation"(libs.findLibrary("androidx.compose.ui.tooling.preview").get())
                "implementation"(libs.findLibrary("androidx.compose.material3").get())
                "debugImplementation"(libs.findLibrary("androidx.compose.ui.tooling").get())
            }
        }
    }
}
