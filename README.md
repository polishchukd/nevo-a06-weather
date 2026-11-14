# nevo-a06-weather

Погода на головном устройстве Changan (NEVO A06): адаптация стокового
`com.incall.apps.weather` на API Gismeteo.

Стоковое приложение тянет прогноз с китайских серверов (polyDomain/HTTPDNS
119.29.29.29), которые из нашей сети недоступны — UI показывал
«Не удалось загрузить». Мы подменяем источник данных своим классом,
сохраняя родной UI (единственная причина не писать своё — штатный виджет
погоды встаёт в штатное место на рабочем столе ГУ).

## Что работает

- город по геопозиции (cities lookup Gismeteo по координатам; кэш резолва по
  координатам ~1 км + TTL 6 ч — при переезде в другой город город
  переопределяется),
- текущая погода (у Gismeteo — из ближайшего почасового слота: блок `<fact>`
  без подписанного X-Gismeteo-Timestamp отдаёт мусор в `t=`),
- ветер: русские 16-румбовые направления + м/с, «Штиль» при затишье
  (в стоке — китайские баллы Бофорта),
- почасовой график 24ч, прогноз на 10 дней (Gismeteo даёт 7 дней + паддинг
  последней неделённой),
- виджет на рабочем столе (гейт sdkConnected снят),
- качество воздуха (AQI): источник **IQAir** (airvisual.com v2) — Gismeteo AQI
  не отдаёт. См. раздел «Качество воздуха» ниже. Все запросы по https.

> Ранее использовались `api.met.no`, `api.open-meteo.com`,
> `air-quality-api.open-meteo.com` и `api.bigdatacloud.net`, но они
> заблокированы ТСПУ: каждый запрос к ним сжигал десятки секунд на таймаутах,
> из-за чего первое обновление не успевало в окно обновления стокового UI и
> он показывал «не удалось обновить». Теперь обращаемся только к Gismeteo.

## Структура

    apks/
      Weather-ru-v9.apk            релиз: Open-Meteo/Gismeteo адаптер, подпись platform key
    src/com/nevo/weather/          наш код: GismeteoAPI (Gismeteo XML адаптер), Diags
    stub/                          compile-only заглушки классов приложения для javac
    scripts/build_dex.sh           javac -> d8 -> build/dex/classes.dex
    scripts/build_rel.sh           полная релизная сборка (патчи smali + dex + подпись)
    scripts/build_emu.sh           эмуляционная сборка (testkey, без sharedUserId)
    keys/platform.pk8|pem          AOSP testkey (ГУ подписан тем же ключом)
    docs/                          скриншоты с эмулятора

## Сборка

    scripts/build_rel.sh          # -> apks/Weather-ru-v9.apk (для ГУ)
    scripts/build_emu.sh          # -> apks/Weather-emu13.apk (для эмулятора)

Требуются: JDK 17, Android build-tools 34 (`$BT`), platform android-34 (`$PLATFORM`),
`tools/apktool.jar`.

## Как это устроено

1. `GismeteoAPI` (src/) внедряется отдельным `classes4.dex` и полностью
   подменяет сетевой слой стока: реализует `IWeatherAPI`. Единственный
   источник прогноза — Gismeteo
   (`services.gismeteo.net/inform-service/inf_android/forecast/?city=<id>&lang=ru`,
   XML, UA `Gismeteo Android v2, Changan Nevo 13`, 7 дней + паддинг; город
   резолвится `.../cities/?lat=%s&lng=%s`). Обратное геокодирование не
   используется (bigdatacloud заблокирован ТСПУ). Качество воздуха тянется
   отдельно от IQAir — см. «Качество воздуха». Все запросы по https
   (targetSdk 34 блокирует cleartext). Таймауты короткие (connect 4с, read 7с,
   2 попытки), чтобы недоступный хост падал быстро и не сжигал окно обновления
   UI.
2. smali-патчи стока (делаются в build_rel.sh, в репо не хранятся):
   - HttpDNS.getIPByHost -> null (китайский HTTPDNS недоступен, okhttp
     использует системный DNS);
   - wind_level: `%1$s级` -> `&#160;%1$s м/с`;
   - BaseViewModel: sdkConnected=true — иначе виджет не грузится без
     активной Activity.
3. Подпись platform.pk8 (AOSP testkey): на ГУ приложение получает
   sharedUserId=system -> WRITE_SECURE_SETTINGS (пишет weather_last_time в
   Settings.Global — родное поведение).

## Качество воздуха (AQI)

Gismeteo AQI не отдаёт (XML-прогноз содержит только метео-поля). Поэтому
качество воздуха тянется отдельно от **IQAir** (airvisual.com v2):

- Эндпоинт: `https://api.airvisual.com/v2/nearest_city?lat=%s&lon=%s&key=<KEY>`
  (по координатам; free-тариф 500 req/день, 100 req/мин).
- В коде: `GismeteoAPI.fetchAqi()` парсит `aqicn` (китайская шкала AQI).
  Именно она используется, потому что `getLevel()` стокового UI калиброван под
  китайскую шкалу (0–50 Отлично, 50–100 Хорошо, 100–150 Слегка вредно,
  150–200 Вредно, 200–300 Очень вредно, 300+ Опасно). Число и цвет уровня
  совпадают.
- UI патчить не нужно: `WeatherActivity` сам читает `getAirQualityDto()` и
  рендерит `getAqi()` + `getAirLevelLabel()`. Мы просто заполняем бин
  `AirQuality` реальным значением вместо нуля.
- Кэш 15 мин по координатам — сглаживает лимит 100 req/мин и burst
  (detail+hours+widget одновременно). При сбое сети/лимита держит последнее
  валидное значение; если его нет — нулевое (UI показывает «-»/другое).
- Free-тариф возвращает только `aqius`/`aqicn` + основной загрязнитель,
  без разбивки по поллютантам — поэтому pm25/pm10/no2/so2/o3/co = 0.

**Внимание — срок действия API-ключа: до 9 ноября 2027.** Ключ (UUID) лежит в
константе `IQ_KEY` в `src/com/nevo/weather/GismeteoAPI.java`. После 09.11.2027
запросы начнут возвращать `401/permission_denied` и AQI перестанет
обновляться (остальная погода не пострадает — IQAir изолирован). Нужно будет
продлить/переиздать ключ на airvisual.com и заменить значение `IQ_KEY`.

> Проверено (Новосибирск): aqius=59 / aqicn=19. Источник в НСК — 3 кастомные
> станции 2 частных участников (crowdsourced, не Росгидромет), поэтому
> точность чуть ниже официальной; для AQI в виджете приемлемо.

## Диагностика

Каждый сетевой путь пишет `E/WXDIAG` в logcat и `/storage/emulated/0/Download/wxdiag.txt`.
Приложение обновляет данные по будильнику AlarmClock (~раз в час);
при старте ForceStop+AlarmManager не догорает, поэтому для быстрой проверки:
`adb shell am force-stop com.incall.apps.weather && monkey -p com.incall.apps.weather 1`.
