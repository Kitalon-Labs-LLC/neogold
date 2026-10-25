plugins {
    id("neogold.android.library")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold.feature.screening"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))

    // Engine modules for v0.1 pipeline integration (P10)
    implementation(project(":engine:camera"))
    implementation(project(":engine:vision"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:estimation"))
    implementation(project(":engine:triage"))
    implementation(project(":engine:cardtrust"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("truth").get())
    testImplementation(libs.findLibrary("kotlinx.coroutines.test").get())
    testImplementation(libs.findLibrary("robolectric").get())
}
