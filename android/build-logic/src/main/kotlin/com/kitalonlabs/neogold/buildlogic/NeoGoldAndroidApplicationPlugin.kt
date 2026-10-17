package com.kitalonlabs.neogold.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class NeoGoldAndroidApplicationPlugin : Plugin<Project> {
    companion object {
        const val APP_VERSION_NAME = "1.0.0"

        fun versionCodeOf(name: String, build: Int): Int {
            val parts = name.split(".").map(String::toInt)
            require(parts.size == 3) { "Semantic versioning requires major.minor.patch" }
            val (major, minor, patch) = parts
            require(minor in 0..99 && patch in 0..99 && build in 0..99) { "version part out of range" }
            return major * 1_000_000 + minor * 10_000 + patch * 100 + build
        }
    }

    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            if (!pluginManager.hasPlugin("org.jetbrains.kotlin.android") && extensions.findByName("kotlin") == null) {
                pluginManager.apply("org.jetbrains.kotlin.android")
            }

            val buildNumber = providers.gradleProperty("buildNumber").map(String::toInt).getOrElse(1)

            extensions.configure<ApplicationExtension> {
                compileSdk = 36

                defaultConfig {
                    applicationId = "com.kitalonlabs.neogold"
                    minSdk = 26
                    targetSdk = 36
                    versionName = APP_VERSION_NAME
                    versionCode = versionCodeOf(APP_VERSION_NAME, buildNumber)
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                splits {
                    abi {
                        isEnable = true
                        reset()
                        include("arm64-v8a", "armeabi-v7a")
                        isUniversalApk = true
                    }
                }

                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                    debug {
                        applicationIdSuffix = ".debug"
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_21
                    targetCompatibility = JavaVersion.VERSION_21
                }

                testOptions {
                    unitTests.isReturnDefaultValues = true
                }
            }

            tasks.register("printVersionName") {
                doLast {
                    println(APP_VERSION_NAME)
                }
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                "implementation"(libs.findLibrary("androidx.core.ktx").get())
                "testImplementation"(libs.findLibrary("junit").get())
                "testImplementation"(libs.findLibrary("truth").get())
            }
        }
    }
}
