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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class WidgetCore {
    static final String REFRESH = "com.matthew.seamasterscout.REFRESH";
    static final String DAILY = "com.matthew.seamasterscout.DAILY";

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

    static void save(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = c.getSharedPreferences("smpc", 0).edit();
            e.putLong("t", System.currentTimeMillis());
            if (o.optInt("ok") == 1) {
                e.putInt("ok", 1);
                e.putLong("p", o.optLong("p"));
                e.putString("title", o.optString("t"));
                e.putString("url", o.optString("u"));
                e.putString("shop", o.optString("s"));
                e.putInt("n", o.optInt("n"));
                e.putString("err", "");
                boolean has = false;
                String img = o.optString("img");
                if (img.length() > 0) {
                    try {
                        HttpURLConnection h = (HttpURLConnection) new URL(img).openConnection();
                        h.setConnectTimeout(10000);
                        h.setReadTimeout(15000);
                        h.setRequestProperty("User-Agent", "Mozilla/5.0");
                        Bitmap b = BitmapFactory.decodeStream(h.getInputStream());
                        if (b != null) {
                            float r = Math.min(1f, 240f / Math.max(b.getWidth(), b.getHeight()));
                            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * r)), Math.max(1, (int) (b.getHeight() * r)), true);
                            FileOutputStream fo = new FileOutputStream(new File(c.getFilesDir(), "smpc.png"));
                            s.compress(Bitmap.CompressFormat.PNG, 90, fo);
                            fo.close();
                            has = true;
                        }
                    } catch (Exception ex) {
                        has = false;
                    }
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
        p.setShader(new LinearGradient(0, 0, S, S, 0xFF06283F, 0xFF0F8AA8, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, p);
        p.setShader(null);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
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
            t.setColor(0xFF0C3A5C);
            cv.drawText(sh, b.left + 14, b.top + 31, t);
            t.setTextSize(42);
            t.setColor(Color.WHITE);
            w = t.measureText(price) + 38;
            RectF pr = new RectF(S - w - 14, S - 78, S - 14, S - 18);
            p.setShader(new LinearGradient(0, pr.top, 0, pr.bottom, 0xFF1479A8, 0xFF082F4D, Shader.TileMode.CLAMP));
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
                t.setTypeface(Typeface.DEFAULT);
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
        SharedPreferences sp = c.getSharedPreferences("smpc", 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.smpc_widget);
        Intent open;
        Bitmap bmp;
        if (sp.getInt("ok", -1) == 1) {
            Bitmap photo = null;
            File f = new File(c.getFilesDir(), "smpc.png");
            if (sp.getBoolean("img", false) && f.exists()) {
                photo = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            String price = "\u00a5" + NumberFormat.getInstance(Locale.US).format(sp.getLong("p", 0));
            bmp = card(photo, price, sp.getString("shop", ""), "", "");
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(sp.getString("url", "https://www.google.com")));
        } else if (sp.getInt("ok", -1) == 0) {
            String err = sp.getString("err", "");
            String l2 = err.length() > 0 ? err : sp.getInt("scanned", 0) + " listings, " + (sp.getInt("sites", 0) - sp.getInt("failed", 0)) + "/" + sp.getInt("sites", 0) + " sites";
            bmp = card(null, null, "", "No SMPc found", l2);
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        } else {
            bmp = card(null, null, "", "SMPc 41mm", "Tap \u21bb to check");
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
        v.setImageViewBitmap(R.id.w_card, bmp);
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
