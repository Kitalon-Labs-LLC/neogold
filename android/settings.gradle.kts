pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "neogold"

include(":app")

// Features
include(":feature:onboarding")
include(":feature:calibration")
include(":feature:screening")
include(":feature:result")
include(":feature:log")
include(":feature:research")
include(":feature:settings")

// Engines
include(":engine:camera")
include(":engine:vision")
include(":engine:colorscience")
include(":engine:estimation")
include(":engine:triage")
include(":engine:cardtrust")

// Core
include(":core:model")
include(":core:data")
include(":core:security")
include(":core:designsystem")
include(":core:common")
include(":core:testing")

// Sync
include(":sync:research")

// Benchmark & Performance
include(":benchmark")

// Tools
include(":neogold-decrypt")
project(":neogold-decrypt").projectDir = file("../tools/neogold-decrypt")
