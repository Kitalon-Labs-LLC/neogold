plugins {
    id("neogold.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kitalonlabs.neogold.sync.research"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":engine:cardtrust"))

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Not used by the module's own code. Robolectric brings a vulnerable Bouncy Castle (1.78.1) to the test compile
    // classpath; this keeps it at the version the rest of the app uses (BUG-2026-036).
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
