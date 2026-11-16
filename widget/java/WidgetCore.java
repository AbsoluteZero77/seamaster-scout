package com.matthew.seamasterscout;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class WidgetCore {
    static final String REFRESH = "com.matthew.seamasterscout.REFRESH";
    static final String DAILY = "com.matthew.seamasterscout.DAILY";
    static final String MODE = "com.matthew.seamasterscout.MODE";
    static final String[] CATS = {"all", "pre", "post", "smpc"};
    static final String[] LABELS = {"All Diver 300M", "Pre-ceramic", "Post-ceramic", "SMPc 41mm"};

    static String cat(Context c) {
        return c.getSharedPreferences("smpc", 0).getString("cat", "smpc");
    }

    static int idx(String cat) {
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i].equals(cat)) {
                return i;
            }
        }
        return 3;
    }

    static SharedPreferences sp(Context c, String cat) {
        return c.getSharedPreferences("smpc_" + cat, 0);
    }

    static void cycle(Context c) {
        String next = CATS[(idx(cat(c)) + 1) % CATS.length];
        c.getSharedPreferences("smpc", 0).edit().putString("cat", next).apply();
        draw(c);
        runNow(c);
    }

    static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    // Next 6:00 pm New Zealand time (daylight saving handled by the time zone).
    static void schedule(Context c) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Pacific/Auckland"));
        cal.set(Calendar.HOUR_OF_DAY, 18);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60000) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent in = new Intent(c, DailyReceiver.class);
        in.setAction(DAILY);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, in, flags());
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    static void runNow(Context c) {
        Constraints cons = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SmpcWorker.class).setConstraints(cons).build();
        WorkManager.getInstance(c).enqueueUniqueWork("smpc-check", ExistingWorkPolicy.REPLACE, req);
    }

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    // Downloads a photo: follows redirects (even http to https), sends a referer, shrinks big images.
    static Bitmap loadBitmap(String u, String referer) {
        String cur = u;
        for (int hop = 0; hop < 5; hop++) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(cur).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                h.setRequestProperty("User-Agent", UA);
                h.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8");
                if (referer != null && referer.length() > 0) {
                    h.setRequestProperty("Referer", referer);
                }
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = h.getHeaderField("Location");
                    if (loc == null) {
                        return null;
                    }
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) {
                    return null;
                }
                InputStream in = h.getInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    if (o.size() > 6000000) {
                        break;
                    }
                }
                in.close();
                byte[] data = o.toByteArray();
                BitmapFactory.Options op = new BitmapFactory.Options();
                op.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, op);
                int sample = 1;
                while (op.outWidth / sample > 800) {
                    sample *= 2;
                }
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static void save(Context c, String cat, String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences p = sp(c, cat);
            SharedPreferences.Editor e = p.edit();
            e.putLong("t", System.currentTimeMillis());
            if (o.optInt("ok") == 1) {
                String url = o.optString("u");
                e.putInt("ok", 1);
                e.putLong("p", o.optLong("p"));
                e.putString("title", o.optString("t"));
                e.putString("url", url);
                e.putString("shop", o.optString("s"));
                e.putInt("n", o.optInt("n"));
                e.putString("err", "");
                ArrayList<String> cands = new ArrayList<String>();
                JSONArray arr = o.optJSONArray("imgs");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        cands.add(arr.optString(i));
                    }
                } else if (o.optString("img").length() > 0) {
                    cands.add(o.optString("img"));
                }
                File f = new File(c.getFilesDir(), "smpc_" + cat + ".png");
                boolean has = false;
                for (String u : cands) {
                    if (u == null || u.length() == 0) {
                        continue;
                    }
                    Bitmap b = loadBitmap(u, url);
                    if (b == null) {
                        continue;
                    }
                    try {
                        float r = Math.min(1f, 360f / Math.max(b.getWidth(), b.getHeight()));
                        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * r)), Math.max(1, (int) (b.getHeight() * r)), true);
                        FileOutputStream fo = new FileOutputStream(f);
                        s.compress(Bitmap.CompressFormat.PNG, 90, fo);
                        fo.close();
                        has = true;
                        break;
                    } catch (Exception ex) {
                        has = false;
                    }
                }
                // Keep the photo we already have if this is still the same listing.
                if (!has && f.exists() && url.equals(p.getString("imgFor", ""))) {
                    has = true;
                }
                if (has) {
                    e.putString("imgFor", url);
                } else {
                    if (f.exists()) {
                        f.delete();
                    }
                    e.putString("imgFor", "");
                }
                e.putBoolean("img", has);
            } else {
                e.putInt("ok", 0);
                e.putString("err", o.optString("err"));
            }
            e.putInt("scanned", o.optInt("scanned"));
            e.putInt("sites", o.optInt("sites"));
            e.putInt("failed", o.optInt("failed"));
            e.putString("why", o.optString("why"));
            e.apply();
        } catch (Exception ex) {
            // keep the last good result
        }
        draw(c);
    }

    static String clip(String t, int n) {
        return t.length() > n ? t.substring(0, n - 1) + "\u2026" : t;
    }

    // One rounded 2x2 card in the same style as the app: photo, price pill, shop badge.
    static Bitmap card(Bitmap photo, String price, String shop, String l1, String l2) {
        int S = 360;
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path rr = new Path();
        rr.addRoundRect(new RectF(0, 0, S, S), 40, 40, Path.Direction.CW);
        cv.clipPath(rr);
        p.setShader(new LinearGradient(0, 0, S, S, 0xFF071F36, 0xFF1DB0C6, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, p);
        p.setShader(null);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        if (photo != null) {
            float sc = Math.max((float) S / photo.getWidth(), (float) S / photo.getHeight());
            Matrix m = new Matrix();
            m.postScale(sc, sc);
            m.postTranslate((S - photo.getWidth() * sc) / 2f, (S - photo.getHeight() * sc) / 2f);
            cv.drawBitmap(photo, m, p);
            p.setShader(new LinearGradient(0, S * 0.55f, 0, S, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
            cv.drawRect(0, S * 0.55f, S, S, p);
            p.setShader(null);
            String sh = clip(shop, 15);
            t.setTextSize(26);
            float w = t.measureText(sh) + 28;
            RectF b = new RectF(S - w - 14, 14, S - 14, 58);
            p.setColor(0xEEFFFFFF);
            cv.drawRoundRect(b, 22, 22, p);
            t.setColor(0xFF0A2A47);
            cv.drawText(sh, b.left + 14, b.top + 31, t);
            t.setTextSize(42);
            t.setColor(Color.WHITE);
            w = t.measureText(price) + 38;
            RectF pr = new RectF(S - w - 14, S - 78, S - 14, S - 18);
            p.setShader(new LinearGradient(0, pr.top, 0, pr.bottom, 0xFF2A8AC0, 0xFF0B3F64, Shader.TileMode.CLAMP));
            cv.drawRoundRect(pr, 30, 30, p);
            p.setShader(null);
            cv.drawText(price, pr.left + 19, pr.top + 43, t);
        } else {
            if (price != null) {
                t.setTextSize(50);
                t.setColor(Color.WHITE);
                cv.drawText(price, 24, 190, t);
                t.setTextSize(24);
                t.setColor(0xFFBFE3F2);
                cv.drawText(clip(shop, 22), 24, 230, t);
            } else {
                t.setTextSize(32);
                t.setColor(Color.WHITE);
                cv.drawText(l1, 24, 150, t);
                t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                t.setTextSize(21);
                t.setColor(0xFFBFE3F2);
                cv.drawText(clip(l2, 30), 24, 196, t);
            }
        }
        return out;
    }

    static void draw(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, SmpcWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        String cat = cat(c);
        SharedPreferences sp = sp(c, cat);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.smpc_widget);
        Intent open;
        Bitmap bmp;
        if (sp.getInt("ok", -1) == 1) {
            Bitmap photo = null;
            File f = new File(c.getFilesDir(), "smpc_" + cat + ".png");
            if (sp.getBoolean("img", false) && f.exists()) {
                photo = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            String price = "\u00a5" + NumberFormat.getInstance(Locale.US).format(sp.getLong("p", 0));
            bmp = card(photo, price, sp.getString("shop", ""), "", "");
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(sp.getString("url", "https://www.google.com")));
        } else if (sp.getInt("ok", -1) == 0) {
            String err = sp.getString("err", "");
            String l2 = err.length() > 0 ? err : sp.getInt("scanned", 0) + " listings, " + (sp.getInt("sites", 0) - sp.getInt("failed", 0)) + "/" + sp.getInt("sites", 0) + " sites";
            bmp = card(null, null, "", "None found", l2);
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        } else {
            bmp = card(null, null, "", LABELS[idx(cat)], "Checking\u2026 tap \u21bb to retry");
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
        v.setImageViewBitmap(R.id.w_card, bmp);
        v.setTextViewText(R.id.w_mode, LABELS[idx(cat)] + " \u25be");
        Intent md = new Intent(c, SmpcWidgetProvider.class);
        md.setAction(MODE);
        v.setOnClickPendingIntent(R.id.w_mode, PendingIntent.getBroadcast(c, 3, md, flags()));
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 1, open, flags()));
        }
        Intent rf = new Intent(c, SmpcWidgetProvider.class);
        rf.setAction(REFRESH);
        v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(c, 2, rf, flags()));
        m.updateAppWidget(ids, v);
    }
}
