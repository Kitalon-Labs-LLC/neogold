plugins {
    id("neogold.jvm.library")
}

dependencies {
    implementation(project(":engine:colorscience"))
    testImplementation(project(":engine:triage"))
}
