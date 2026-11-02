package com.metro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Current conditions for the greeting line, from Open-Meteo (free, no account or key). The city is
 * looked up once, then the weather is refreshed every half hour while Start is in use.
 */
final class Weather {
    static final long FRESH_MS = 30 * 60_000L, SHOW_MS = 3 * 3600_000L;
    private static boolean fetching;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("metro", Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("weather", false) && Net.allowed(c); }

    static boolean fahrenheit(Context c) { return prefs(c).getBoolean("weather_f", false); }

    static String city(Context c) { return prefs(c).getString("weather_city", ""); }

    /** "24° Partly cloudy", or null when there's nothing recent enough to show. */
    static String line(Context c) {
        SharedPreferences p = prefs(c);
        if (!enabled(c) || System.currentTimeMillis() - p.getLong("weather_at", 0) > SHOW_MS) return null;
        if (p.getBoolean("weather_f_at", false) != fahrenheit(c)) return null;
        return Math.round(p.getFloat("weather_temp", 0)) + "° " + describe(p.getInt("weather_code", -1));
    }

    /** What went wrong last time, for Personalize ("City not found"), or null. */
    static String problem(Context c) { return prefs(c).getString("weather_problem", null); }

    /** Forget the reading, e.g. when the city or units change, so the next refresh happens right away. */
    static void invalidate(Context c) {
        prefs(c).edit().remove("weather_at").remove("weather_problem").apply();
    }

    static void refresh(Context ctx, Runnable done) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = prefs(c);
        if (!enabled(c) || fetching || city(c).trim().isEmpty()) return;
        if (System.currentTimeMillis() - p.getLong("weather_at", 0) < FRESH_MS && p.getBoolean("weather_f_at", false) == fahrenheit(c)) return;
        fetching = true;
        final String city = city(c).trim();
        final boolean f = fahrenheit(c);
        Net.io.execute(() -> {
            String problem = null;
            try {
                if (!city.equals(p.getString("weather_geo_city", null))) {
                    JSONArray r = new JSONObject(Net.text("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + Uri.encode(city))).optJSONArray("results");
                    if (r == null || r.length() == 0) throw new IllegalArgumentException("City not found");
                    JSONObject g = r.getJSONObject(0);
                    p.edit().putString("weather_geo_city", city).putFloat("weather_lat", (float) g.getDouble("latitude"))
                            .putFloat("weather_lon", (float) g.getDouble("longitude")).putString("weather_place", g.optString("name", city)).apply();
                }
                JSONObject cur = new JSONObject(Net.text("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code"
                        + "&latitude=" + p.getFloat("weather_lat", 0) + "&longitude=" + p.getFloat("weather_lon", 0)
                        + (f ? "&temperature_unit=fahrenheit" : ""))).getJSONObject("current");
                p.edit().putFloat("weather_temp", (float) cur.getDouble("temperature_2m")).putInt("weather_code", cur.optInt("weather_code", -1))
                        .putBoolean("weather_f_at", f).putLong("weather_at", System.currentTimeMillis()).remove("weather_problem").apply();
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            } catch (Exception e) {
                problem = "Couldn't reach the weather service";
            }
            final String pr = problem;
            new Handler(Looper.getMainLooper()).post(() -> {
                fetching = false;
                if (pr != null) p.edit().putString("weather_problem", pr).apply();
                if (done != null) done.run();
            });
        });
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "Rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    private Weather() { }
}
