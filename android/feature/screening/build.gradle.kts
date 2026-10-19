plugins {
    id("neogold.android.library")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold.feature.screening"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Engine modules for v0.1 pipeline integration (P10)
    implementation(project(":engine:camera"))
    implementation(project(":engine:vision"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:estimation"))
    implementation(project(":engine:triage"))
    implementation(project(":engine:cardtrust"))
}
