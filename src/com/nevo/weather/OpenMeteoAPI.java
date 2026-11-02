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
import java.io.InputStream;
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
 * Talks to public Open-Meteo APIs directly and produces the same
 * bean objects the stock UI expects. Read-only, no account, no proxy.
 */
public final class OpenMeteoAPI implements IWeatherAPI {

    private static final String FC_URL =
        "http://api.open-meteo.com/v1/forecast"
        + "?latitude=%s&longitude=%s"
        + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m"
        + "&hourly=temperature_2m,weather_code,wind_direction_10m,wind_speed_10m"
        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
        + "&timezone=auto&forecast_days=7&wind_speed_unit=ms";

    // api.open-meteo.com (Hetzner 157.90.83.168) is unreachable from RU
    // networks; met.no is reachable and needs no key. Adapter below converts
    // its Locationforecast 2.0 JSON into the Open-Meteo shape the parsers
    // expect. met.no requires a descriptive User-Agent (rejects okhttp/).
    // Gismeteo Android inform API (extracted from Gismeteo-1113 APK,
    // ru.gismeteo: GMHttpRequest.prepareUrlString). No key needed, reachable
    // from RU/GU networks. Returns XML: fact + days + 3-hourly forecast.
    private static final String GM_UA = "Gismeteo Android v2, Changan Nevo 13";
    private static final String GM_CITY_URL =
        "https://services.gismeteo.net/inform-service/inf_android/cities/"
        + "?lat_lng=1&lat=%s&lng=%s&count=1&lang=ru&with_facts=0&real_distance=0";
    private static final String GM_FC_URL =
        "https://services.gismeteo.net/inform-service/inf_android/forecast/"
        + "?city=%s&lang=ru";
    private static volatile String gmCityCache = null;

    private static final String MET_UA =
        "ChanganNevoWeather/1.0 (head-unit widget; contact mikhail.bodrod@eltex.loc)";
    private static final String MET_URL =
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%s&lon=%s";
    private static final String SUN_URL =
        "https://api.met.no/weatherapi/sunrisetime/1.1/compute?lat=%s&lon=%s&date=%s";

    private static final String AQ_URL =
        "http://air-quality-api.open-meteo.com/v1/air-quality"
        + "?latitude=%s&longitude=%s&current=pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,ozone,us_aqi";

    private static final String GEO_URL =
        "http://api.bigdatacloud.net/data/reverse-geocode-client"
        + "?latitude=%s&longitude=%s&localityLanguage=ru";

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
        try {
            return httpGetOnce(url, ua);
        } catch (java.io.IOException e) {
            // http<->https fallback: head unit may block plain HTTP or lack CA
            String alt = url.startsWith("http:") ? "https:" + url.substring(5)
                     : url.startsWith("https:") ? "http:" + url.substring(6) : null;
            if (alt == null) throw e;
            try { return httpGetOnce(alt, ua); } catch (java.io.IOException e2) { throw e; }
        }
    }

    private static String httpGetOnce(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", ua);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code + " " + url);
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---- met.no Locationforecast 2.0 -> Open-Meteo-shaped JSON ----
    private static long metSymbolToWmo(String s) {
        if (s == null || s.isEmpty()) return 3;
        s = s.replaceAll("_(day|night|polartwilight)$", "");
        if (s.equals("clearsky")) return 0;
        if (s.equals("fair")) return 1;
        if (s.equals("partlycloudy")) return 2;
        if (s.equals("cloudy")) return 3;
        if (s.equals("fog")) return 45;
        if (s.equals("lightrain")) return 61;
        if (s.equals("rain")) return 63;
        if (s.equals("heavyrain")) return 65;
        if (s.equals("rainshowers") || s.equals("lightrainshowers")) return 80;
        if (s.equals("heavyrainshowers")) return 82;
        if (s.startsWith("freezingrain")) return 66;
        if (s.startsWith("sleet")) return 71;
        if (s.equals("lightsnow")) return 71;
        if (s.equals("snow")) return 73;
        if (s.equals("heavysnow")) return 75;
        if (s.startsWith("snowshowers")) return 85;
        if (s.startsWith("thunder")) return 95;
        return 3;
    }

    private static java.util.Calendar parseIsoUtc(String iso) throws Exception {
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTime(f.parse(iso));
        return c;
    }

    private static String fmtLoc(long millis, String pattern) {
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
        return f.format(new java.util.Date(millis));
    }

    // NOAA approximate solar algorithm; returns UTC ms of sunrise/sunset.
    private static double sunHour(double doy, double lat, double lng, boolean rise) {
        double toRad = Math.PI / 180.0, toDeg = 180.0 / Math.PI;
        double lngHour = lng / 15.0;
        double t = rise ? doy + ((6.0 - lngHour) / 24.0) : doy + ((18.0 - lngHour) / 24.0);
        double M = 0.9856 * t - 3.289;
        double L = M + 1.916 * Math.sin(M * toRad) + 0.020 * Math.sin(2 * M * toRad) + 282.634;
        L = ((L % 360.0) + 360.0) % 360.0;
        double RA = Math.atan2(0.91764 * Math.sin(L * toRad), Math.cos(L * toRad)) * toDeg;
        RA = ((RA % 360.0) + 360.0) % 360.0;
        double Lq = Math.floor(L / 90.0) * 90.0;
        double RAq = Math.floor(RA / 90.0) * 90.0;
        RA = ((RA - RAq + Lq) % 360.0 + 360.0) % 360.0 / 15.0;
        double sinDec = 0.39782 * Math.sin(L * toRad);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(90.833 * toRad) - sinDec * Math.sin(lat * toRad))
                    / (cosDec * Math.cos(lat * toRad));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double H = rise ? 360.0 - Math.acos(cosH) * toDeg : Math.acos(cosH) * toDeg;
        H /= 15.0;
        double T = H + RA - 0.06571 * t - 6.622;
        return ((T - lngHour) % 24.0 + 24.0) % 24.0;
    }

    private static long sunUtcMs(long localNoonUtcMs, double lat, double lng, boolean rise) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(localNoonUtcMs);
        double ut = sunHour(c.get(java.util.Calendar.DAY_OF_YEAR), lat, lng, rise);
        if (Double.isNaN(ut)) return localNoonUtcMs;
        long dayStart = localNoonUtcMs - (localNoonUtcMs % 86400000L);
        long v = dayStart + (long) (ut * 3600000.0);
        // keep within +-24h of the reference day
        if (v < dayStart - 43200000L) v += 86400000L;
        if (v > dayStart + 129600000L) v -= 86400000L;
        return v;
    }

    /** Fetch forecast: met.no (reachable from RU) converted to Open-Meteo shape,
     *  falling back to open-meteo.com if met.no is unavailable. */
    private static JSONObject forecast(String latS, String lngS) throws Exception {
        double latD = Double.parseDouble(latS), lngD = Double.parseDouble(lngS);
        try {
            String body = httpGet(String.format(java.util.Locale.US, MET_URL, latS, lngS), MET_UA);
            JSONObject om = convertMet(new JSONObject(body), latD, lngD);
            if (om != null) { Diags.log("src=met.no"); return om; }
        } catch (Exception e) { Diags.log("metfail " + e); }
        Diags.log("src=open-meteo");
        return new JSONObject(httpGet(String.format(java.util.Locale.US, FC_URL, latS, lngS)));
    }

    private static JSONObject convertMet(JSONObject met, double latD, double lngD) throws Exception {
        JSONArray ts = met.getJSONObject("properties").getJSONArray("timeseries");
        if (ts.length() == 0) return null;

        JSONArray hTime = new JSONArray(), hTemp = new JSONArray(), hCode = new JSONArray(),
                  hWd = new JSONArray(), hWs = new JSONArray();
        java.util.LinkedHashMap<String, double[]> dagg = new java.util.LinkedHashMap<String, double[]>();
        java.util.LinkedHashMap<String, Long> dcode = new java.util.LinkedHashMap<String, Long>();
        java.util.LinkedHashMap<String, Double> dprec = new java.util.LinkedHashMap<String, Double>();

        double curTemp = 0, curHum = 0, curWd = 0, curWs = 0;
        long curCode = 3;

        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            String tUtc = e.getString("time");
            long millis = parseIsoUtc(tUtc).getTimeInMillis();
            JSONObject data = e.getJSONObject("data");
            JSONObject inst = data.optJSONObject("instant");
            JSONObject det = inst != null ? inst.optJSONObject("details") : null;
            JSONObject n1 = data.optJSONObject("next_1_hours");
            String sym = null; double precip = 0;
            if (n1 != null) {
                sym = n1.optJSONObject("summary").optString("symbol_code");
                precip = n1.optJSONObject("details").optDouble("precipitation_amount", 0);
            } else {
                JSONObject n6 = data.optJSONObject("next_6_hours");
                if (n6 != null) {
                    sym = n6.optJSONObject("summary").optString("symbol_code");
                    precip = n6.optJSONObject("details").optDouble("precipitation_amount", 0) / 6.0;
                }
            }
            if (i == 0 && det != null) {
                curTemp = det.optDouble("air_temperature", 0);
                curHum = det.optDouble("relative_humidity", 0);
                curWd = det.optDouble("wind_from_direction", 0);
                curWs = det.optDouble("wind_speed", 0);
                curCode = metSymbolToWmo(sym);
            }
            if (det == null) continue;
            double temp = det.optDouble("air_temperature", 0);
            if (n1 != null && hTime.length() < 24 * 7) {
                hTime.put(fmtLoc(millis, "yyyy-MM-dd'T'HH:mm"));
                hTemp.put(temp);
                hCode.put(metSymbolToWmo(sym));
                hWd.put(det.optDouble("wind_from_direction", 0));
                hWs.put(det.optDouble("wind_speed", 0));
            }
            String day = fmtLoc(millis, "yyyy-MM-dd");
            double[] mm = dagg.get(day);
            if (mm == null) { mm = new double[]{temp, temp}; dagg.put(day, mm); }
            if (temp < mm[0]) mm[0] = temp;
            if (temp > mm[1]) mm[1] = temp;
            Double best = dprec.get(day);
            if (best == null || precip > best.doubleValue()) {
                dprec.put(day, precip);
                dcode.put(day, Long.valueOf(metSymbolToWmo(sym)));
            }
        }

        JSONArray dTime = new JSONArray(), dCode = new JSONArray(), dMin = new JSONArray(),
                  dMax = new JSONArray(), dSunrise = new JSONArray(), dSunset = new JSONArray();
        int days = Math.min(dagg.size(), 10);
        int idx = 0;
        for (String day : dagg.keySet()) {
            if (idx >= days) break;
            double[] mm = dagg.get(day);
            dTime.put(day);
            dMin.put(mm[0]);
            dMax.put(mm[1]);
            dCode.put(dcode.get(day).longValue());
            try {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.clear();
                String[] ymd = day.split("-");
                c.set(Integer.parseInt(ymd[0]), Integer.parseInt(ymd[1]) - 1, Integer.parseInt(ymd[2]), 12, 0, 0);
                long noon = c.getTimeInMillis();
                dSunrise.put(fmtLoc(sunUtcMs(noon, latD, lngD, true), "yyyy-MM-dd'T'HH:mm"));
                dSunset.put(fmtLoc(sunUtcMs(noon, latD, lngD, false), "yyyy-MM-dd'T'HH:mm"));
            } catch (Exception ex) {
                dSunrise.put(""); dSunset.put("");
            }
            idx++;
        }

        JSONObject current = new JSONObject();
        current.put("time", fmtLoc(System.currentTimeMillis(), "yyyy-MM-dd'T'HH:mm"));
        current.put("temperature_2m", curTemp);
        current.put("relative_humidity_2m", (int) Math.round(curHum));
        current.put("weather_code", curCode);
        current.put("wind_speed_10m", curWs);
        current.put("wind_direction_10m", curWd);

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

    // Gismeteo 8-point compass (1=N .. 8=Calm) -> degrees
    private static double wdToDeg(String wd) {
        try {
            int n = Integer.parseInt(wd.trim());
            if (n >= 1 && n <= 8) return (n - 1) * 45.0;
        } catch (Exception ignored) { }
        return 0;
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
            String cityId = gmCityCache;
            if (cityId == null) {
                String cl = httpGet(String.format(java.util.Locale.US, GM_CITY_URL, num(latD), num(lngD)), GM_UA);
                int it = cl.indexOf("<item ");
                if (it < 0) return null;
                cityId = xmlAttr(cl, it, "id");
                String nm = xmlAttr(cl, it, "n");
                if (nameOut != null && nameOut.length > 0) nameOut[0] = nm;
                if (cityId.isEmpty()) return null;
                gmCityCache = cityId;
            } else if (nameOut != null && nameOut.length > 0) {
                // cache hit: names come only with the cities lookup; refetch once
                String cl = httpGet(String.format(java.util.Locale.US, GM_CITY_URL, num(latD), num(lngD)), GM_UA);
                int it = cl.indexOf("<item ");
                if (it >= 0) nameOut[0] = xmlAttr(cl, it, "n");
            }
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
                  hWd = new JSONArray(), hWs = new JSONArray();
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
            current.put("relative_humidity_2m", 0); // no hum in hTime; refilled below
            current.put("weather_code", hCode.getLong(hNow));
            current.put("wind_direction_10m", hWd.getDouble(hNow));
            current.put("wind_speed_10m", hWs.getDouble(hNow));
            // humidity from the same <forecast> slot (parse again from xml)
            java.util.regex.Matcher MH = PF.matcher(xml);
            int idx = -1;
            while (MH.find()) {
                idx++;
                if (idx == hNow) {
                    current.put("relative_humidity_2m", (int) Math.round(xmlNum(MH.group(2), 0, "hum", 50)));
                    break;
                }
            }
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

    private static BaseNewSO<LocationWeather> loadLocation(VehicleLocation req) throws Exception {
        double[] ll = normalize(req);
        String lat = num(ll[0]), lng = num(ll[1]);
        String[] gmNames = new String[1];
        JSONObject fc = gforecast(ll[0], ll[1], null, gmNames);
        boolean fromGm = fc != null;
        if (fromGm) { Diags.log("src=gismeteo"); }
        else fc = forecast(lat, lng);

        String city = "", area = "";
        if (fromGm && gmNames[0] != null && !gmNames[0].isEmpty()) {
            city = gmNames[0];
            area = city;
        } else {
            try {
                JSONObject geo = new JSONObject(httpGet(String.format(java.util.Locale.US, GEO_URL, lat, lng)));
                city = geo.optString("city", "");
                area = geo.optString("locality", "");
                if (area.isEmpty()) area = city;
                if (city.isEmpty()) city = geo.optString("principalSubdivision", "");
            } catch (Exception ignored) { }
        }
        if (city.isEmpty()) { city = "\u041d\u043e\u0432\u043e\u0441\u0438\u0431\u0438\u0440\u0441\u043a"; }  // Новосибирск fallback
        if (area.isEmpty()) area = city;

        String province = "";
        try {
            JSONObject geo = new JSONObject(httpGet(String.format(java.util.Locale.US, GEO_URL, lat, lng)));
            province = geo.optString("principalSubdivision", "");
        } catch (Exception ignored) { }
        if (province.isEmpty()) province = city;

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
        // Stock UI only renders the 10-day tab when forecastWeather.size() >= 10;
        // met.no horizon is ~9-10 days, pad the tail with last day's values.
        while (forecast.size() < 10) {
            WeatherInfo last = forecast.get(forecast.size() - 1);
            forecast.add(new WeatherInfo(
                dayPlus(forecast.size()),
                last.getSunrise(), last.getSunset(),
                last.getNightTemperature(), last.getDayTemperature(), last.getDayTemperature(),
                "", "", "", last.getPhenomena()));
        }

        AirQuality aq = new AirQuality(0, 0, 0, 0, 0, 0, 0);
        try {
            JSONObject a = new JSONObject(httpGet(String.format(java.util.Locale.US, AQ_URL, lat, lng))).getJSONObject("current");
            aq = new AirQuality(
                a.optDouble("nitrogen_dioxide", 0),
                a.optDouble("pm10", 0),
                a.optDouble("pm2_5", 0),
                0,
                a.optDouble("ozone", 0),
                a.optDouble("carbon_monoxide", 0),
                a.optDouble("us_aqi", 0));
        } catch (Exception ignored) { }

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
        String lat = num(ll[0]), lng = num(ll[1]);
        JSONObject fc = gforecast(ll[0], ll[1], null, null);
        boolean fromGm = fc != null;
        if (fromGm) { Diags.log("hours src=gismeteo"); }
        else fc = forecast(lat, lng);
        JSONObject hourly = fc.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");

        // find index of current hour
        String nowIso = fc.getJSONObject("current").getString("time");
        int start = 0;
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).compareTo(nowIso) >= 0) { start = i; break; }
        }

        String city = "";
        try {
            city = new JSONObject(httpGet(String.format(java.util.Locale.US, GEO_URL, lat, lng))).optString("city", "");
        } catch (Exception ignored) { }
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
