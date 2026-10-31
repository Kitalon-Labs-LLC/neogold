plugins {
    id("neogold.android.library")
}

android {
    namespace = "com.kitalonlabs.neogold.engine.vision"
}

dependencies {
    implementation(project(":engine:colorscience"))
    implementation(libs.opencv)

    // On-device tests of the OpenCV detector: its native library cannot load in JVM unit tests (ADR-018).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
