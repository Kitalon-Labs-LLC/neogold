plugins {
    id("neogold.android.library")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold.feature.research"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))

    implementation(project(":engine:cardtrust"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:estimation"))
    implementation(project(":engine:camera"))
    implementation(project(":engine:vision"))

    implementation(libs.findLibrary("androidx-room-runtime").get())
    implementation(libs.findLibrary("androidx-room-ktx").get())
    implementation(libs.findLibrary("timber").get())
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())

    testImplementation(project(":core:testing"))
    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("truth").get())
    testImplementation(libs.findLibrary("robolectric").get())
    testImplementation(libs.findLibrary("kotlinx.coroutines.test").get())
}
