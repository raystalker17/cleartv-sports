package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class PrayerTimes {
    private static final String TAG = "TvHome.Bola";
    private static final long RETRY_MS = 60L * 60 * 1000;
    
    // ID Liga: EPL (4328), Serie A (4332), Ligue 1 (4334), Eredivisie (4337), Liga 1 Indo (4362), Bundesliga (4331), UCL (4480)
    private static final String[] LEAGUE_IDS = {"4328", "4332", "4334", "4337", "4362", "4331", "4480"};
    
    static String[] NAMES = {"Memuat...", "Memuat...", "Memuat...", "Memuat...", "Memuat..."};

    // "hijri" dikembalikan agar UI tidak error
    static final class Day {
        final int[] minutes;
        final String hijri;

        Day(int[] minutes, String hijri) {
            this.minutes = minutes;
            this.hijri = hijri;
        }
    }

    private PrayerTimes() {
    }

    private static File file(Context ctx, String zone) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, "bola-" + zone + ".json") : null;
    }

    /** Cek data di cache dan urutkan berdasarkan jam tanding. */
    static Day day(Context ctx, String zone, String key) {
        JSONArray matches = read(file(ctx, zone));
        if (matches == null || matches.length() == 0) return null;

        List<JSONObject> matchList = new ArrayList<>();
        try {
            for (int i = 0; i < matches.length(); i++) {
                matchList.add(matches.getJSONObject(i));
            }

            // URUTKAN PERTANDINGAN DARI JAM PALING AWAL
            Collections.sort(matchList, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    String timeA = a.optString("strTimeWIB");
                    String timeB = b.optString("strTimeWIB");
                    return timeA.compareTo(timeB);
                }
            });

            List<Integer> times = new ArrayList<>();
            NAMES = new String[5]; // Reset

            for (int i = 0; i < matchList.size() && i < 5; i++) {
                JSONObject match = matchList.get(i);
                String home = match.optString("strHomeTeam");
                String away = match.optString("strAwayTeam");
                String time = match.optString("strTimeWIB");
                
                String shortHome = home.length() > 15 ? home.substring(0, 13) + ".." : home;
                String shortAway = away.length() > 15 ? away.substring(0, 13) + ".." : away;

                NAMES[i] = shortHome + " vs " + shortAway;
                int mins = minutes(time);
                times.add(mins);
            }
            
            while (times.size() < 5) {
                times.add(-1);
                NAMES[times.size() - 1] = "-";
            }
            
            int[] m = new int[5];
            for (int i = 0; i < 5; i++) m[i] = times.get(i);
            
            return new Day(m, "Jadwal Bola");
        } catch (Exception e) {
            return null;
        }
    }

    static boolean due(Context ctx, String zone, String todayKey) {
        if (day(ctx, zone, todayKey) != null) return false;
        return System.currentTimeMillis() - prefs(ctx).getLong("tried_" + zone, 0) >= RETRY_MS;
    }

    static boolean refresh(Context ctx, String zone) {
        prefs(ctx).edit().putLong("tried_" + zone, System.currentTimeMillis()).apply();
        File out = file(ctx, zone);
        if (out == null) return false;
        
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String today = sdf.format(new Date());

            String apiUrl = "https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=" + today + "&s=Soccer";
            byte[] body = get(apiUrl, 2048 * 1024);
            
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray allEvents = root.optJSONArray("events");
            
            JSONArray filtered = new JSONArray();
            if (allEvents != null) {
                for (int i = 0; i < allEvents.length(); i++) {
                    JSONObject ev = allEvents.getJSONObject(i);
                    String idLeague = ev.optString("idLeague");
                    String home = ev.optString("strHomeTeam").toLowerCase(Locale.US);
                    String away = ev.optString("strAwayTeam").toLowerCase(Locale.US);
                    
                    boolean isTargetLeague = false;
                    for (String lid : LEAGUE_IDS) {
                        if (lid.equals(idLeague)) {
                            isTargetLeague = true;
                            break;
                        }
                    }
                    
                    boolean isTimnasIndo = home.contains("indonesia") || away.contains("indonesia");

                    if (isTargetLeague || isTimnasIndo) {
                        String utcTime = ev.optString("strTime");
                        ev.put("strTimeWIB", convertToWIB(utcTime));
                        filtered.put(ev);
                    }
                }
            }

            File tmp = new File(out.getPath() + ".new");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(filtered.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(out)) throw new IllegalStateException("Gagal simpan file");
            
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Fetch gagal: " + e.getMessage());
            return false;
        }
    }

    private static String convertToWIB(String utcTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = sdf.parse(utcTime);
            
            SimpleDateFormat sdfWib = new SimpleDateFormat("HH:mm", Locale.US);
            sdfWib.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
            return sdfWib.format(date);
        } catch (Exception e) {
            return utcTime;
        }
    }

    static String key(Calendar c) {
        return String.format(Locale.ROOT, "%02d-%02d-%04d", c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    static String dateKey(String date) {
        String[] p = date.trim().split("-");
        if (p.length != 3) return "";
        return date.trim();
    }

    static int minutes(String hms) {
        String[] p = hms.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Dikembalikan agar file UI tidak error
    static String hijri(String ymd) {
        return ""; 
    }

    static int next(int[] minutes, int now) {
        for (int i = 0; i < minutes.length; i++) if (minutes[i] > now) return i;
        return -1;
    }

    static String countdown(int minutesLeft) {
        int h = minutesLeft / 60, m = minutesLeft % 60;
        if (h == 0) return "dalam " + Math.max(1, m) + " min";
        return m == 0 ? "dalam " + h + " j" : "dalam " + h + " j " + m + " min";
    }

    static String format(int minutes, boolean is24) {
        int h = minutes / 60, m = minutes % 60;
        if (is24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d", h12, m);
    }

    private static JSONArray read(File f) {
        if (f == null || !f.isFile() || f.length() > 2048 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("Response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }
    }
