#!/usr/bin/env bash
# Compile Freebox Remote (application Android native) et produit FreeboxRemote.apk.
# Prérequis : JDK 17+, curl, unzip, zip. Le SDK Android est téléchargé au besoin.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$PWD/.android-sdk}}"
BUILD_TOOLS_VERSION="35.0.0"
PLATFORM="android-35"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ ! -x "$SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2" ] || [ ! -f "$SDK/platforms/$PLATFORM/android.jar" ]; then
  echo "→ Installation du SDK Android dans $SDK…"
  mkdir -p "$SDK/cmdline-tools"
  TMP="$(mktemp -d)"
  curl -sSL -o "$TMP/tools.zip" "$CMDLINE_URL"
  unzip -q "$TMP/tools.zip" -d "$SDK/cmdline-tools"
  rm -rf "$TMP"
  if [ -d "$SDK/cmdline-tools/cmdline-tools" ]; then
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --install "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS_VERSION" "platform-tools" >/dev/null
fi

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

if [ ! -x "$BT/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "SDK Android incomplet : $SDK" >&2
  exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
  echo "javac introuvable : installez un JDK 17 ou plus récent." >&2
  exit 1
fi

echo "→ Nettoyage…"
rm -rf build
mkdir -p build/classes build/gen build/apk build/dex

echo "→ Ressources…"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 \
  --target-sdk-version 35 \
  --version-code 5 \
  --version-name 1.4 \
  --auto-add-overlay \
  --java build/gen \
  build/res.zip

echo "→ Compilation Java…"
SOURCES="$(find src build/gen -name '*.java')"
javac --release 8 -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d build/classes \
  $SOURCES

echo "→ Dex…"
CLASSES="$(find build/classes -name '*.class')"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 21 --output build/dex $CLASSES

echo "→ Assemblage de l'APK…"
cp build/base.apk build/app.apk
( cd build && zip -q -j app.apk dex/classes.dex )
"$BT/zipalign" -f 4 build/app.apk build/aligned.apk

KEYSTORE="$PWD/.debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "→ Création de la clé de signature de développement…"
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android -keypass android \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Freebox Remote Debug, O=Freebox Remote, C=FR" >/dev/null
fi

echo "→ Signature…"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out FreeboxRemote.apk \
  build/aligned.apk

"$BT/apksigner" verify --print-certs FreeboxRemote.apk | head -4
echo
echo "APK prêt : $PWD/FreeboxRemote.apk"
echo "Installez-le avec : adb install -r FreeboxRemote.apk"
