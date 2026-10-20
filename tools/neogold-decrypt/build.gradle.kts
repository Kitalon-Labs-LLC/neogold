plugins {
    kotlin("jvm")
    application
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

application {
    mainClass.set("com.kitalonlabs.neogold.decrypt.MainKt")
    applicationName = "neogold-decrypt"
}

dependencies {
    implementation(libs.findLibrary("tink").get())

    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("truth").get())
}
