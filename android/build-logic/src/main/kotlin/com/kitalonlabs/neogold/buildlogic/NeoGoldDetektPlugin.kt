package com.kitalonlabs.neogold.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class NeoGoldDetektPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                val commonConfigFile = rootProject.file("config/detekt/detekt.yml")
                val engineConfigFile = rootProject.file("config/detekt/detekt-engine.yml")
                val configFiles = mutableListOf(commonConfigFile)
                if (path.startsWith(":engine:") && engineConfigFile.exists()) {
                    configFiles += engineConfigFile
                }
                config.setFrom(configFiles)
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget = "21"
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                "detektPlugins"(libs.findLibrary("detekt-formatting").get())
            }
        }
    }
}
