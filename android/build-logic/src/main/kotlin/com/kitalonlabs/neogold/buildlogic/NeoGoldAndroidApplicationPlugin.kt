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
            pluginManager.apply(NeoGoldDetektPlugin::class.java)

            val buildNumber = providers.gradleProperty("buildNumber").map(String::toInt).getOrElse(1)
            val effectiveVersionName = providers.gradleProperty("versionName").getOrElse(
                providers.gradleProperty("appVersionName").getOrElse(APP_VERSION_NAME)
            )

            extensions.configure<ApplicationExtension> {
                compileSdk = 36

                defaultConfig {
                    applicationId = "com.kitalonlabs.neogold"
                    minSdk = 26
                    targetSdk = 36
                    versionName = effectiveVersionName
                    versionCode = versionCodeOf(effectiveVersionName, buildNumber)
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                // AGP 9 refuses to build an App Bundle while ABI splits are on (issuetracker 402800800), and Play
                // makes the ABI splits from the bundle itself, so the per-ABI APKs exist for APK builds only.
                val buildsBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
                splits {
                    abi {
                        isEnable = !buildsBundle
                        reset()
                        include("arm64-v8a", "armeabi-v7a")
                        isUniversalApk = true
                    }
                }

                val uploadKeystore = providers.gradleProperty("uploadKeystore").orNull
                    ?: providers.environmentVariable("UPLOAD_KEYSTORE").orNull
                val uploadKeystorePassword = providers.gradleProperty("uploadKeystorePassword").orNull
                    ?: providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").orNull
                val uploadKeyAlias = providers.gradleProperty("uploadKeyAlias").orNull
                    ?: providers.environmentVariable("UPLOAD_KEY_ALIAS").orNull
                val uploadKeyPassword = providers.gradleProperty("uploadKeyPassword").orNull
                    ?: providers.environmentVariable("UPLOAD_KEY_PASSWORD").orNull

                val releaseSigning = if (!uploadKeystore.isNullOrBlank() && file(uploadKeystore).exists()) {
                    signingConfigs.create("release") {
                        storeFile = file(uploadKeystore)
                        storePassword = uploadKeystorePassword ?: ""
                        this.keyAlias = uploadKeyAlias ?: ""
                        this.keyPassword = uploadKeyPassword ?: ""
                    }
                } else null

                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        // Only the two supported ABIs (CLAUDE.md); OpenCV's x86 and x86_64 libraries add 100 MB.
                        ndk {
                            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
                        }
                        if (releaseSigning != null) {
                            signingConfig = releaseSigning
                        }
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
                    println(effectiveVersionName)
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
