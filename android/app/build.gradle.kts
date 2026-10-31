import com.android.build.api.variant.BuildConfigField
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

plugins {
    id("neogold.android.application")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold"

    buildFeatures {
        buildConfig = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Signs the model, the triage config and the trust list with K3 and writes them, the manifest and `signing-info.json`
 * into [outputDir], which every variant packs as assets. The source tree never holds a signed copy: a signed copy
 * would need a key in the repository (BUG-2026-008) and would go stale the moment a config changed.
 *
 * `-PmanifestKey` is the release key (a path, or the key itself); without it a development key is made under build/.
 * `-PrequireReleaseKey=true` refuses to fall back to that, and the release workflow sets it.
 */
abstract class SignBundleManifestTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signerScript: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinnedPublicKey: RegularFileProperty

    @get:Input
    abstract val pythonCommand: ListProperty<String>

    @get:Input
    abstract val requireReleaseKey: Property<Boolean>

    /** "development" or "release". The key itself is never an input; a release key makes the task always run. */
    @get:Input
    abstract val signerKind: Property<String>

    /** The release key as given on the command line. Never an input, never printed. */
    @get:Internal
    abstract val manifestKey: Property<String>

    @get:Internal
    abstract val devKeyFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sign() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val arguments = mutableListOf<String>()
        arguments += pythonCommand.get()
        arguments += listOf(
            signerScript.get().asFile.path,
            "--config-dir", configDir.get().asFile.path,
            "--out-dir", out.path,
            "--dev-key-file", devKeyFile.get().asFile.path
        )
        if (pinnedPublicKey.isPresent) {
            arguments += listOf("--pinned-public-key", pinnedPublicKey.get().asFile.path)
        }
        val key = manifestKey.orNull
        // A key that is passed in is a release key: it must match the pinned K3, so a wrong secret or a missing pin
        // fails the build instead of producing a bundle the app trusts only because nothing was pinned.
        if (requireReleaseKey.get() || key != null) {
            arguments += "--require-release-key"
        }
        val keyEnvironment = "NEOGOLD_MANIFEST_KEY"
        if (key != null) {
            // A path is safe on the command line; key material goes through the environment.
            if (File(key).isFile) arguments += listOf("--manifest-key", key) else arguments += listOf("--manifest-key-env", keyEnvironment)
        }

        execOperations.exec {
            commandLine(arguments)
            if (key != null && !File(key).isFile) {
                environment(keyEnvironment, key)
            }
        }
    }
}

val signBundleManifest = tasks.register<SignBundleManifestTask>("signBundleManifest") {
    description = "Signs the bundle assets with K3 into the app's generated assets (doc 4 Section 12, doc 8 Section 6.5)"
    group = "build"
    val repositoryRoot = rootProject.layout.projectDirectory.dir("..")
    configDir.set(repositoryRoot.dir("config"))
    signerScript.set(repositoryRoot.file("tools/sign_bundle_manifest.py"))
    repositoryRoot.file("config/k3-public-key.hex").asFile.takeIf { it.isFile }?.let { pinnedPublicKey.set(it) }

    @Suppress("UNCHECKED_CAST")
    pythonCommand.set(rootProject.extra["pythonCommand"] as List<String>)

    val keyInput = providers.gradleProperty("manifestKey").orNull
    manifestKey.set(providers.provider { keyInput })
    signerKind.set(if (keyInput == null) "development" else "release")
    // Nothing of a release key is kept to compare against, so a build that has one signs again every time.
    if (keyInput != null) outputs.upToDateWhen { false }
    requireReleaseKey.set(providers.gradleProperty("requireReleaseKey").map(String::toBoolean).orElse(false))
    devKeyFile.set(layout.buildDirectory.file("dev-k3/k3-dev.key"))
    outputDir.set(layout.buildDirectory.dir("generated/neogold-bundle"))
}

fun signingInfoField(name: String): Provider<String> = signBundleManifest
    .flatMap { it.outputDir.file("signing-info.json") }
    .map { file ->
        Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(file.asFile.readText())?.groupValues?.get(1)
            ?: error("signing-info.json has no $name")
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(signBundleManifest, SignBundleManifestTask::outputDir)

        // The app trusts the public half of the key its own bundle was signed with, and knows if that key is a
        // development key so a build that is not debuggable can refuse to run on it (BUG-2026-008).
        variant.buildConfigFields?.put(
            "K3_PUBLIC_KEY_HEX",
            signingInfoField("publicKeyHex").map { BuildConfigField("String", "\"$it\"", "K3 public key of the bundle") }
        )
        variant.buildConfigFields?.put(
            "K3_IS_DEV_KEY",
            signingInfoField("keyKind").map { BuildConfigField("boolean", (it != "release").toString(), "development key") }
        )
    }
}

// The unit tests check the bundle the build really produced, so they find it by this property.
tasks.withType<Test>().configureEach {
    dependsOn(signBundleManifest)
    systemProperty("neogold.bundle.dir", signBundleManifest.flatMap { it.outputDir }.get().asFile.absolutePath)
}

dependencies {
    implementation(libs.findLibrary("androidx.activity.compose").get())
    implementation(libs.findLibrary("androidx.navigation.compose").get())
    implementation(libs.findLibrary("androidx.biometric").get())
    implementation(libs.findLibrary("androidx-room-runtime").get())
    implementation(libs.findLibrary("androidx-room-ktx").get())
    implementation(libs.findLibrary("timber").get())

    // Core
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:security"))

    // Engines
    implementation(project(":engine:camera"))
    implementation(project(":engine:vision"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:estimation"))
    implementation(project(":engine:triage"))
    implementation(project(":engine:cardtrust"))

    // Features
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:calibration"))
    implementation(project(":feature:screening"))
    implementation(project(":feature:result"))
    implementation(project(":feature:log"))
    implementation(project(":feature:research"))
    implementation(project(":feature:settings"))

    // Sync
    implementation(project(":sync:research"))

    // Instrumented tests (SecurityInstrumentedTest): the real Keystore, SQLCipher and container on a device
    androidTestImplementation(libs.findLibrary("androidx.junit").get())
    androidTestImplementation(libs.findLibrary("androidx.test.runner").get())
    androidTestImplementation(libs.findLibrary("truth").get())
}
