plugins {
    id("neogold.android.library")
}

android {
    namespace = "com.kitalonlabs.neogold.core.security"
}

dependencies {
    implementation(libs.tink)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.biometric)
    implementation(libs.timber)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
