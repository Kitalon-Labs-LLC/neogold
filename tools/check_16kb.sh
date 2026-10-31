#!/usr/bin/env bash
# Release gate S4 (doc 8 Section 6.1): every native library is 16 KB aligned.
# Usage: check_16kb.sh app.apk   (needs zipalign and llvm-objdump from the Android SDK and NDK)
set -euo pipefail
apk="${1:?usage: check_16kb.sh app.apk}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
zipalign -c -P 16 -v 4 "$apk" > "$work/zipalign.txt" || { echo "S4 FAIL: zip alignment"; tail -5 "$work/zipalign.txt"; exit 1; }
unzip -q "$apk" 'lib/*' -d "$work" 2>/dev/null || true
fail=0
while IFS= read -r -d '' so; do
  case "$so" in */arm64-v8a/*|*/x86_64/*) ;; *) continue ;; esac   # 16 KB pages apply to 64-bit ABIs
  while read -r align; do
    exp="${align#"2**"}"
    if [ "$exp" -lt 14 ]; then echo "S4 FAIL: ${so#"$work"/} LOAD segment aligned 2**$exp"; fail=1; fi
  done < <(llvm-objdump -p "$so" | awk '/LOAD/ {for (i=1;i<=NF;i++) if ($i=="align") print $(i+1)}')
done < <(find "$work/lib" -name '*.so' -print0 2>/dev/null)
[ "$fail" -eq 0 ] && echo "S4 PASS: zip and ELF alignment satisfied"
exit "$fail"
