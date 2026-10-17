plugins {
    id("neogold.android.library")
    id("neogold.android.compose")
}

android {
    namespace = "com.kitalonlabs.neogold.feature.research"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
