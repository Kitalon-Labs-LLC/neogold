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

# The same kind of annotation, referenced by Tink's secret-key access classes. They became reachable when the research
# session was wired into the app (C2), which is when R8 first reported them. Exactly the two classes it lists in
# missing_rules.txt; still no wildcard.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue

# One more of the same kind, referenced by Tink's MAC key classes. They became reachable when the research export was wired
# into the app (C3, R07), which is when R8 first reported it. Exactly the class it lists in missing_rules.txt; no wildcard.
-dontwarn com.google.errorprone.annotations.RestrictedApi
