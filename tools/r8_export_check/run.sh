#!/usr/bin/env bash
# BUG-2026-083 item 32, the part that needs no phone: runs the app's export crypto after R8 has shrunk and renamed it, with the
# app's own rules (android/app/proguard-rules.pro) and Tink's consumer rule, then opens the result with the unshrunk decrypt
# tool. A device run of a minified release APK is still the last check (doc 9, TC-EXP-01); this one proves R8 does not strip
# or rename what Tink reads by reflection on the export path.
#
# Needs: `cd android && ./gradlew :core:security:compileDebugKotlin :neogold-decrypt:classes` first, the Android SDK's
# cmdline-tools r8.jar, and a JDK. Uses synthetic keys only.
#   usage: tools/r8_export_check/run.sh [work-dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="${1:-$(mktemp -d)}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
r8="$sdk/cmdline-tools/latest/lib/r8.jar"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
tink="$(find "$cache/com.google.crypto.tink/tink-android" -name 'tink-android-*.jar' ! -name '*sources*' | sort | tail -1)"
kstd="$(find "$cache/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2*.jar' ! -name '*sources*' | sort | tail -1)"
android_jar="$(ls "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)"
app_classes="$root/android/core/security/build/tmp/kotlin-classes/debug"
decrypt_classes="$root/tools/neogold-decrypt/build/classes/kotlin/main"
for f in "$r8" "$android_jar" "$tink" "$kstd" "$app_classes" "$decrypt_classes"; do [ -e "$f" ] || { echo "missing: $f" >&2; exit 2; }; done
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"

rm -rf "$work"; mkdir -p "$work/harness" "$work/verify" "$work/program/com/kitalonlabs/neogold/core/security" "$work/out"
javac -cp "$app_classes:$tink:$kstd" -d "$work/harness" "$here/ExportHarness.java"
javac -cp "$decrypt_classes:$tink:$kstd" -d "$work/verify" "$here/ExportVerify.java"
# only the export package goes in: the rest of :core:security needs Android classes
cp -r "$app_classes/com/kitalonlabs/neogold/core/security/export" "$work/program/com/kitalonlabs/neogold/core/security/"

# Tink's own consumer rule (the app's R8 applies it from the jar) and the harness entry point
unzip -p "$tink" 'META-INF/proguard/*.pro' > "$work/tink-consumer.pro"
cat > "$work/entry.pro" <<'PRO'
-keep class ExportHarness { public static void main(java.lang.String[]); }
-dontwarn kotlin.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.kitalonlabs.neogold.core.security.export.**
PRO

# R8 takes jars, not directories
jar cf "$work/harness.jar" -C "$work/harness" .
jar cf "$work/program.jar" -C "$work/program" .

java -cp "$r8" com.android.tools.r8.R8 --release --classfile --output "$work/out/shrunk.jar" --lib "$jdk" --lib "$android_jar" \
  --pg-conf "$root/android/app/proguard-rules.pro" --pg-conf "$work/tink-consumer.pro" --pg-conf "$work/entry.pro" \
  --pg-map-output "$work/out/mapping.txt" "$work/harness.jar" "$work/program.jar" "$tink" "$kstd"

verify_cp="$work/verify:$decrypt_classes:$tink:$kstd"
read -r priv pub < <(java -cp "$verify_cp" ExportVerify keygen)
java -cp "$work/out/shrunk.jar" ExportHarness "$pub" "$work/out/export"
java -cp "$verify_cp" ExportVerify "$priv" "$work/out/export"
echo "shrunk jar: $(du -h "$work/out/shrunk.jar" | cut -f1) (tink-android alone: $(du -h "$tink" | cut -f1)); work dir: $work"
