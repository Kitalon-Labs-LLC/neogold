# NeoGold R8 Proguard Rules

# Preserve navigation route classes
-keepclassmembers class * {
    @androidx.navigation.* <methods>;
}

# OpenCV (ADR-018): its native library finds the Java classes by name through JNI, so R8 must neither rename
# nor remove them. The Java side is about 0.4 MB.
-keep class org.opencv.** { *; }

# Release logging: strip android.util.Log calls in release builds (TC-SEC-03, doc 8 Section 6.1)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Compile-time-only annotation that a dependency references. It has no runtime behaviour, so R8
# can drop the reference. This is exactly the one rule R8 reports as missing for the release build
# (app/build/outputs/mapping/release/missing_rules.txt); no wildcard, nothing else is silenced.
-dontwarn com.google.errorprone.annotations.Immutable
