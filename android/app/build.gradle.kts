plugins {
    id("neogold.android.application")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

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
}

tasks.named("preBuild") {
    dependsOn(":signAssetManifest")
}
