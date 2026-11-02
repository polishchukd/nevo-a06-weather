#!/bin/bash
# Эмуляционная сборка: релизный APK -> без sharedUserId, под testkey AOSP
# (эмулятор Google APIs не принимает платформу ГУ; для тестов на Automotive AVD)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
APKTOOL=${APKTOOL:-tools/apktool.jar}
IN=${IN:-apks/Weather-ru-v8.apk}
OUT=${OUT:-apks/Weather-emu13.apk}
KS=${KS:-apks/testkey.p12}   # ключ: keytool -genkey -alias android -keyalg RSA -storepass android -keystore apks/testkey.p12

rm -rf build/wxem
java -jar "$APKTOOL" d -f -o build/wxem "$IN" >/dev/null 2>&1
sed -i 's/ android:sharedUserId="android.uid.system"//' build/wxem/AndroidManifest.xml

# Settings$Global.putString -> nop (нет WRITE_SECURE_SETTINGS вне system-uid)
python3 - <<'EOF'
import re,glob
n=0
for p in glob.glob('build/wxem/smali*/com/nevo/weather/*.smali'):
    s=open(p).read()
    if 'Settings$Global;->putString' in s:
        s=re.sub(r'invoke-static \{[\s\S]{0,400}?Settings\$Global;->putString\(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;\)V',
                 'nop', s)
        open(p,'w').write(s); n+=1
print('putString-stub files:',n)
EOF

# profileinstaller конфликтует с android.car.jar на automotive-имидже
sed -i '/androidx.profileinstaller.ProfileInstallerInitializer/d' build/wxem/AndroidManifest.xml

java -jar "$APKTOOL" b -f -o build/eu.apk build/wxem >/dev/null 2>&1
"$BT/zipalign" -p -f 4 build/eu.apk build/eua.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias android --out "$OUT" build/eua.apk
"$BT/apksigner" verify "$OUT" && ls -la "$OUT"
