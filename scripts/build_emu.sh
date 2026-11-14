#!/bin/bash
# Эмуляционная сборка: релизный APK -> без sharedUserId, под testkey AOSP
# (эмулятор Google APIs не принимает платформу ГУ; для тестов на Automotive AVD)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
APKTOOL=${APKTOOL:-tools/apktool.jar}
IN=${IN:-apks/Weather-ru-v10.apk}
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

# === RENAME: OpenMeteoAPI -> GismeteoAPI (класс исторически назван по OpenMeteo, но ходит к Gismeteo) ===
python3 << 'PYEOF'
import glob, os
renamed = 0
for p in glob.glob('build/wxem/smali*/com/nevo/weather/OpenMeteoAPI*.smali'):
    newp = p.replace('OpenMeteoAPI', 'GismeteoAPI')
    os.rename(p, newp)
    renamed += 1
# Replace all class references in ALL smali
for p in glob.glob('build/wxem/smali*/**/*.smali', recursive=True):
    s = open(p).read()
    if 'OpenMeteoAPI' in s:
        s = s.replace('OpenMeteoAPI', 'GismeteoAPI')
        open(p, 'w').write(s)
print(f'RENAME: {renamed} files renamed, all refs replaced')
PYEOF

# === CANCELLATION GUARD: в catch-коллекторе $1$2 игнорировать CancellationException ===
# При pull-to-refresh: onSuccess (thread A) ставит succeed=true, затем handleBlock
# перехватывает JobCancellationException (отмена корутины) -> catch-коллектор
# перезаписывает succeed=false. Стандартный паттерн: отмена != ошибка.
python3 << 'PYEOF'
p = "build/wxem/smali/com/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$2.smali"
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
    print("CANCELLATION GUARD: anchor not found (maybe already patched)")
open(p, "w").write(s)
PYEOF

python3 << 'PYEOF'
p = "build/wxem/smali/com/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel.smali"
s = open(p).read()

# 1. Bump .locals 2 -> 5 in the requestWeatherData override (it only uses v0, safe)
old_l = ".method public requestWeatherData()V\n    .locals 2\n"
if old_l in s:
    s = s.replace(old_l, ".method public requestWeatherData()V\n    .locals 5\n", 1)
    print("locals: bumped to 5")
else:
    print("locals: pattern not found (maybe already bumped)")

# 2. Insert gate-values probe right after invoke-super (fires whenever method is called).
#    Uses v0=perm v1=net v2=sdk v3=StringBuilder v4=scratch (all declared by .locals 5).
anchor = "    invoke-super {p0}, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;->requestWeatherData()V\n"
probe = anchor + (
'    const-string v0, "WXDIAG"\n'
'    const-string v1, "requestWeatherData CALLED (entry)"\n'
'    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
'    invoke-virtual {p0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->getHasPermission()Z\n'
'    move-result v0\n'
'    invoke-virtual {p0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->getHasNetwork()Z\n'
'    move-result v1\n'
'    invoke-virtual {p0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->getSdkConnected()Z\n'
'    move-result v2\n'
'    new-instance v3, Ljava/lang/StringBuilder;\n'
'    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V\n'
'    const-string v4, "GATES perm="\n'
'    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    const-string v4, " net="\n'
'    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    const-string v4, " sdk="\n'
'    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;\n'
'    move-result-object v3\n'
'    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n'
'    move-result-object v3\n'
'    const-string v4, "WXDIAG"\n'
'    invoke-static {v4, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
)
if anchor in s:
    s = s.replace(anchor, probe, 1)
    print("GATE-VALUES probe: inserted OK")
else:
    print("GATE-VALUES probe: anchor not found!")

# 3. Keep GATE PASSED probe before getCurrentLocationWeather (uses v0/v1, declared)
gp_anchor = "    invoke-virtual {p0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->getCurrentLocationWeather()V"
gp = (
"    const-string v0, \"WXDIAG\"\n"
"    const-string v1, \"GATE PASSED -> getCurrentLocationWeather\"\n"
"    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
)
idx = s.find(gp_anchor)
if idx != -1:
    s = s[:idx] + gp + s[idx:]
    print("GATE-PASSED probe: inserted OK")
else:
    print("GATE-PASSED probe: anchor not found!")

open(p, "w").write(s)
PYEOF

# ============================================================================
# PUBLISH: после успешного fetch (onSuccess-коллектор) -> sendLocationWeatherToOthers
# Виджет/карта (CAR) получает данные только через event bus (CoreManager.postEvent).
# В стоковом приложении sendLocationWeatherToOthers НЕ вызывается никем — поэтому
# виджет пуст до ручного открытия. Патчим SUCCESS-коллектор
# (BaseViewModel$getCurrentLocationWeather$1$3.emit(LocationWeather, Continuation)),
# который уже постит LocationWeather в LiveData — и добавляем publish в event bus.
# Идём через WeatherSDK.INSTANCE.getService() (proxy implements IBizService) ->
# BizService.sendLocationWeatherToOthers -> BizModel.sendLocationWeatherEvent ->
# CoreManager.postEvent(имя_события_по_BizType, {lat,lng,weatherData}).
# Это ровно тот путь, который OEM-карта ожидает; BizType берётся из SDK-конфига (APP),
# т.к. это weather-приложение (APP), а не карточка.
# ============================================================================
python3 << 'PYEOF'
import os
p = "build/wxem/smali/com/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$3.smali"
s = open(p).read()

# Точный success-коллектор (типизированный emit с LocationWeather)
anchor = ".method public final emit(Lcom/ca/hmi/weather/sdk/bean/LocationWeather;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;\n"
assert s.count(anchor) == 1, f"emit anchor count={s.count(anchor)}"
s = s.replace(anchor, anchor + "    .locals 3\n", 1) if anchor + "    .locals 2\n" not in s else s

# .locals 2 -> 4 (v0=LocationWeather v1=VehicleLocation v2=proxy v3=BaseViewModel)
old_l = anchor + "    .locals 2\n"
if old_l in s:
    s = s.replace(old_l, anchor + "    .locals 4\n", 1)
    print("locals: bumped 2->4 in success emit")

# Точка вставки: сразу после первого postValue в locationWeather (данные уже в LiveData,
# до getWeatherRefreshResult — там p1/p2 перезаписываются).
# p0=this(lamba), p1=LocationWeather, p2=MutableLiveData (после move-result-object p2).
# ВАЖНО: BaseViewModel — не p0, а поле this$0 лямбды (iget-object из p0).
postv = "    invoke-virtual {p2, p1}, Landroidx/lifecycle/MutableLiveData;->postValue(Ljava/lang/Object;)V\n"
assert s.count(postv) >= 1, "postValue anchor not found"

inject = postv + (
    # --- ПUBLISH в event bus (после успешного postValue в locationWeather) ---
    '    const-string v1, "WXDIAG"\n'
    '    const-string v2, "onSuccess: publishing weather to event bus"\n'
    '    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
    '    # v0 = LocationWeather (p1)\n'
    '    move-object v0, p1\n'
    '    # v3 = BaseViewModel (this$0 лямбды)\n'
    '    iget-object v3, p0, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel$getCurrentLocationWeather$1$3;->this$0:Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;\n'
    '    # v1 = VehicleLocation (getCurrentLocation, non-null: DEFAULT_LOCATION)\n'
    '    invoke-virtual {v3}, Lcom/ca/hmi/weather/ue/viewmodel/BaseViewModel;->getCurrentLocation()Lcom/ca/hmi/weather/sdk/bean/VehicleLocation;\n'
    '    move-result-object v1\n'
    '    # v2 = WeatherSDK proxy (implements IBizService)\n'
    '    sget-object v2, Lcom/ca/hmi/weather/sdk/WeatherSDK;->INSTANCE:Lcom/ca/hmi/weather/sdk/WeatherSDK;\n'
    '    invoke-virtual {v2}, Lcom/ca/hmi/weather/sdk/WeatherSDK;->getService()Lcom/ca/hmi/weather/sdk/WeatherSDK$IServiceProxy;\n'
    '    move-result-object v2\n'
    '    # publish: sendLocationWeatherToOthers(location, locationWeather)\n'
    '    invoke-interface {v2, v1, v0}, Lcom/ca/hmi/weather/sdk/service/biz/IBizService;->sendLocationWeatherToOthers(Lcom/ca/hmi/weather/sdk/bean/VehicleLocation;Lcom/ca/hmi/weather/sdk/bean/LocationWeather;)V\n'
    '    const-string v1, "WXDIAG"\n'
    '    const-string v2, "onSuccess: sendLocationWeatherToOthers returned"\n'
    '    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
)
# вставляем после ПЕРВОГО postValue (в locationWeather) — это успех, не weatherRefreshResult
s = s.replace(postv, inject, 1)
print("PUBLISH: injected after first locationWeather.postValue")
open(p, "w").write(s)
PYEOF

# === REFRESH-RESULT: checkRefreshResult требует finished+succeed на ВСЕХ 3 запросах,
# но GismeteoAPI заполняет только locationWeather. Патчим: finished/succeed определяются
# только по locationWeather (alerts/predictions — опциональные). ===
python3 << 'PYEOF'
p = "build/wxem/smali_classes2/com/incall/apps/weather/activity/WeatherActivity.smali"
s = open(p).read()

# 1) finished: after first if-eqz (locationWeather check), skip weatherAlert+predictWeather
anchor_f = (
    "    if-eqz v0, :cond_0\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
replace_f = (
    "    if-eqz v0, :cond_0\n"
    "\n"
    "    # PATCH: only locationWeather.finished matters\n"
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

# 2) succeed: after first if-eqz (locationWeather check), skip weatherAlert+predictWeather
anchor_s = (
    "    if-eqz v3, :cond_1\n"
    "\n"
    "    invoke-virtual {p1}, Lcom/ca/hmi/weather/sdk/bean/WeatherRefreshResult;->getWeatherAlert()Lcom/ca/hmi/weather/sdk/bean/RequestActionResult;\n"
)
replace_s = (
    "    if-eqz v3, :cond_1\n"
    "\n"
    "    # PATCH: only locationWeather.succeed matters\n"
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
# Race: new onSuccess posts succeed=true, then old request's JobCancellationException
# posts succeed=false (shared WeatherRefreshResult). Two checkRefreshResult calls:
# first → "刷新成功", second → "刷新失败". If locationWeather LiveData already has
# a value (Gismeteo succeeded at least once), force success toast.
python3 << 'PYEOF'
p = "build/wxem/smali_classes2/com/incall/apps/weather/activity/WeatherActivity.smali"
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
        "    # TOAST GUARD: if locationWeather LiveData already has data, force success\n"
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
        "    # has data -> force success toast\n"
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

# ============================================================================
# DEBUG FORCE-FETCH (только для валидации на эмуляторе)
# На Google-эмуляторе системный сервис event_module (bindService S1) отсутствует,
# поэтому mSdkConnectedStateListener -> initRequest() НИКОГДА не срабатывает и
# fetch не стартует. Для end-to-end проверки патча PUBLISH принудительно
# запускаем requestWeatherData() в onCreated (hasPermission/hasNetwork=true).
# На реальном A06 event_module есть -> initRequest() работает штатно; этот
# форс-вызов идемпотентен (requestWeatherData защищён теми же гейтами) и не ломает
# штатный путь. В production-сборке (для машины) блокируется флагом NO_FORCEF.
# ============================================================================
if [ -z "${NO_FORCEF:-}" ]; then
python3 - <<'EOF'
p = "build/wxem/smali_classes2/com/incall/apps/weather/activity/WeatherActivity.smali"
s = open(p).read()

anchor = "    invoke-super {p0, p1}, Lcom/ca/hmi/ue/view/CAActivity;->onCreated(Landroid/os/Bundle;)V\n"
assert s.count(anchor) == 1, f"onCreated super anchor count={s.count(anchor)}"

# .locals 2 -> 3 (v2 = AppWeatherViewModel)
old_l = ".method protected onCreated(Landroid/os/Bundle;)V\n    .locals 2\n"
if old_l in s:
    s = s.replace(old_l, ".method protected onCreated(Landroid/os/Bundle;)V\n    .locals 3\n", 1)
    print("onCreated locals: bumped 2->3")

inject = anchor + (
    '    # WXDIAG: onCreated -> forced fetch (emulator validation)\n'
    '    const-string v0, "WXDIAG"\n'
    '    const-string v1, "onCreated: forcing requestWeatherData (emulator)"\n'
    '    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
    '    invoke-direct {p0}, Lcom/incall/apps/weather/activity/WeatherActivity;->getMAppWeatherViewModel()Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;\n'
    '    move-result-object v2\n'
    '    const/4 v0, 0x1\n'
    '    invoke-virtual {v2, v0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->setHasPermission(Z)V\n'
    '    invoke-virtual {v2, v0}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->setHasNetwork(Z)V\n'
    '    invoke-virtual {v2}, Lcom/ca/hmi/weather/ue/viewmodel/AppWeatherViewModel;->requestWeatherData()V\n'
)
s = s.replace(anchor, inject, 1)
print("FORCE-FETCH: injected in onCreated")
open(p, "w").write(s)
EOF
fi

# profileinstaller конфликтует с android.car.jar на automotive-имидже
sed -i '/androidx.profileinstaller.ProfileInstallerInitializer/d' build/wxem/AndroidManifest.xml

# === BOOT_COMPLETED: триггер headless-fetch при старте системы ===
# Виджет (embedded WeatherActivity) не показывает данные до ручного запуска,
# т.к. requestWeatherData() вызывается только в onCreated Activity.
# BootReceiver стартует WeatherActivity (singleTask, allowEmbedded) при
# BOOT_COMPLETED -> fetch стартует без пользователя -> MMKV/LiveData заполняются
# до того, как лаунчер отрисовывает виджет.
# См. /root/nevo-weather/docs/widget-root-cause.md
python3 - <<'EOF'
import os, re
# 1. Create BootReceiver.smali (in smali_classes2, same dir as widget/)
pkg = 'build/wxem/smali_classes2/com/incall/apps/weather'
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

    # Log via android.util.Log (no CALog init dependency)
    const-string v0, "WXDIAG"

    const-string v1, "BootReceiver onReceive: launching WeatherActivity for boot fetch"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    # Build Intent for WeatherActivity
    new-instance v0, Landroid/content/Intent;

    const-class v1, Lcom/incall/apps/weather/activity/WeatherActivity;

    invoke-direct {v0, p1, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    # FLAG_ACTIVITY_NEW_TASK = 0x10000000 (required for startActivity from a
    # BroadcastReceiver; without it Android throws AndroidRuntimeException)
    const v1, 0x10000000

    invoke-virtual {v0, v1}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    move-result-object v0

    # Start activity (headless fetch trigger)
    invoke-virtual {p1, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V

    # NOTE: BroadcastReceiver.onReceive is abstract — do NOT call invoke-super
    return-void
.end method
'''
with open(bp, 'w') as f:
    f.write(smali)
print('BootReceiver.smali written to', bp)

# 2. Add BOOT_COMPLETED permission + receiver to AndroidManifest.xml
mp = 'build/wxem/AndroidManifest.xml'
s = open(mp).read()

# Add RECEIVE_BOOT_COMPLETED permission if not present
if 'RECEIVE_BOOT_COMPLETED' not in s:
    s = s.replace(
        '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>',
        '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>\n    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>',
        1
    )
    print('Added RECEIVE_BOOT_COMPLETED permission')

# Add BootReceiver before </application>
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


java -jar "$APKTOOL" b -f -o build/eu.apk build/wxem >/dev/null 2>&1
"$BT/zipalign" -p -f 4 build/eu.apk build/eua.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias android --out "$OUT" build/eua.apk
"$BT/apksigner" verify "$OUT" && ls -la "$OUT"
