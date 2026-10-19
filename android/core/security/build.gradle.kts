plugins {
    id("neogold.android.library")
}

android {
    namespace = "com.kitalonlabs.neogold.core.security"
}

dependencies {
    implementation(libs.tink)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
