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

# Release by default. A debug build is marked android:debuggable and is signed
# with the generic "CN=Android Debug" identity, and Google Play Protect blocks
# installs on that basis, so the debug build is only useful for debugging.
BUILD_TYPE=release
case "${1:-}" in
    --debug) BUILD_TYPE=debug ;;
    --release|"") ;;
    *) echo "usage: ${0##*/} [--release|--debug]" >&2; exit 1 ;;
esac

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$PROJECT_ROOT/app"
OUT="$PROJECT_ROOT/app/build/offline/$BUILD_TYPE"
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
echo "==> ${PACKAGE} ${VERSION_NAME} (${VERSION_CODE}) ${BUILD_TYPE}, minSdk ${MIN_SDK}, targetSdk ${TARGET_SDK}"

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
    $([[ "$BUILD_TYPE" == debug ]] && echo --debug-mode) \
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
    "--$BUILD_TYPE" \
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
if [[ "$BUILD_TYPE" == debug ]]; then
    KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
    STORE_PASS=android
    KEY_ALIAS=androiddebugkey
    if [[ ! -f "$KEYSTORE" ]]; then
        echo "==> creating debug keystore at $KEYSTORE"
        mkdir -p "$(dirname "$KEYSTORE")"
        "$KEYTOOL" -genkeypair -v \
            -keystore "$KEYSTORE" \
            -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10950 \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null
    fi
else
    # The release key is this app's identity. Android only accepts an upgrade
    # signed with the same key, so losing it means every future version has to
    # be installed fresh. It is deliberately kept out of the repository, which
    # is public: anyone holding it could sign an "upgrade" the phone would
    # install over this app without a murmur.
    KEYSTORE="${RELEASE_KEYSTORE:-$PROJECT_ROOT/keystore/release.keystore}"
    PASS_FILE="${RELEASE_KEYSTORE_PASSFILE:-${KEYSTORE%.keystore}.password}"
    KEY_ALIAS="${RELEASE_KEY_ALIAS:-instawidget}"

    if [[ ! -f "$KEYSTORE" ]]; then
        echo "==> creating release keystore at $KEYSTORE"
        mkdir -p "$(dirname "$KEYSTORE")"
        # A generated password, written beside the keystore. Both are
        # gitignored; back them up together.
        head -c 24 /dev/urandom | base64 | tr -d '\n/+=' > "$PASS_FILE"
        chmod 600 "$PASS_FILE"
        "$KEYTOOL" -genkeypair -v \
            -keystore "$KEYSTORE" \
            -storepass "$(cat "$PASS_FILE")" -keypass "$(cat "$PASS_FILE")" \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 4096 -validity 10950 \
            -dname "CN=IG DM Widget,OU=Personal,O=IG DM Widget,C=US" >/dev/null
        chmod 600 "$KEYSTORE"
        echo "    Back up $KEYSTORE and $PASS_FILE. Without them, future"
        echo "    versions cannot upgrade this install."
    fi
    [[ -f "$PASS_FILE" ]] || { echo "Keystore password file missing: $PASS_FILE" >&2; exit 1; }
    STORE_PASS="$(cat "$PASS_FILE")"
fi

echo "==> apksigner"
FINAL="$PROJECT_ROOT/dist/app-$BUILD_TYPE.apk"
mkdir -p "$(dirname "$FINAL")"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$STORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$FINAL" \
    "$ALIGNED"

apksigner verify --min-sdk-version "$MIN_SDK" --verbose "$FINAL"

echo
echo "APK: $FINAL"
ls -l "$FINAL"
