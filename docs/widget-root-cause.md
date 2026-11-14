# Виджет погоды: root cause + фикс (RESOLVED 2026-11-14)

## Симптом
Виджет погоды (embedded `WeatherActivity`) пуст, пока не открыть приложение вручную.

## Root cause (2 независимых)

1. **Публичная в event bus отсутствует.** Данные виджету/карте (CAR) приходят
   только через `CoreManager.postEvent(eventName, payload)`. Метод
   `BizService.sendLocationWeatherToOthers()` — ровно для этого — **не вызывается
   никем** в стоковом приложении. Поэтому даже после успешного fetch данные
   никуда не публикуются, и CAR-виджет их не получает.

2. **Нарушенный SDK-connection на эмуляторе.** `initRequest()` (единственный
   источник `requestWeatherData()`) вызывается из `mSdkConnectedStateListener`,
   который срабатывает только при `onServiceConnected` к системному сервису
   `event_module` (bindService S1). На Google-эмуляторе этого сервиса нет →
   listener не fires → fetch не стартует. На реальном A06 сервис есть.

## Фикс

### Публичная (ядро, обязательно)
Патчим SUCCESS-коллектор
`BaseViewModel$getCurrentLocationWeather$1$3.emit(LocationWeather, Continuation)`
(там, где уже `postValue` в `locationWeather` LiveData) → после postValue вызываем
```
WeatherSDK.INSTANCE.getService()
    .sendLocationWeatherToOthers(getCurrentLocation(), locationWeather)
```
Это публикует в event bus с правильным именем события (`app_weather_event`,
BizType=APP из SDK-конфига) и payload `{lat,lng,weatherData:{code:0,data:LocationWeather}}`
— ровно то, что ожидает CAR-виджет.

### Boot-триггер (запасной, для гарантированного старта fetch)
`BootReceiver` (BOOT_COMPLETED + QUICKBOOT_POWERON) → `startActivity(WeatherActivity)`.
Даже если OEM не запустит embedded-активность при старте — fetch стартует от boot.

## Валидация (эмулятор, 2026-11-14)
Цепь подтверждена end-to-end в logcat:
```
onCreated: forcing requestWeatherData   (debug force-fetch, только эмулятор)
GATES perm=true net=true sdk=true
GATE PASSED -> getCurrentLocationWeather
req detail lat=29.56 lng=106.58 (Чунцин)
OK fetch / src=gismeteo
onSuccess: publishing weather to event bus      ← ПАТЧ
CSSDK-SdkManagerImpl: ~ S1[event_module, event_service,
    app_weather_event, {"lat":29.56,"lng":106.58,"weatherData":{"code":0,
    "data":{...Чунцин,21°,阴,5-day forecast...}}}, caller=com.incall.apps.weather
```
Последний binder-hop в `event_module` падает с `-1003` на эмуляторе (сервиса нет) —
это ожидаемо. На реальном A06 сервис есть → hop успешен → CAR-виджет получает данные.

## Сборка

- `scripts/build_prod.sh` → **`apks/Weather-prod.apk`** (для реального A06):
  v10 + PUBLISH + BootReceiver, `sharedUserId=android.uid.system` сохранён,
  **без** debug-проб/force-fetch/putString-stub.
- Подпись: **platform-ключ AOSP testkey** (SHA-256 `c8a2e9bc...`), совпадает с cert v10.
  Keystore: `apks/platform.p12` (alias `android`, pass `android`).
  Источники ключа: `/opt/nevo/changan-a06-ru-assistant/tools/platform-key/`
  (platform.pk8 DER + platform.x509.pem). Конвертация:
  `openssl pkcs8 -inform DER -nocrypt -in platform.pk8` → pkcs12.
- `scripts/build_emu.sh` → `apks/Weather-emu13.apk` (для эмулятора): testkey,
  без sharedUserId, с debug-пробами + force-fetch (валидация).

## Почему не нужно патчить
- `getSdkConnected()` — в v10 уже хардкод `return 1` (true).
- MMKV — приватный (app-process), не cross-process.
- `CarPropertyModel.setProperty` — только READ `OutsideTemperature`, weather не пишет.
- `Settings` — только координаты.
