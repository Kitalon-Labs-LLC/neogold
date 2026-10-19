plugins {
    id("neogold.android.library")
}

android {
    namespace = "com.kitalonlabs.neogold.benchmark"
}

dependencies {
    implementation(project(":feature:screening"))
    implementation(project(":engine:camera"))
    implementation(project(":engine:vision"))
    implementation(project(":engine:colorscience"))
    implementation(project(":engine:estimation"))
    implementation(project(":engine:triage"))
    implementation(project(":engine:cardtrust"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
