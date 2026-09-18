#!/usr/bin/env bash
# Builds AnyDoor.apk with the raw SDK tools (no Gradle). Run from the project root in Git Bash.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-D:/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-C:/Users/zyx/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot}"
OUT=build
SRC=app/src/main
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

echo "[1/5] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$OUT/res.zip"
echo "[2/5] aapt2 link"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$SRC/AndroidManifest.xml" \
  -A "$SRC/assets" --java "$OUT/gen" --auto-add-overlay "$OUT/res.zip"
echo "[3/5] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -nowarn -Xlint:-options \
  -cp "$PLATFORM;libs/api-82.jar" -d "$OUT/classes" @"$OUT/sources.txt"
echo "[4/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8.bat" --release --min-api 27 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"
echo "[5/5] package + sign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -v -keystore keystore.jks -alias anydoor -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass anydoor123 -keypass anydoor123 -dname "CN=AnyDoor, OU=Dev, O=AnyDoor, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks keystore.jks --ks-pass pass:anydoor123 --key-pass pass:anydoor123 \
  --ks-key-alias anydoor --out AnyDoor.apk "$OUT/aligned.apk"
ls -la AnyDoor.apk
