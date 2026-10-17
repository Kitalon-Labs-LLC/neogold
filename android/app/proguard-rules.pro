# NeoGold R8 Proguard Rules

# Preserve navigation route classes
-keepclassmembers class * {
    @androidx.navigation.* <methods>;
}
