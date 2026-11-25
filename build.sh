#!/bin/bash
# Pil Analizi'ni derler. Gradle/Android Studio gerektirmez.
#
# Yerelde (Ubuntu/Mint): sudo apt install openjdk-17-jdk-headless aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
# GitHub Actions'ta: runner'daki $ANDROID_HOME kullanılır.
#
# Ortam değişkenleri (isteğe bağlı):
#   VERSION_CODE, VERSION_NAME   sürüm (varsayılan 1 / 1.0)
#   KEYSTORE, KEYSTORE_PASS      imza anahtarı (varsayılan ./imza.p12)
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
KEYSTORE="${KEYSTORE:-imza.p12}"
KEYSTORE_PASS="${KEYSTORE_PASS:-}"

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
  # Kararlı platformu seç (önizleme/DEV sürümlerini atla)
  PLAT=""
  for d in $(ls -d "$ANDROID_HOME"/platforms/android-[0-9]* | sort -V -r); do
    grep -q "AndroidVersion.CodeName" "$d/source.properties" 2>/dev/null || { PLAT="$d"; break; }
  done
  ANDROID_JAR="$PLAT/android.jar"
  AAPT="$BT/aapt"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
  DEX() { "$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output build $(find build/classes -name '*.class'); }
else
  ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
  AAPT=aapt; ZIPALIGN=zipalign; APKSIGNER=apksigner
  DEX() { /usr/lib/android-sdk/build-tools/debian/dx --dex --min-sdk-version=26 --output=build/classes.dex build/classes; }
fi
echo "android.jar: $ANDROID_JAR"

rm -rf build && mkdir -p build/classes build/gen
"$AAPT" package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -J build/gen -F build/unsigned.apk \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java')
DEX
(cd build && "$AAPT" add unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "İmza anahtarı bulunamadı: $KEYSTORE" >&2; exit 1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-type PKCS12 --ks-pass "pass:$KEYSTORE_PASS" --out PilAnalizi.apk build/aligned.apk
"$APKSIGNER" verify PilAnalizi.apk
echo "OK -> $(pwd)/PilAnalizi.apk  (v$VERSION_NAME / $VERSION_CODE)"
