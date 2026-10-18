plugins {
    id("neogold.android.library")
}

android {
    namespace = "com.kitalonlabs.neogold.engine.vision"

    val ndkPath = project.findProperty("android.ndkPath") as? String ?: System.getenv("ANDROID_NDK_HOME")
    if (ndkPath != null && file(ndkPath).exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
}

dependencies {
    implementation(project(":engine:colorscience"))
}
