#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 ADB_SERIAL MIE2_DEBUG_APK [floating|modes|clipboard|bds|optimization|all]" >&2
    exit 2
fi
case "${3:-floating}" in
    floating) runners=(Runner) ;;
    modes) runners=(ModeRunner) ;;
    clipboard) runners=(ClipboardRunner) ;;
    bds) runners=(BdsRunner) ;;
    optimization) runners=(OptimizationRunner) ;;
    all) runners=(Runner ModeRunner ClipboardRunner) ;;
    *) echo "Expected floating, modes, clipboard, bds, optimization or all" >&2; exit 2 ;;
esac
serial="$1"
apk="$2"
source_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
tools_dir="$sdk_dir/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
android_jar="$sdk_dir/platforms/${ANDROID_PLATFORM:-android-37.0}/android.jar"
key_file="${TRIME_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/trime-floating-smoke.XXXXXX")"
cleanup() {
    if [ "${TRIME_KEEP_SMOKE:-0}" != 1 ]; then adb -s "$serial" uninstall dev.codex.trime.smoke >/dev/null 2>&1 || true; fi
    rm -rf -- "$test_dir"
}
trap cleanup EXIT
mkdir -p "$test_dir/classes" "$test_dir/dex"
javac -source 11 -target 11 -classpath "$android_jar" -d "$test_dir/classes" "$source_dir"/*.java
"$tools_dir/d8" --lib "$android_jar" --output "$test_dir/dex" "$test_dir"/classes/dev/codex/trime/smoke/*.class
"$tools_dir/aapt" package -f -M "$source_dir/AndroidManifest.xml" -I "$android_jar" -F "$test_dir/unsigned.apk"
(cd "$test_dir/dex" && zip -q "$test_dir/unsigned.apk" classes.dex)
"$tools_dir/zipalign" -f 4 "$test_dir/unsigned.apk" "$test_dir/aligned.apk"
"$tools_dir/apksigner" sign --ks "$key_file" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$test_dir/smoke.apk" "$test_dir/aligned.apk"
if [ "${TRIME_SKIP_TARGET_INSTALL:-0}" != 1 ]; then adb -s "$serial" install --no-incremental -r "$apk"; fi
adb -s "$serial" install --no-incremental -r "$test_dir/smoke.apk"
for runner in "${runners[@]}"; do
    adb -s "$serial" shell am instrument -w "dev.codex.trime.smoke/dev.codex.trime.smoke.$runner" | tee "$test_dir/$runner.txt"
    rg -q 'INSTRUMENTATION_RESULT: result=PASS' "$test_dir/$runner.txt"
done
