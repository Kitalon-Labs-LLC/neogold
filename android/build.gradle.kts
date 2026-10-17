// Top-level build file for NeoGold Android project
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

// Build-time Guardrails & Architecture Enforcement
val httpDisallowedGroups = listOf("com.squareup.okhttp3", "io.ktor", "org.apache.httpcomponents", "com.android.volley")
val pureEngineModules = listOf(":engine:colorscience", ":engine:estimation", ":engine:triage", ":engine:cardtrust")

subprojects {
    afterEvaluate {
        // Guardrail 1: HTTP client isolation (doc 8 Section 6.1)
        if (path != ":sync:research") {
            configurations.configureEach {
                dependencies.configureEach {
                    val g = group ?: ""
                    val n = name
                    if (g in httpDisallowedGroups || n.contains("okhttp") || n.contains("ktor")) {
                        throw GradleException(
                            "Dependency guardrail violation: Module '$path' is not permitted to depend on HTTP client '$g:$n'. Only ':sync:research' may depend on HTTP clients."
                        )
                    }
                }
            }
        }

        // Guardrail 2: Pure engine JVM isolation (doc 4 Section 4.2)
        if (path in pureEngineModules) {
            configurations.configureEach {
                dependencies.configureEach {
                    val g = group ?: ""
                    if (g.startsWith("androidx.") || g.startsWith("com.android.") || g.startsWith("com.google.android.")) {
                        throw GradleException(
                            "Pure engine guardrail violation: Pure engine module '$path' must not depend on Android artifacts ('$group:$name')."
                        )
                    }
                }
            }
        }
    }
}

// Global Lifecycle Verification & Build Tasks
tasks.register("testReleaseUnitTest") {
    description = "Runs unit tests across all Android and JVM modules"
    group = "verification"
    dependsOn(subprojects.map { subproject ->
        subproject.tasks.matching { task ->
            task.name == "testReleaseUnitTest" || task.name == "test"
        }
    })
}

tasks.register("lintRelease") {
    description = "Runs lint on Android modules"
    group = "verification"
    dependsOn(subprojects.map { subproject ->
        subproject.tasks.matching { task ->
            task.name == "lintRelease"
        }
    })
}

tasks.register("detekt") {
    description = "Runs detekt analysis"
    group = "verification"
    dependsOn(subprojects.map { subproject ->
        subproject.tasks.matching { task ->
            task.name == "detekt"
        }
    })
}

tasks.register("assembleRelease") {
    description = "Assembles release artifacts"
    group = "build"
    dependsOn(":app:assembleRelease")
}

// Wire check task
tasks.register("check") {
    description = "Runs all verification checks"
    group = "verification"
    dependsOn("testReleaseUnitTest", "lintRelease")
}

// Placeholders for checks added in later work packages
tasks.register("verifyGoldenVectors") {
    description = "Golden vectors verification (implemented in P04/P06)"
    doLast { println("TODO [P04/P06]: verifyGoldenVectors placeholder") }
}

tasks.register("triageDifferentialTest") {
    description = "Triage differential test against Python reference (implemented in P03)"
    doLast { println("TODO [P03]: triageDifferentialTest placeholder") }
}

tasks.register("verifyConfigSchema") {
    description = "Config JSON schema verification (implemented in P03)"
    doLast { println("TODO [P03]: verifyConfigSchema placeholder") }
}
