plugins {
    id("neogold.android.library")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold.engine.camera"
    buildFeatures {
        buildConfig = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("androidx.camera.core").get())
    implementation(libs.findLibrary("androidx.camera.camera2").get())
    implementation(libs.findLibrary("androidx.camera.lifecycle").get())
    implementation(libs.findLibrary("androidx.camera.view").get())
    implementation(libs.findLibrary("kotlinx.coroutines.android").get())
    implementation(libs.findLibrary("zxing.core").get()) // reads the signed binary QR on the card and the study sheet

    implementation(project(":engine:vision"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:triage"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))

    testImplementation(libs.findLibrary("robolectric").get())
    testImplementation(libs.findLibrary("kotlinx.coroutines.test").get())
}
