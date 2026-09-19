#!/usr/bin/env bash
#
# Builds the debug APK without the Android Gradle Plugin.
#
# Why this exists: AGP and the Android SDK are only distributed from
# dl.google.com, which some networks (including the sandbox this project was
# first built in) block. The normal build is `./gradlew assembleDebug`; use
# this script only when that host is unreachable.
#
# It drives the same underlying tools AGP itself drives:
#
#   kotlinc  -> .class files
#   aapt2    -> compiled resources + resources.apk + R.java
#   javac    -> R.class
#   d8 (R8)  -> classes.dex
#   zipalign -> aligned APK
#   apksigner-> signed with a debug keystore
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$PROJECT_ROOT/app"
OUT="$PROJECT_ROOT/app/build/offline"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
RES_COMPILED="$OUT/res-compiled"
DEX="$OUT/dex"

# --- toolchain ---------------------------------------------------------------
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-amd64}"
: "${ANDROID_HOME:=/usr/lib/android-sdk}"
: "${TOOLS_DIR:=/opt/android-tools}"

BUILD_TOOLS="$ANDROID_HOME/build-tools/29.0.3"
AAPT2="$BUILD_TOOLS/aapt2"

# Two different framework jars, for two different jobs:
#
#  * RES_JAR is what aapt2 links resources against. Ubuntu only packages the
#    API 23 platform, which is fine -- every attribute this app's XML uses
#    predates API 23.
#  * COMPILE_JAR is the Kotlin/javac classpath and has to match compileSdk,
#    because the code calls API 26 methods such as requestPinAppWidget.
#    Google only distributes android.jar from dl.google.com, so this uses
#    Robolectric's android-all build of the same AOSP framework, which is on
#    Maven Central:
#      https://repo1.maven.org/maven2/org/robolectric/android-all/
RES_JAR="${RES_JAR:-$ANDROID_HOME/platforms/android-23/android.jar}"
COMPILE_JAR="${COMPILE_JAR:-$TOOLS_DIR/android-all-34.jar}"
KOTLINC="$TOOLS_DIR/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$TOOLS_DIR/kotlin-stdlib-1.9.24.jar"
KOTLIN_ANNOTATIONS="$TOOLS_DIR/annotations-13.0.jar"
R8_JAR="$TOOLS_DIR/r8-8.5.35.jar"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

# Read the build config from one place rather than keeping a second copy in
# sync by hand; they drifted apart more than once.
gradle_value() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[[:space:]]*$/\1/p" \
        "$APP/build.gradle.kts" | head -1
}

MIN_SDK="$(gradle_value minSdk)"
TARGET_SDK="$(gradle_value targetSdk)"
VERSION_CODE="$(gradle_value versionCode)"
VERSION_NAME="$(gradle_value versionName)"
PACKAGE="$(gradle_value namespace)"

for pair in "MIN_SDK:$MIN_SDK" "TARGET_SDK:$TARGET_SDK" \
            "VERSION_CODE:$VERSION_CODE" "VERSION_NAME:$VERSION_NAME" "PACKAGE:$PACKAGE"; do
    [[ -n "${pair#*:}" ]] || { echo "Could not read ${pair%%:*} from build.gradle.kts" >&2; exit 1; }
done
echo "==> ${PACKAGE} ${VERSION_NAME} (${VERSION_CODE}), minSdk ${MIN_SDK}, targetSdk ${TARGET_SDK}"

for tool in "$AAPT2" "$KOTLINC" "$R8_JAR" "$RES_JAR" "$COMPILE_JAR" "$JAVA" "$JAVAC"; do
    [[ -e "$tool" ]] || { echo "Missing required tool: $tool" >&2; exit 1; }
done

echo "==> Cleaning $OUT"
rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES" "$RES_COMPILED" "$DEX"

# --- 1. compile resources ----------------------------------------------------
echo "==> aapt2 compile"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$RES_COMPILED/res.zip"

# AGP 8 injects the package name from the `namespace` in build.gradle.kts and
# rejects a `package` attribute in the source manifest, but standalone aapt2
# still requires one -- so inject it into a throwaway copy.
MANIFEST="$OUT/AndroidManifest.xml"
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" \
    "$APP/src/main/AndroidManifest.xml" > "$MANIFEST"

echo "==> aapt2 link"
"$AAPT2" link \
    -o "$OUT/resources.apk" \
    -I "$RES_JAR" \
    --manifest "$MANIFEST" \
    --java "$GEN" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --debug-mode \
    --auto-add-overlay \
    "$RES_COMPILED/res.zip"

# --- 2. compile Kotlin -------------------------------------------------------
echo "==> kotlinc"
# -Xlambdas/-Xsam-conversions=class keep invokedynamic out of the bytecode,
# which keeps the dex step simple and the output identical across API levels.
JAVA_HOME="$JAVA_HOME" "$KOTLINC" \
    -classpath "$COMPILE_JAR:$GEN" \
    -jvm-target 17 \
    -Xlambdas=class \
    -Xsam-conversions=class \
    -nowarn \
    -d "$CLASSES" \
    "$APP/src/main/java" "$GEN"

# --- 3. compile the generated R.java ----------------------------------------
echo "==> javac (generated R.java)"
mapfile -t JAVA_SOURCES < <(find "$GEN" -name '*.java')
if (( ${#JAVA_SOURCES[@]} > 0 )); then
    "$JAVAC" -source 17 -target 17 -nowarn \
        -classpath "$COMPILE_JAR:$CLASSES" \
        -d "$CLASSES" "${JAVA_SOURCES[@]}" 2>&1 | grep -v "bootstrap class path" || true
fi

# --- 4. dex ------------------------------------------------------------------
echo "==> d8"
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --debug \
    --min-api "$MIN_SDK" \
    --lib "$COMPILE_JAR" \
    --output "$DEX" \
    --classpath "$CLASSES" \
    "$KOTLIN_STDLIB" \
    "$KOTLIN_ANNOTATIONS" \
    $(find "$CLASSES" -name '*.class')

# --- 5. assemble the APK -----------------------------------------------------
echo "==> assembling APK"
UNALIGNED="$OUT/app-debug-unaligned.apk"
cp "$OUT/resources.apk" "$UNALIGNED"
( cd "$DEX" && zip -q -u "$UNALIGNED" ./*.dex )

# --- 6. align ----------------------------------------------------------------
echo "==> zipalign"
ALIGNED="$OUT/app-debug-unsigned.apk"
zipalign -p -f 4 "$UNALIGNED" "$ALIGNED"

# --- 7. sign with a debug key ------------------------------------------------
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
if [[ ! -f "$KEYSTORE" ]]; then
    echo "==> creating debug keystore at $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo "==> apksigner"
FINAL="$PROJECT_ROOT/dist/app-debug.apk"
mkdir -p "$(dirname "$FINAL")"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --out "$FINAL" \
    "$ALIGNED"

apksigner verify --min-sdk-version "$MIN_SDK" --verbose "$FINAL"

echo
echo "APK: $FINAL"
ls -l "$FINAL"
