#!/bin/bash
# Релизная сборка для ГУ: ru-база v6 -> патчи smali/strings -> classes4.dex -> подпись platform key
# Требования: apktool.jar в tools/, JDK, Android build-tools 34
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
APKTOOL=${APKTOOL:-tools/apktool.jar}
OUT=${OUT:-apks/Weather-ru-v10.apk}

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

# 2b. getWindForceLabel: не брать строку wind_level из ресурсов — системная
# ру-накладка Changan (RRO) переопределяет её на "%1$s балл", и в ГУ ветер
# показывается в баллах. Собираем "N м/с" прямо в smali, минуя ресурсы.
python3 - <<'EOF'
p='build/wxrel/smali/com/ca/hmi/weather/sdk/utils/WeatherUtil.smali'
s=open(p,encoding='utf-8').read()
old='''    sget p0, Lcom/ca/hmi/weather/sdk/R$string;->wind_level:I

    filled-new-array {p3}, [Ljava/lang/Object;

    move-result-object p3

    invoke-virtual {p1, p0, p3}, Landroid/content/Context;->getString(I[Ljava/lang/Object;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "getString(...)"

    invoke-static {p0, p1}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullExpressionValue(Ljava/lang/Object;Ljava/lang/String;)V'''
new='''    new-instance p0, Ljava/lang/StringBuilder;

    invoke-direct {p0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {p0, p3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object p0

    const-string p1, "\\u043c/\\u0441"

    invoke-virtual {p0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object p0

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0'''
assert old in s, "wind_level getString block not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
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

# 4. UI: пробел между направлением ветра и скоростью ("ЮЗ1 м/с" -> "ЮЗ 1 м/с")
#    В getWindForceLabel после :goto_0 склеивается direction + forceLabel("N м/с").
#    forceLabel собран блоком 2b (p0 = speed + " м/с"). Вставляем " " между append(p2=dir)
#    и append(p0=forceLabel). p2 (direction) уже добавлен -> его можно перезаписать.
python3 - <<'EOF'
p='build/wxrel/smali/com/ca/hmi/weather/sdk/utils/WeatherUtil.smali'
s=open(p,encoding='utf-8').read()
old=("    .line 136\n"
"    new-instance p1, Ljava/lang/StringBuilder;\n"
"\n"
"    invoke-direct {p1}, Ljava/lang/StringBuilder;-><init>()V\n"
"\n"
"    invoke-virtual {p1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
"\n"
"    move-result-object p1\n"
"\n"
"    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
"\n"
"    move-result-object p0\n"
"\n"
"    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n")
new=("    .line 136\n"
"    new-instance p1, Ljava/lang/StringBuilder;\n"
"\n"
"    invoke-direct {p1}, Ljava/lang/StringBuilder;-><init>()V\n"
"\n"
"    invoke-virtual {p1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
"\n"
"    move-result-object p1\n"
"\n"
"    const-string p2, \" \"\n"
"\n"
"    invoke-virtual {p1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
"\n"
"    move-result-object p1\n"
"\n"
"    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
"\n"
"    move-result-object p0\n"
"\n"
"    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n")
assert s.count(old)==1, f"wind concat block count={s.count(old)}"
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("WIND SPACING: 'ЮЗ1 м/с' -> 'ЮЗ 1 м/с'")
EOF

# 4b. UI: температура "0 °/0 °" -> "0° / 0°" (TextViewUtil.createTemperatureString)
#    Сейчас: min + " \u00b0/" + max + " \u00b0". Меняем константы на "\u00b0 / " и "\u00b0".
#    RaisedDegreeSpan (indexOf/lastIndexOf '°') найдёт оба ° -> подсветка сохранится.
python3 - <<'EOF'
p='build/wxrel/smali_classes2/com/incall/apps/weather/utils/TextViewUtil.smali'
s=open(p,encoding='utf-8').read()
o1='    const-string p1, " \\u00b0/"'
n1='    const-string p1, "\\u00b0 / "'
assert s.count(o1)==1, f"temp min const count={s.count(o1)}"
s=s.replace(o1,n1,1)
o2='    const-string p1, " \\u00b0"'
n2='    const-string p1, "\\u00b0"'
assert s.count(o2)==1, f"temp max const count={s.count(o2)}"
s=s.replace(o2,n2,1)
open(p,'w',encoding='utf-8').write(s)
print("TEMP SPACING: '0 \u00b0/0 \u00b0' -> '0\u00b0 / 0\u00b0'")
EOF

java -jar "$APKTOOL" b -f -o build/v.apk build/wxrel >/dev/null 2>&1
"$BT/zipalign" -p -f 4 build/v.apk build/va.apk
"$BT/apksigner" sign --key platform-key/platform.pk8 --cert platform-key/platform.x509.pem --out "$OUT" build/va.apk
"$BT/apksigner" verify "$OUT" && ls -la "$OUT"
