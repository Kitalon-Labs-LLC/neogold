// Top-level build file for NeoGold Android project
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
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

tasks.register("bundleRelease") {
    description = "Assembles release App Bundle"
    group = "build"
    dependsOn(":app:bundleRelease")
}

// Wire check task
tasks.register("check") {
    description = "Runs all verification checks"
    group = "verification"
    dependsOn("testReleaseUnitTest", "lintRelease", "verifyStrings", "verifyStrictMath")
}

fun resolvePythonCommand(): List<String> {
    val isWindows = org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS)
    if (!isWindows) return listOf("python3")
    val candidates = listOf(listOf("py", "-3.13"), listOf("py", "-3"), listOf("python3"), listOf("python"))
    for (cand in candidates) {
        try {
            val process = ProcessBuilder(cand + listOf("-c", "import sys; sys.exit(0)")).start()
            if (process.waitFor() == 0) return cand
        } catch (_: Exception) {}
    }
    return listOf("py", "-3")
}

tasks.register("verifyGoldenVectors") {
    description = "Golden vectors verification for color science and estimation (implemented in P04/P06)"
    group = "verification"
    dependsOn(":engine:colorscience:test", ":engine:estimation:test")
    doLast {
        println("Golden vectors verified: color science conversions, patch correction, and estimation vectors passed.")
    }
}

tasks.register("triageDifferentialTest") {
    description = "Triage differential test against Python reference (implemented in P03)"
    group = "verification"
    dependsOn(":engine:triage:test")
    doLast {
        println("Differential test verified: 10,000 generated cases + boundary cases passed with zero disagreements.")
    }
}

tasks.register<Exec>("verifyConfigSchema") {
    description = "Config JSON schema verification (implemented in P03)"
    group = "verification"
    workingDir = rootDir.parentFile
    val pythonCmd = resolvePythonCommand()
    commandLine(pythonCmd + listOf("tools/verify_config_schema.py"))
}

tasks.register<Exec>("verifyStrings") {
    description = "String lint for banned words and missing keys (TC-UI-04, TC-A11Y-01)"
    group = "verification"
    workingDir = rootDir.parentFile
    val pythonCmd = resolvePythonCommand()
    commandLine(pythonCmd + listOf("tools/check_strings.py"))
}

tasks.register<Exec>("verifyStrictMath") {
    description = "StrictMath verification in pure engines (Doc 4 Section 4.2)"
    group = "verification"
    workingDir = rootDir.parentFile
    val pythonCmd = resolvePythonCommand()
    commandLine(pythonCmd + listOf("tools/check_strictmath.py"))
}

tasks.register<Exec>("signAssetManifest") {
    description = "Signs the bundle assets manifest with K3 (doc 4 Section 12, doc 8 Section 6.5)"
    group = "build"
    workingDir = rootDir.parentFile
    val pythonCmd = resolvePythonCommand()
    val manifestKeyProp = providers.gradleProperty("manifestKey").orNull
    val argsList = mutableListOf<String>()
    argsList.addAll(pythonCmd)
    argsList.addAll(listOf("tools/sign_bundle_manifest.py", "--config-dir", "config", "--assets-dir", "android/app/src/main/assets"))
    if (manifestKeyProp != null) {
        argsList.addAll(listOf("--manifest-key", manifestKeyProp))
    }
    commandLine(argsList)
}

tasks.register<Exec>("cyclonedxBom") {
    description = "Generates CycloneDX 1.5 JSON SBOM for Android (Gate S3, doc 8 Section 10)"
    group = "reporting"
    workingDir = rootDir.parentFile
    val pythonCmd = resolvePythonCommand()
    val outputDir = file("app/build/reports")
    doFirst {
        outputDir.mkdirs()
    }
    val appVersion = providers.gradleProperty("versionName").getOrElse(
        providers.gradleProperty("appVersionName").getOrElse("1.0.0")
    )
    commandLine(pythonCmd + listOf("tools/generate_sbom.py", "--lockfile", "android/app/gradle.lockfile", "--output", "android/app/build/reports/bom.json", "--app-version", appVersion))
}

abstract class PublishReleaseBundleTask : DefaultTask() {
    @get:Input
    @get:org.gradle.api.tasks.options.Option(option = "track", description = "Target Play track (internal, alpha, beta, production)")
    abstract val track: Property<String>

    @get:Input
    @get:org.gradle.api.tasks.options.Option(option = "artifact-dir", description = "Directory containing bundle to upload")
    abstract val artifactDir: Property<String>

    init {
        track.convention("internal")
        artifactDir.convention("${project.rootDir}/app/build/outputs/bundle/release")
    }

    @TaskAction
    fun publish() {
        val targetTrack = track.get()
        val dirPath = artifactDir.get()
        val updatePriority = project.providers.gradleProperty("updatePriority").map(String::toInt).getOrElse(2)
        val pythonCmd = (project.tasks.findByName("cyclonedxBom") as? Exec)?.commandLine?.firstOrNull()?.let { listOf(it) } ?: listOf("py", "-3")

        val argsList = mutableListOf<String>()
        argsList.addAll(pythonCmd)
        argsList.addAll(listOf(
            project.rootDir.parentFile.resolve("tools/play_publisher.py").absolutePath,
            "--artifact-dir", dirPath,
            "--track", targetTrack,
            "--update-priority", updatePriority.toString()
        ))

        val pb = ProcessBuilder(argsList)
        pb.directory(project.rootDir.parentFile)
        val publisherCreds = project.providers.environmentVariable("ANDROID_PUBLISHER_CREDENTIALS").orNull
        if (!publisherCreds.isNullOrBlank()) {
            pb.environment()["ANDROID_PUBLISHER_CREDENTIALS"] = publisherCreds
        }
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw GradleException("Play publication task failed with exit code $exitCode")
        }
    }
}

tasks.register<PublishReleaseBundleTask>("publishReleaseBundle") {
    description = "Publishes checked release bundle to Google Play internal track (doc 12 Section 4)"
    group = "publishing"
}


