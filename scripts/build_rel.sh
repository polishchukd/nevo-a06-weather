#!/bin/bash
# Релизная сборка для ГУ: ru-база v6 -> патчи smali/strings -> classes4.dex -> подпись platform key
# Требования: apktool.jar в tools/, JDK, Android build-tools 34
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
APKTOOL=${APKTOOL:-tools/apktool.jar}
OUT=${OUT:-apks/Weather-ru-v8.apk}

bash scripts/build_dex.sh
cp build/dex/classes.dex apks/.classes4.dex.tmp

rm -rf build/wxrel
java -jar "$APKTOOL" d -f -o build/wxrel apks/Weather-ru-v6.apk >/dev/null 2>&1
cp apks/.classes4.dex.tmp build/wxrel/classes4.dex && rm -f apks/.classes4.dex.tmp

# 1. HTTPDNS -> null (okhttp падает на системный DNS; 119.29.29.29 из ГУ недоступен)
python3 - <<'EOF'
import re
p='build/wxrel/smali_classes2/com/incall/apps/component/okhttp/manager/dns/HttpDNS.smali'
s=open(p).read()
m=re.search(r'\.method public getIPByHost\(Ljava/lang/String;\)\[Ljava/lang/String;\n(.*?)\n\.end method\n', s, re.S)
assert m, "getIPByHost not found"
body="    .registers 2\n\n    const/4 v0, 0x0\n\n    return-object v0"
s=s[:m.start(1)]+body+s[m.end(1):]
open(p,'w').write(s)
EOF

# 2. wind_level -> "<скорость> м/с" вместо "N级"
python3 - <<'EOF'
import re
for p in ['build/wxrel/res/values-en/strings.xml','build/wxrel/res/values/strings.xml']:
    s=open(p,encoding='utf-8').read()
    s=re.sub(r'<string name="wind_level">.*?</string>','<string name="wind_level">&#160;%1$s \u043c/\u0441</string>',s)
    open(p,'w',encoding='utf-8').write(s)
EOF

# 3. sdkConnected=true в конструкторе BaseViewModel (гейт виджета)
python3 - <<'EOF'
import re
p='build/wxrel/smali/com/ca/hmi/weather/ue/viewmodel/BaseViewModel.smali'
s=open(p).read()
m=re.search(r'\.method public constructor <init>\(.*?\n(.*?)\n\.end method\n', s, re.S)
assert m, "BaseViewModel init not found"
ins="\n    const/4 v0, 0x1\n\n    iput-boolean v0, p0, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;->sdkConnected:Z\n"
s=s[:m.end(1)]+ins+s[m.end(1):]
open(p,'w').write(s)
EOF

java -jar "$APKTOOL" b -f -o build/v.apk build/wxrel >/dev/null 2>&1
"$BT/zipalign" -p -f 4 build/v.apk build/va.apk
"$BT/apksigner" sign --key keys/platform.pk8 --cert keys/platform.x509.pem --out "$OUT" build/va.apk
"$BT/apksigner" verify "$OUT" && ls -la "$OUT"
