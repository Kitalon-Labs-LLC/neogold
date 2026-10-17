plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.4.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
}

gradlePlugin {
    plugins {
        register("neogoldJvmLibrary") {
            id = "neogold.jvm.library"
            implementationClass = "com.kitalonlabs.neogold.buildlogic.NeoGoldJvmLibraryPlugin"
        }
        register("neogoldAndroidLibrary") {
            id = "neogold.android.library"
            implementationClass = "com.kitalonlabs.neogold.buildlogic.NeoGoldAndroidLibraryPlugin"
        }
        register("neogoldAndroidCompose") {
            id = "neogold.android.compose"
            implementationClass = "com.kitalonlabs.neogold.buildlogic.NeoGoldAndroidComposePlugin"
        }
        register("neogoldAndroidApplication") {
            id = "neogold.android.application"
            implementationClass = "com.kitalonlabs.neogold.buildlogic.NeoGoldAndroidApplicationPlugin"
        }
        register("neogoldDetekt") {
            id = "neogold.detekt"
            implementationClass = "com.kitalonlabs.neogold.buildlogic.NeoGoldDetektPlugin"
        }
    }
}
