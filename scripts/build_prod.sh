#!/bin/bash
# PRODUCTION-сборка для реального Changan A06:
#   - SOU v10 (sharedUserId=android.uid.system) + PUBLISH-патч (виджет->event bus)
#   - + BootReceiver (BOOT_COMPLETED -> WeatherActivity -> fetch)
#   - БЕЗ debug-проб, БЕЗ force-fetch, БЕЗ putString-stub (system-uid)
#   - подпись platform-ключом (AOSP testkey = SHA256 c8a2e9bc..., совпадает с v10)
#
# Использование:  bash scripts/build_prod.sh
#   Результат:    apks/Weather-prod.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
APKTOOL=${APKTOOL:-tools/apktool.jar}
IN=${IN:-apks/Weather-ru-v10.apk}
OUT=${OUT:-apks/Weather-prod.apk}
KS=${KS:-apks/platform.p12}    # platform key (AOSP testkey), alias=android, pass=android

# --- keystore: если platform.p12 отсутствует, собрать из platform.pk8/x509.pem ---
if [ ! -f "$KS" ]; then
  PLAT=/opt/nevo/changan-a06-ru-assistant/tools/platform-key
  openssl pkcs8 -inform DER -nocrypt -in "$PLAT/platform.pk8" -out /tmp/_priv.pem
  openssl pkcs12 -export -in "$PLAT/platform.x509.pem" -inkey /tmp/_priv.pem \
    -name android -out "$KS" -passout pass:android
  rm -f /tmp/_priv.pem
  echo "platform.p12 created from $PLAT"
fi

rm -rf build/wxpr
java -jar "$APKTOOL" d -f -o build/wxpr "$IN" >/dev/null 2>&1
# ВАЖНО: sharedUserId=android.uid.system НЕ трогаем (это production)

# === RENAME: OpenMeteoAPI -> GismeteoAPI ===
python3 << 'PYEOF'
import glob, os
renamed = 0
for p in glob.glob('build/wxpr/smali*/com/nevo/weather/OpenMeteoAPI*.smali'):
    newp = p.replace('OpenMeteoAPI', 'GismeteoAPI')
    os.rename(p, newp)
    renamed += 1
for p in glob.glob('build/wxpr/smali*/**/*.smali', recursive=True):
    s = open(p).read()
    if 'OpenMeteoAPI' in s:
        s = s.replace('OpenMeteoAPI', 'GismeteoAPI')
        open(p, 'w').write(s)
print(f'RENAME: {renamed} files renamed, all refs replaced')
PYEOF

# ----------------------------------------------------------------------------
# PUBLISH: после успешного fetch (onSuccess-коллектор) -> sendLocationWeatherToOthers
# (см. docs/widget-root-cause.md; valid on emulator 2026-11-14: event bus получил
#  app_weather_event с payload Чунцин/21°/5-day forecast)
# ----------------------------------------------------------------------------
python3 << 'PYEOF'
p = "build/wxpr/smali/com/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$3.smali"
s = open(p).read()
anchor = ".method public final emit(Lcom/ca/hmi/weather/sdk/bean/LocationWeather;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;\n"
assert s.count(anchor) == 1, f"emit anchor count={s.count(anchor)}"
# .locals 2 -> 5 (v0-v4: v4=LocationWeather save, v0/v1/v2=scratch)
old_l = anchor + "    .locals 2\n"
if old_l in s:
    s = s.replace(old_l, anchor + "    .locals 5\n", 1)
    print("PUBLISH locals: 2->5")
postv = "    invoke-virtual {p2, p1}, Landroidx/lifecycle/MutableLiveData;->postValue(Ljava/lang/Object;)V\n"
assert s.count(postv) >= 1, "postValue anchor not found"
inject = postv + (
    '    # PUBLISH: send weather to event bus (non-fatal, try/catch)\n'
    '    move-object v4, p1\n'
    '    .catch Ljava/lang/Throwable; {:try_start_pub .. :try_end_pub} :catch_pub\n'
    '    :try_start_pub\n'
    '    iget-object v0, p0, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$3;->this$0:Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;\n'
    '    invoke-virtual {v0}, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;->getCurrentLocation()Lcom/ca/hmi/weather/sdk/bean/VehicleLocation;\n'
    '    move-result-object v0\n'
    '    sget-object v1, Lcom/ca/hmi/weather/sdk/WeatherSDK;->INSTANCE:Lcom/ca/hmi/weather/sdk/WeatherSDK;\n'
    '    invoke-virtual {v1}, Lcom/ca/hmi/weather/sdk/WeatherSDK;->getService()Lcom/ca/hmi/weather/sdk/WeatherSDK$IServiceProxy;\n'
    '    move-result-object v1\n'
    '    if-eqz v1, :cond_pub_skip\n'
    '    invoke-interface {v1, v0, v4}, Lcom/ca/hmi/weather/sdk/service/biz/IBizService;->sendLocationWeatherToOthers(Lcom/ca/hmi/weather/sdk/bean/VehicleLocation;Lcom/ca/hmi/weather/sdk/bean/LocationWeather;)V\n'
    '    :cond_pub_skip\n'
    '    :try_end_pub\n'
    '    goto :goto_pub_done\n'
    '    :catch_pub\n'
    '    move-exception v0\n'
    '    const-string v1, "WXDIAG"\n'
    '    const-string v2, "publish to event bus failed (non-fatal)"\n'
    '    invoke-static {v1, v2, v0}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I\n'
    '    :goto_pub_done\n'
)
s = s.replace(postv, inject, 1)
print("PUBLISH: injected with try/catch + null-guard (no debug probes)")
open(p, "w").write(s)
PYEOF

# === CANCELLATION GUARD: в catch-коллекторе $1$2 игнорировать CancellationException ===
python3 << 'PYEOF'
p = "build/wxpr/smali/com/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$2.smali"
s = open(p).read()
anchor = "    check-cast p1, Ljava/lang/Throwable;\n\n    .line 271\n"
if anchor in s:
    guard = (
        "    check-cast p1, Ljava/lang/Throwable;\n"
        "\n"
        "    # CANCELLATION GUARD: ignore CancellationException (not a real error)\n"
        "    instance-of v0, p1, Lkotlinx/coroutines/CancellationException;\n"
        "\n"
        "    if-eqz v0, :cond_cancel_guard\n"
        "\n"
        "    sget-object p0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;\n"
        "\n"
        "    return-object p0\n"
        "\n"
        "    :cond_cancel_guard\n"
        "\n"
        "    .line 271\n"
    )
    s = s.replace(anchor, guard, 1)
    print("CANCELLATION GUARD: injected")
else:
    print("CANCELLATION GUARD: anchor not found")
open(p, "w").write(s)
PYEOF

# === REFRESH-RESULT: только locationWeather определяет finished/succeed ===
python3 << 'PYEOF'
p = "build/wxpr/smali_classes2/com/incall/apps/weather/activity/WeatherActivity.smali"
s = open(p).read()
anchor_f = (
    "    if-eqz v0, :cond_0\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
replace_f = (
    "    if-eqz v0, :cond_0\n"
    "\n"
    "    move v0, v1\n"
    "\n"
    "    goto :goto_0\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
if anchor_f in s:
    s = s.replace(anchor_f, replace_f, 1)
    print("REFRESH-PATCH finished: ok")
else:
    print("REFRESH-PATCH finished: anchor not found")
anchor_s = (
    "    if-eqz v3, :cond_1\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
replace_s = (
    "    if-eqz v3, :cond_1\n"
    "\n"
    "    move p1, v1\n"
    "\n"
    "    goto :goto_1\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
if anchor_s in s:
    s = s.replace(anchor_s, replace_s, 1)
    print("REFRESH-PATCH succeed: ok")
else:
    print("REFRESH-PATCH succeed: anchor not found")
open(p, "w").write(s)
PYEOF

# === TOAST GUARD: suppress "刷新失败" when locationWeather LiveData already has data ===
python3 << 'PYEOF'
p = "build/wxpr/smali_classes2/com/incall/apps/weather/activity/WeatherActivity.smali"
s = open(p).read()
anchor = (
    "    invoke-static {}, Lcom/incall/apps/caui/toast/ToastHelper;->cancel()V\n"
    "\n"
    "    if-eqz p1, :cond_7\n"
)
if anchor in s:
    guard = (
        "    invoke-static {}, Lcom/incall/apps/caui/toast/ToastHelper;->cancel()V\n"
        "\n"
        "    invoke-direct {p0}, Lcom/incall/apps/weather/activity/WeatherActivity;->getMAppWeatherViewModel()Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;\n"
        "\n"
        "    move-result-object v3\n"
        "\n"
        "    invoke-virtual {v3}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->getLocationWeather()Landroidx/lifecycle/MutableLiveData;\n"
        "\n"
        "    move-result-object v3\n"
        "\n"
        "    invoke-virtual {v3}, Landroidx/lifecycle/MutableLiveData;->getValue()Ljava/lang/Object;\n"
        "\n"
        "    move-result-object v3\n"
        "\n"
        "    if-eqz v3, :cond_tg_no_data\n"
        "\n"
        "    move-object p1, p0\n"
        "\n"
        "    check-cast p1, Landroid/content/Context;\n"
        "\n"
        "    sget v0, Lcom/ca/hmi/weather/sdk/R$string;->refresh_success:I\n"
        "\n"
        "    invoke-virtual {p0, v0}, Lcom/incall/apps/weather/activity/WeatherActivity;->getString(I)Ljava/lang/String;\n"
        "\n"
        "    move-result-object p0\n"
        "\n"
        "    check-cast p0, Ljava/lang/CharSequence;\n"
        "\n"
        "    invoke-static {p1, p0}, Lcom/incall/apps/caui/toast/ToastHelper;->showSuccessToast(Landroid/content/Context;Ljava/lang/CharSequence;)V\n"
        "\n"
        "    goto :goto_3\n"
        "\n"
        "    :cond_tg_no_data\n"
        "    if-eqz p1, :cond_7\n"
    )
    s = s.replace(anchor, guard, 1)
    print("TOAST GUARD: injected")
else:
    print("TOAST GUARD: anchor not found")

# Also patch handleWeatherError: if locationWeather has data, suppress error toast
anchor_hw = (
    "    if-eqz p1, :cond_4\n"
    "\n"
    "    .line 439\n"
)
if anchor_hw in s:
    patch_hw = (
        "    if-eqz p1, :cond_4\n"
        "\n"
        "    # TOAST GUARD 2: if locationWeather already has data, skip error\n"
        "    if-nez v0, :cond_4\n"
        "\n"
        "    .line 439\n"
    )
    s = s.replace(anchor_hw, patch_hw, 1)
    print("TOAST GUARD 2 (handleWeatherError): injected")
else:
    print("TOAST GUARD 2: anchor not found")

open(p, "w").write(s)
PYEOF

# ----------------------------------------------------------------------------
# BOOT_COMPLETED: BootReceiver -> WeatherActivity при старте системы
# ----------------------------------------------------------------------------
python3 << 'EOF'
import os
pkg = 'build/wxpr/smali_classes2/com/incall/apps/weather'
os.makedirs(pkg, exist_ok=True)
bp = os.path.join(pkg, 'BootReceiver.smali')
smali = '''.class public Lcom/incall/apps/weather/BootReceiver;
.super Landroid/content/BroadcastReceiver;
.source "BootReceiver.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/content/BroadcastReceiver;-><init>()V

    return-void
.end method


# virtual methods
.method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    .locals 2

    const-string v0, "WXDIAG"

    const-string v1, "BootReceiver onReceive: launching WeatherActivity for boot fetch"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    new-instance v0, Landroid/content/Intent;

    const-class v1, Lcom/incall/apps/weather/activity/WeatherActivity;

    invoke-direct {v0, p1, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    # FLAG_ACTIVITY_NEW_TASK = 0x10000000
    const v1, 0x10000000

    invoke-virtual {v0, v1}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    move-result-object v0

    invoke-virtual {p1, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V

    # BroadcastReceiver.onReceive — abstract, invoke-super не нужен
    return-void
.end method
'''
open(bp, 'w').write(smali)
print('BootReceiver.smali written')

mp = 'build/wxpr/AndroidManifest.xml'
s = open(mp).read()
if 'RECEIVE_BOOT_COMPLETED' not in s:
    s = s.replace(
        '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>',
        '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>\n    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>',
        1
    )
    print('Added RECEIVE_BOOT_COMPLETED permission')
receiver = '''        <receiver android:exported="true" android:name="com.incall.apps.weather.BootReceiver">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
                <action android:name="android.intent.action.QUICKBOOT_POWERON"/>
            </intent-filter>
        </receiver>
    </application>'''
s = s.replace('</application>', receiver, 1)
print('Added BootReceiver to manifest')
open(mp, 'w').write(s)
EOF

# ----------------------------------------------------------------------------
# build + zipalign + sign (platform key) + verify
# ----------------------------------------------------------------------------
java -jar "$APKTOOL" b -f -o build/wp.apk build/wxpr >/dev/null 2>&1
"$BT/zipalign" -p -f 4 build/wp.apk build/wpa.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias android --out "$OUT" build/wpa.apk
"$BT/apksigner" verify "$OUT" && echo "SIGN OK" && ls -la "$OUT"

# --- verify: cert должен совпадать с v10 (c8a2e9bc...) ---
echo "=== PRODUCTION cert (должен быть c8a2e9bc...) ==="
"$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null | grep -iE "SHA-256|certificate DN"
echo "=== v10 cert (reference) ==="
"$BT/apksigner" verify --print-certs "$IN" 2>/dev/null | grep -iE "SHA-256"
