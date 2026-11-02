# nevo-a06-wheather

Погода на головном устройстве Changan (NEVO A06): адаптация стокового
`com.incall.apps.weather` на API Gismeteo.

Стоковое приложение тянет прогноз с китайских серверов (polyDomain/HTTPDNS
119.29.29.29), которые из нашей сети недоступны — UI показывал
«Не удалось загрузить». Мы подменяем источник данных своим классом,
сохраняя родной UI (единственная причина не писать своё — штатный виджет
погоды встаёт в штатное место на рабочем столе ГУ).

## Что работает

- город по геопозиции (Gismeteo cities lookup по координатам),
- текущая погода (taker из ближайшего почасового слота — блок `<fact>`
  у Gismeteo без подписанного X-Gismeteo-Timestamp отдаёт мусор в `t=`),
- ветер: русские 16-румбовые направления + м/с (в стоке — китайские баллы Бофорта),
- почасовой график 24ч, прогноз на 10 дней (Gismeteo даёт 7 дней + паддинг),
- виджет на рабочем столе (гейт sdkConnected снят),
- всё без VPN/прокси, только `services.gismeteo.net`.

## Структура

    apks/
      weather-stock-original.apk   исходный сток (com.incall.apps.weather)
      Weather-ru-v6.apk            ru-локализация сток (вход для сборки)
      Weather-ru-v8.apk            релиз: Gismeteo-адаптер, подпись platform key
      Weather-emu13.apk            сборка для эмулятора (без sharedUserId)
    src/com/nevo/weather/          наш код: OpenMeteoAPI (Gismeteo XML адаптер), Diags
    stub/                          compile-only заглушки классов приложения для javac
    scripts/build_dex.sh           javac -> d8 -> build/dex/classes.dex
    scripts/build_rel.sh           полная релизная сборка (патчи smali + dex + подпись)
    scripts/build_emu.sh           эмуляционная сборка (testkey, без sharedUserId)
    keys/platform.pk8|pem          AOSP testkey (ГУ подписан тем же ключом)
    docs/                          скриншоты с эмулятора

## Сборка

    scripts/build_rel.sh          # -> apks/Weather-ru-v8.apk (для ГУ)
    scripts/build_emu.sh          # -> apks/Weather-emu13.apk (для эмулятора)

Требуются: JDK 17, Android build-tools 34 (`$BT`), platform android-34 (`$PLATFORM`),
`tools/apktool.jar`.

## Как это устроено

1. `OpenMeteoAPI` (src/) внедряется отдельным `classes4.dex` и полностью
   подменяет сетевой слой стока: реализует `IWeatherAPI`, ходит на
   `services.gismeteo.net/inform-service/inf_android/forecast/?city=<id>&lang=ru`
   (XML, без ключа) с UA `Gismeteo Android v2, Google Pixel 6 14`.
2. smali-патчи стока (делаются в build_rel.sh, в репо не хранятся):
   - HttpDNS.getIPByHost -> null (китайский HTTPDNS недоступен, okhttp
     использует системный DNS);
   - wind_level: `%1$s级` -> `&#160;%1$s м/с`;
   - BaseViewModel: sdkConnected=true — иначе виджет не грузится без
     активной Activity.
3. Подпись platform.pk8 (AOSP testkey): на ГУ приложение получает
   sharedUserId=system -> WRITE_SECURE_SETTINGS (пишет weather_last_time в
   Settings.Global — родное поведение).

## Диагностика

Каждый сетевой путь пишет `E/WXDIAG` в logcat и `/storage/emulated/0/Download/wxdiag.txt`.
Приложение обновляет данные по будильнику AlarmClock (~раз в час); при старте
ForceStop+AlarmManager не догорает, поэтому для быстрой проверки:
`adb shell am force-stop com.incall.apps.weather && monkey -p com.incall.apps.weather 1`.

## Статус

v8 проверен на automotive-эмуляторе (API 34): город, текущая, ветер м/с по-русски,
24ч график, 10-Day — работают, иероглифов нет. Установка на ГУ: положить
`Weather-ru-v8.apk` в Download / файловый менеджер (или поверх — та же подпись).
