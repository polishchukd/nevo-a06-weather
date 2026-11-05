package com.nevo.weather;

import com.ca.hmi.weather.sdk.api.IWeatherAPI;
import com.ca.hmi.weather.sdk.bean.AirQuality;
import com.ca.hmi.weather.sdk.bean.CityInfo;
import com.ca.hmi.weather.sdk.bean.LocationWeather;
import com.ca.hmi.weather.sdk.bean.PredictWeather;
import com.ca.hmi.weather.sdk.bean.PredictWeatherData;
import com.ca.hmi.weather.sdk.bean.VehicleLocation;
import com.ca.hmi.weather.sdk.bean.WeatherAlert;
import com.ca.hmi.weather.sdk.bean.WeatherInfo;
import com.incall.apps.component.okhttp.bean.BaseNewSO;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;

/**
 * Serverless replacement of the Changan SDA weather backend.
 * Talks to the public Gismeteo Android inform API directly (the only
 * weather source reachable from RU head-unit networks) and produces the
 * same bean objects the stock UI expects. Read-only, no account, no proxy.
 */
public final class OpenMeteoAPI implements IWeatherAPI {

    // Forecast source: Gismeteo Android inform API ONLY (extracted from
    // Gismeteo-1113 APK, ru.gismeteo: GMHttpRequest.prepareUrlString). The
    // other sources previously used (api.met.no, api.open-meteo.com,
    // air-quality-api.open-meteo.com, api.bigdatacloud.net) are blocked by
    // TSPU on RU head-unit networks — every request to them burned tens of
    // seconds on connect/read timeouts, so the first update overran the stock
    // UI's refresh window and it showed "refresh failed". Gismeteo is the only
    // reachable source, so it is the only one contacted. XML, only 7 days —
    // the tail of the 10-day tab is padded with the last day.
    private static final String GM_UA = "Gismeteo Android v2, Changan Nevo 13";
    private static final String GM_CITY_URL =
        "https://services.gismeteo.net/inform-service/inf_android/cities/"
        + "?lat_lng=1&lat=%s&lng=%s&count=1&lang=ru&with_facts=0&real_distance=0";
    private static final String GM_FC_URL =
        "https://services.gismeteo.net/inform-service/inf_android/forecast/"
        + "?city=%s&lang=ru";
    // City lookup cache keyed by rounded coordinates with a TTL, so moving to
    // another city re-resolves instead of reusing the first city's forecast.
    private static final long GM_CITY_TTL_MS = 6 * 60 * 60 * 1000L; // 6h
    private static volatile String gmCityKey = null;
    private static volatile String gmCityId = null;
    private static volatile String gmCityName = null;
    private static volatile long gmCityAt = 0L;

    // Forecast cache: the stock app fires detail+hours from the Activity and
    // the widget simultaneously (widget updatePeriodMillis=1000), producing
    // bursts of identical requests. Collapse a burst into one network round
    // trip; short TTL so a real move to another location refreshes quickly.
    private static final long FC_TTL_MS = 5 * 60 * 1000L; // 5 min
    private static final double FC_KEY_STEP = 0.01;        // ~1 km
    private static volatile String fcKey = null;
    private static volatile JSONObject fcCache = null;
    private static volatile String fcName = null;
    private static volatile long fcAt = 0L;

    // WMO weather code -> Chinese phenomena string used by the stock UI
    // (dictionary WeatherTypeUtils.MAP of the stock app).
    private static String phenomena(long code) {
        switch ((int) code) {
            case 0: return "\u6674";                       // clear
            case 1: return "\u5927\u90e8\u6674\u6717";     // mostly sunny
            case 2: return "\u5c11\u4e91";                 // few clouds
            case 3: return "\u9634";                       // overcast
            case 45: case 48: return "\u96fe";             // fog / rime fog
            case 51: return "\u5c0f\u9635\u96e8";          // light drizzle
            case 53: return "\u5c0f\u96e8";                // drizzle
            case 55: return "\u4e2d\u96e8";                // heavy drizzle
            case 56: case 57: return "\u51bb\u96e8";       // freezing drizzle
            case 61: return "\u5c0f\u96e8";                // light rain
            case 63: return "\u4e2d\u96e8";                // moderate rain
            case 65: return "\u5927\u96e8";                // heavy rain
            case 66: case 67: return "\u51bb\u96e8";       // freezing rain
            case 71: return "\u5c0f\u96ea";                // light snow
            case 73: return "\u4e2d\u96ea";                // moderate snow
            case 75: return "\u5927\u96ea";                // heavy snow
            case 77: return "\u96ea";                      // snow grains
            case 80: return "\u5c0f\u9635\u96e8";          // light showers
            case 81: return "\u9635\u96e8";                // showers
            case 82: return "\u5f3a\u9635\u96e8";          // violent showers
            case 85: return "\u5c0f\u9635\u96ea";          // light snow showers
            case 86: return "\u9635\u96ea";                // snow showers
            case 95: return "\u96f7\u9635\u96e8";          // thunderstorm
            case 96: case 99: return "\u96f7\u9635\u96e8\u5e76\u4f34\u6709\u51b0\u96f9"; // ts + hail
            default: return "\u591a\u4e91";                // cloudy fallback
        }
    }

    // Wind direction degrees -> Russian 16-point names. They intentionally do
    // NOT match the stock Chinese dictionary keys, so the UI displays them as-is.
    private static String windDir(double deg) {
        if (deg < 0) return "\u0428\u0442\u0438\u043b\u044c"; // Штиль
        String[] names = {
            "\u0421",       // С
            "\u0421\u0421\u0412", // ССВ
            "\u0421\u0412",       // СВ
            "\u0412\u0421\u0412", // ВСВ
            "\u0412",       // В
            "\u0412\u042e\u0412", // ВЮВ
            "\u042e\u0412",       // ЮВ
            "\u042e\u042e\u0412", // ЮЮВ
            "\u042e",       // Ю
            "\u042e\u042e\u0417", // ЮЮЗ
            "\u042e\u0417",       // ЮЗ
            "\u0417\u042e\u0417", // ЗЮЗ
            "\u0417",       // З
            "\u0417\u0421\u0417", // ЗСЗ
            "\u0421\u0417",       // СЗ
            "\u0421\u0421\u0417"  // ССЗ
        };
        int idx = (int) Math.floor(((deg % 360.0) + 360.0) % 360.0 / 22.5 + 0.5) % 16;
        return names[idx];
    }

    // Wind speed m/s -> numeric string; the stock template wind_level appends
    // the unit (" м/с" after our resource patch), so return just the number.
    private static String windForce(double ms) {
        return Long.toString(Math.round(ms));
    }

    private static String num(double d) {
        long r = Math.round(d);
        if (Math.abs(d - r) < 0.05) return Long.toString(r);
        return String.format(java.util.Locale.US, "%.1f", d);
    }

    // Stock UI parses temperature strings with Integer.parseInt — always integer.
    private static String numi(double d) {
        return Long.toString(Math.round(d));
    }

    private static String httpGet(String url) throws Exception {
        return httpGet(url, "okhttp/4.9.0");
    }

    private static String httpGet(String url, String ua) throws Exception {
        // Head-unit DNS is flaky: it intermittently fails to resolve with
        // UnknownHostException/EAI_NODATA even when the network is up. Retry a
        // couple of times with backoff so a transient resolver miss doesn't
        // blank UI. Timeouts are kept short so an unreachable host fails fast
        // instead of blowing past the stock UI's refresh window.
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(400L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            try {
                return httpGetOnce(url, ua);
            } catch (java.io.IOException e) {
                last = e;
                // http<->https fallback: head unit may block plain HTTP or lack CA
                String alt = url.startsWith("http:") ? "https:" + url.substring(5)
                         : url.startsWith("https:") ? "http:" + url.substring(6) : null;
                if (alt != null) {
                    try { return httpGetOnce(alt, ua); }
                    catch (java.io.IOException e2) { last = e; }
                }
            }
        }
        throw last;
    }

    private static String httpGetOnce(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", ua);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code + " " + url);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
    // Gismeteo XML helpers ---------------------------------------------------
    private static String xmlAttr(String s, int from, String name) {
        String key = name + "=\"";
        int i = s.indexOf(key, from);
        if (i < 0) return "";
        int a = i + key.length(), b = s.indexOf('"', a);
        return b < 0 ? "" : s.substring(a, b);
    }

    private static double xmlNum(String s, int from, String name, double dflt) {
        String v = xmlAttr(s, from, name);
        try { return v.isEmpty() ? dflt : Double.parseDouble(v); } catch (Exception e) { return dflt; }
    }

    // Gismeteo 8-point compass (1=N .. 7=C, 8=Calm) -> degrees.
    // Returns -1 for calm/unknown so windDir can render "Штиль".
    private static double wdToDeg(String wd) {
        try {
            int n = Integer.parseInt(wd.trim());
            if (n >= 1 && n <= 7) return (n - 1) * 45.0;
            if (n == 8) return -1; // calm
        } catch (Exception ignored) { }
        return -1;
    }

    // Gismeteo icon code -> WMO code. Codes (observed + gismeteo wiki):
    //   d.clear, n.clear / d.smallcloud / d.cloud / d.overcast
    //   "<cloud>.rN[.st]" rain, "<cloud>.sN[.st]" snow, "<cloud>.rsN" rain+雪
    //   "cl" rain (no cloud), "sl" snow, "sn.cloud" snow w/ clouds,
    //   "thunder" / "<x>t" thunder, "mist", "haze"
    private static long gsymToWmo(String ic) {
        if (ic == null || ic.isEmpty()) return 3;
        if (ic.endsWith(".st")) ic = ic.substring(0, ic.length() - 3);
        if (ic.contains("mist") || ic.contains("haze")) return 45;
        if (ic.contains("thunder")) return 95;
        if (ic.contains("sn.cloud")) return 3;
        String core = ic;
        int dot = ic.indexOf('.');
        if (dot >= 0) core = ic.substring(dot + 1); // drop day/night letter
        if ("clear".equals(core)) return 0;
        if ("smallcloud".equals(core)) return 1;
        if ("cloud".equals(core)) return 2;
        if ("overcast".equals(core)) return 3;
        if (core.startsWith("sn.")) return 73;
        // strip leading thunder "t" (e.g. d.tc4.r1 / d.tcloud)
        if (core.startsWith("t") && core.length() > 1 && !core.startsWith("thunder")) {
            return 95;
        }
        if (core.startsWith("cl")) return 61; // rain no cloud
        if (core.startsWith("sl")) return 73; // snow no cloud
        // cloudy with precip: cN + ph (r/s/rs)
        boolean rain = core.contains("r"), snow = core.contains("s");
        if (rain && snow) return 66;
        if (snow) return 73;
        if (rain) return 61;
        return 3;
    }

    private static String gdayIso(String xml, int from) {
        String d = xmlAttr(xml, from, "date");
        return d.length() == 10 ? d : "";
    }

    private static java.util.Calendar calFromLocal(String iso, int tzMin) throws Exception {
        // "2026-11-02T04:00:00" server-local (tzone minutes east of UTC) -> Calendar UTC
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1,
              Integer.parseInt(iso.substring(8, 10)), Integer.parseInt(iso.substring(11, 13)),
              iso.length() >= 16 ? Integer.parseInt(iso.substring(14, 16)) : 0, 0);
        c.add(java.util.Calendar.MINUTE, -tzMin);
        return c;
    }

    private static String toLocalIso(java.util.Calendar utc, int tzMin, boolean withMin) {
        java.util.Calendar l = (java.util.Calendar) utc.clone();
        l.add(java.util.Calendar.MINUTE, tzMin);
        int Y = l.get(java.util.Calendar.YEAR), M = l.get(java.util.Calendar.MONTH) + 1,
            D = l.get(java.util.Calendar.DAY_OF_MONTH), h = l.get(java.util.Calendar.HOUR_OF_DAY),
            m = l.get(java.util.Calendar.MINUTE);
        return String.format(java.util.Locale.US, withMin ? "%04d-%02d-%02dT%02d:%02d" : "%04d-%02d-%02d", Y, M, D, h, m);
    }

    // Gismeteo: resolve city by coordinates, then fetch forecast XML and
    // convert into the Open-Meteo shape. Returns null on failure.
    private static JSONObject gforecast(double latD, double lngD, int[] cityOut, String[] nameOut) {
        try {
            // Key on ~1km-rounded coords so small GPS jitter reuses the cache
            // but a real move to another city invalidates it.
            String key = String.format(java.util.Locale.US, "%.2f,%.2f", latD, lngD);
            String cityId = gmCityId;
            String cityName = gmCityName;
            boolean fresh = key.equals(gmCityKey) && cityId != null
                && (System.currentTimeMillis() - gmCityAt) < GM_CITY_TTL_MS;
            if (!fresh) {
                String cl = httpGet(String.format(java.util.Locale.US, GM_CITY_URL, num(latD), num(lngD)), GM_UA);
                int it = cl.indexOf("<item ");
                if (it < 0) return null;
                cityId = xmlAttr(cl, it, "id");
                cityName = xmlAttr(cl, it, "n");
                if (cityId.isEmpty()) return null;
                gmCityKey = key; gmCityId = cityId; gmCityName = cityName;
                gmCityAt = System.currentTimeMillis();
            }
            if (nameOut != null && nameOut.length > 0) nameOut[0] = cityName;
            if (cityOut != null && cityOut.length > 0) cityOut[0] = Integer.parseInt(cityId);
            String xml = httpGet(String.format(java.util.Locale.US, GM_FC_URL, cityId), GM_UA);
            return convertGismeteo(xml);
        } catch (Exception e) { Diags.log("gmfail " + e); return null; }
    }

    private static JSONObject convertGismeteo(String xml) throws Exception {
        int tz = (int) xmlNum(xml, 0, "tzone", 0); // minutes east of UTC
        int fact = xml.indexOf("<fact ");
        int d0 = xml.indexOf("<day ");
        if (d0 < 0) return null;

        // hourly: every <forecast> inside each <day> (3-hour steps)
        JSONArray hTime = new JSONArray(), hTemp = new JSONArray(), hCode = new JSONArray(),
                  hWd = new JSONArray(), hWs = new JSONArray(), hHum = new JSONArray();
        // daily aggregation from <day> elements
        JSONArray dTime = new JSONArray(), dCode = new JSONArray(), dMin = new JSONArray(),
                  dMax = new JSONArray(), dSunrise = new JSONArray(), dSunset = new JSONArray();
        java.util.regex.Pattern PF = java.util.regex.Pattern.compile("<forecast valid=\"([^\"]+)\"[^>]*>\\s*<values ([^>]*?)[>]");
        java.util.regex.Matcher M = PF.matcher(xml);
        while (M.find()) {
            String valid = M.group(1), vals = M.group(2);
            try {
                java.util.Calendar utc = calFromLocal(valid, tz);
                hTime.put(toLocalIso(utc, tz, true));
                hTemp.put((int) Math.round(xmlNum(vals, 0, "t", 0)));
                long code = gsymToWmo(xmlAttr(vals, 0, "icon"));
                hCode.put(code);
                hWd.put(wdToDeg(xmlAttr(vals, 0, "wd")));
                hWs.put(xmlNum(vals, 0, "ws", 0));
                hHum.put((int) Math.round(xmlNum(vals, 0, "hum", 50)));
            } catch (Exception ignored) { }
        }
        int di = d0;
        while (di >= 0) {
            int de = xml.indexOf("<day ", di + 5);
            String block = xml.substring(di, de < 0 ? xml.length() : de);
            String date = gdayIso(block, 0);
            if (date.length() == 10) {
                dTime.put(date);
                dMin.put((int) Math.round(xmlNum(block, 0, "tmin", 0)));
                dMax.put((int) Math.round(xmlNum(block, 0, "tmax", 0)));
                dCode.put(gsymToWmo(xmlAttr(block, 0, "icon")));
                try {
                    long sr = (long) xmlNum(block, 0, "sunrise", 0) * 1000L;
                    long ss = (long) xmlNum(block, 0, "sunset", 0) * 1000L;
                    java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                    c.setTimeInMillis(sr); dSunrise.put(toLocalIso(c, tz, true));
                    c.setTimeInMillis(ss); dSunset.put(toLocalIso(c, tz, true));
                } catch (Exception ex) { dSunrise.put(""); dSunset.put(""); }
            }
            di = de;
        }
        if (dTime.length() == 0) return null;

        JSONObject current = new JSONObject();
        // Gismeteo's <fact> block may contain bogus values (observed t="1793...":
        // a timestamp leaking into the temperature attr for requests without a
        // signed X-Gismeteo-Timestamp). The <forecast> entries are always real,
        // so derive "current" from the nearest upcoming hourly sample.
        int hNow = -1;
        try {
            java.util.Calendar nowC = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            String nowUtc = String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:00",
                nowC.get(java.util.Calendar.YEAR), nowC.get(java.util.Calendar.MONTH) + 1,
                nowC.get(java.util.Calendar.DAY_OF_MONTH), nowC.get(java.util.Calendar.HOUR_OF_DAY));
            // hTime entries are local-time ISO; compare on the same clock
            java.util.Calendar lt = (java.util.Calendar) nowC.clone();
            lt.add(java.util.Calendar.MINUTE, tz);
            String nowLoc = String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:00",
                lt.get(java.util.Calendar.YEAR), lt.get(java.util.Calendar.MONTH) + 1,
                lt.get(java.util.Calendar.DAY_OF_MONTH), lt.get(java.util.Calendar.HOUR_OF_DAY));
            for (int i = 0; i < hTime.length(); i++) {
                if (hTime.getString(i).compareTo(nowLoc) >= 0) { hNow = i; break; }
            }
            if (hNow < 0) hNow = 0;
        } catch (Exception ignored) { }
        if (hTime.length() > 0 && hNow >= 0) {
            try { Diags.log("cur hNow=" + hNow + " t=" + hTemp.getInt(hNow) + " time=" + hTime.getString(hNow)); } catch (Exception ignored) { }
            current.put("time", hTime.getString(hNow));
            current.put("temperature_2m", hTemp.getInt(hNow));
            current.put("relative_humidity_2m", hHum.getInt(hNow));
            current.put("weather_code", hCode.getLong(hNow));
            current.put("wind_direction_10m", hWd.getDouble(hNow));
            current.put("wind_speed_10m", hWs.getDouble(hNow));
        } else if (fact >= 0) {
            String fv = xmlAttr(xml, fact, "valid");
            try {
                java.util.Calendar now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                current.put("time", toLocalIso(now, tz, true));
            } catch (Exception ex) { current.put("time", fv); }
            double ft = xmlNum(xml, fact, "t", 0);
            if (ft < -90 || ft > 60) ft = 0; // sanitize bogus timestamps
            current.put("temperature_2m", ft);
            current.put("relative_humidity_2m", (int) Math.round(xmlNum(xml, fact, "hum", 0)));
            current.put("weather_code", gsymToWmo(xmlAttr(xml, fact, "icon")));
            current.put("wind_direction_10m", wdToDeg(xmlAttr(xml, fact, "wd")));
            current.put("wind_speed_10m", xmlNum(xml, fact, "ws", 0));
        } else {
            current.put("time", hTime.getString(0));
            current.put("temperature_2m", hTemp.getInt(0));
            current.put("relative_humidity_2m", 50);
            current.put("weather_code", hCode.getLong(0));
            current.put("wind_direction_10m", hWd.getDouble(0));
            current.put("wind_speed_10m", hWs.getDouble(0));
        }

        JSONObject hourly = new JSONObject();
        hourly.put("time", hTime);
        hourly.put("temperature_2m", hTemp);
        hourly.put("weather_code", hCode);
        hourly.put("wind_direction_10m", hWd);
        hourly.put("wind_speed_10m", hWs);

        JSONObject daily = new JSONObject();
        daily.put("time", dTime);
        daily.put("weather_code", dCode);
        daily.put("temperature_2m_min", dMin);
        daily.put("temperature_2m_max", dMax);
        daily.put("sunrise", dSunrise);
        daily.put("sunset", dSunset);

        JSONObject om = new JSONObject();
        om.put("current", current);
        om.put("hourly", hourly);
        om.put("daily", daily);
        return om;
    }

    private static double[] normalize(VehicleLocation req) {
        double lat = req.getLat();
        double lng = req.getLng() != 0 ? req.getLng() : req.getLon();
        // gcj02 -> wgs84 correction (only matters inside China)
        String ct = req.getCoordinateType();
        if (ct != null && ct.toLowerCase(java.util.Locale.US).contains("gcj")) {
            double[] w = gcjToWgs(lat, lng);
            lat = w[0]; lng = w[1];
        }
        // No GPS fix: fall back to Novosibirsk (Eltex) so the UI still works
        if (lat == 0 && lng == 0) { lat = 55.03; lng = 82.92; }
        return new double[]{lat, lng};
    }

    private static double[] gcjToWgs(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transform(lng - 105.0, lat - 35.0);
        double dLng = transform(lat - 105.0, lng - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (6378245.0 / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat * 2 - (lat + dLat), lng * 2 - (lng + dLng)};
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transform(double x, double y) {
        double r = 20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI);
        r += 20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI);
        r += 15.0 * Math.sin(x / 12.0 * Math.PI) + 16.0 * Math.sin(x / 30.0 * Math.PI);
        r *= 2.0 / 3.0;
        r += -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * y * Math.PI) + 20.0 * Math.sin(2.0 * y * Math.PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static String isoToSql(String iso) {
        // "2026-10-27T08:12" -> "2026-10-27 08:12:00"
        if (iso == null || iso.length() < 16) return iso;
        return iso.substring(0, 10) + " " + iso.substring(11, 16) + ":00";
    }

    private static String isoToDate(String iso) {
        if (iso == null || iso.length() < 10) return iso;
        if (iso.length() >= 16) return iso.substring(0, 10) + " " + iso.substring(11, 16) + ":00";
        return iso.substring(0, 10) + " 00:00:00";
    }

    private static String dayPlus(int dayOffset) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset);
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(c.getTimeInMillis())) + " 00:00:00";
    }

    // Resolve forecast from Gismeteo (the only reachable source) with a
    // short-lived per-location cache so the stock app's simultaneous
    // detail+hours/widget bursts collapse into a single network round trip.
    // nameOut[0] receives the Gismeteo city name.
    private static JSONObject getForecast(double latD, double lngD, String[] nameOut) throws Exception {
        String key = Math.round(latD / FC_KEY_STEP) + "," + Math.round(lngD / FC_KEY_STEP);
        JSONObject cached = fcCache;
        if (cached != null && key.equals(fcKey)
                && (System.currentTimeMillis() - fcAt) < FC_TTL_MS) {
            if (nameOut != null && nameOut.length > 0) nameOut[0] = fcName;
            return cached;
        }
        String[] names = new String[1];
        JSONObject fc = gforecast(latD, lngD, null, names);
        if (fc == null) throw new Exception("gismeteo forecast unreachable");
        Diags.log("src=gismeteo");
        fcKey = key; fcCache = fc; fcName = names[0]; fcAt = System.currentTimeMillis();
        if (nameOut != null && nameOut.length > 0) nameOut[0] = names[0];
        return fc;
    }

    private static BaseNewSO<LocationWeather> loadLocation(VehicleLocation req) throws Exception {
        double[] ll = normalize(req);
        String[] gmNames = new String[1];
        JSONObject fc = getForecast(ll[0], ll[1], gmNames);

        // City name comes from the Gismeteo city lookup; no reverse-geocoding
        // network call (bigdatacloud is blocked by TSPU).
        String city = gmNames[0] != null ? gmNames[0] : "";
        if (city.isEmpty()) { city = "\u041d\u043e\u0432\u043e\u0441\u0438\u0431\u0438\u0440\u0441\u043a"; }  // Новосибирск fallback
        String area = city, province = city;

        JSONObject cur = fc.getJSONObject("current");
        JSONObject daily = fc.getJSONObject("daily");

        WeatherInfo now = new WeatherInfo(
            isoToDate(cur.getString("time")),
            isoToSql(daily.getJSONArray("sunrise").getString(0)),
            isoToSql(daily.getJSONArray("sunset").getString(0)),
            numi(daily.getJSONArray("temperature_2m_min").getDouble(0)),
            numi(daily.getJSONArray("temperature_2m_max").getDouble(0)),
            numi(cur.getDouble("temperature_2m")),
            windForce(cur.getDouble("wind_speed_10m")),
            windDir(cur.getDouble("wind_direction_10m")),
            Integer.toString(cur.getInt("relative_humidity_2m")),
            phenomena(cur.getLong("weather_code")));

        List<WeatherInfo> forecast = new ArrayList<WeatherInfo>();
        JSONArray days = daily.getJSONArray("time");
        for (int i = 0; i < days.length(); i++) {
            forecast.add(new WeatherInfo(
                isoToDate(days.getString(i) + "T00:00"),
                isoToSql(daily.getJSONArray("sunrise").getString(i)),
                isoToSql(daily.getJSONArray("sunset").getString(i)),
                numi(daily.getJSONArray("temperature_2m_min").getDouble(i)),
                numi(daily.getJSONArray("temperature_2m_max").getDouble(i)),
                numi(daily.getJSONArray("temperature_2m_max").getDouble(i)),
                "",
                "",
                "",
                phenomena(daily.getJSONArray("weather_code").getLong(i))));
        }
        // Stock UI only renders the 10-day tab when forecastWeather.size() >= 10.
        // Gismeteo returns 7 days — pad the tail with the last day's values so
        // the tab still renders.
        while (forecast.size() < 10) {
            WeatherInfo last = forecast.get(forecast.size() - 1);
            forecast.add(new WeatherInfo(
                dayPlus(forecast.size()),
                last.getSunrise(), last.getSunset(),
                last.getNightTemperature(), last.getDayTemperature(), last.getDayTemperature(),
                "", "", "", last.getPhenomena()));
        }

        AirQuality aq = new AirQuality(0, 0, 0, 0, 0, 0, 0);  // no reachable AQ API (open-meteo blocked)

        LocationWeather data = new LocationWeather(area, province, city, forecast, aq, now, false);
        BaseNewSO<LocationWeather> so = new BaseNewSO<LocationWeather>();
        so.code = Integer.valueOf(0);
        so.success = true;
        so.message = "ok";
        so.data = data;
        return so;
    }

    private static BaseNewSO<PredictWeatherData> loadHours(VehicleLocation req) throws Exception {
        double[] ll = normalize(req);
        String[] gmNames = new String[1];
        JSONObject fc = getForecast(ll[0], ll[1], gmNames);
        JSONObject hourly = fc.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");

        // find index of current hour
        String nowIso = fc.getJSONObject("current").getString("time");
        int start = 0;
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).compareTo(nowIso) >= 0) { start = i; break; }
        }

        String city = gmNames[0] != null ? gmNames[0] : "";
        if (city.isEmpty()) city = "\u041d\u043e\u0432\u043e\u0441\u0438\u0431\u0438\u0440\u0441\u043a";

        List<PredictWeather> list = new ArrayList<PredictWeather>();
        int end = Math.min(times.length(), start + 24);
        for (int i = start; i < end; i++) {
            list.add(new PredictWeather(
                numi(hourly.getJSONArray("temperature_2m").getDouble(i)),
                isoToSql(times.getString(i)),
                phenomena(hourly.getJSONArray("weather_code").getLong(i)),
                Long.toString(hourly.getJSONArray("weather_code").getLong(i)),
                windDir(hourly.getJSONArray("wind_direction_10m").getDouble(i)),
                windForce(hourly.getJSONArray("wind_speed_10m").getDouble(i))));
        }
        PredictWeatherData data = new PredictWeatherData("", city, list, isoToSql(nowIso), false);
        BaseNewSO<PredictWeatherData> so = new BaseNewSO<PredictWeatherData>();
        so.code = Integer.valueOf(0);
        so.success = true;
        so.message = "ok";
        so.data = data;
        return so;
    }

    private static BaseNewSO<WeatherAlert> loadAlert(VehicleLocation req) {
        // No free public alerts API usable without a server. Report "no alerts"
        // (success=false makes the stock code take the empty branch, same as
        // when there are no active warnings for the region).
        BaseNewSO<WeatherAlert> so = new BaseNewSO<WeatherAlert>();
        so.code = Integer.valueOf(404);
        so.success = false;
        so.message = "no alerts";
        so.data = null;
        return so;
    }

    private static <T> Observable<BaseNewSO<T>> fromFetcher(final Fetcher<T> f) {
        return Observable.create(new ObservableOnSubscribe<BaseNewSO<T>>() {
            @Override public void subscribe(ObservableEmitter<BaseNewSO<T>> e) {
                Diags.grantLocation();
                try {
                    BaseNewSO<T> so = f.fetch();
                    e.onNext(so);
                    Diags.ok("fetch");
                } catch (Throwable t) {
                    Diags.notify("fetch", t);
                    e.onError(t);
                }
                e.onComplete();
            }
        });
    }

    interface Fetcher<T> { BaseNewSO<T> fetch() throws Exception; }

    private static void logReq(VehicleLocation req, String what) {
        try {
            Diags.log("req " + what + " lat=" + req.getLat() + " lng="
                + (req.getLng() != 0 ? req.getLng() : req.getLon()));
        } catch (Throwable ignored) { }
    }

    @Override
    public Observable<BaseNewSO<LocationWeather>> getLocationWeatherDetail(final VehicleLocation req) {
        logReq(req, "detail");
        return fromFetcher(new Fetcher<LocationWeather>() {
            @Override public BaseNewSO<LocationWeather> fetch() throws Exception { return loadLocation(req); }
        });
    }

    @Override
    public Observable<BaseNewSO<PredictWeatherData>> getPredictHoursWeather(final VehicleLocation req) {
        logReq(req, "hours");
        return fromFetcher(new Fetcher<PredictWeatherData>() {
            @Override public BaseNewSO<PredictWeatherData> fetch() throws Exception { return loadHours(req); }
        });
    }

    @Override
    public Observable<BaseNewSO<WeatherAlert>> getWeatherAlert(final VehicleLocation req) {
        return fromFetcher(new Fetcher<WeatherAlert>() {
            @Override public BaseNewSO<WeatherAlert> fetch() { return loadAlert(req); }
        });
    }
}
