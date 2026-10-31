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
import java.text.SimpleDateFormat;
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
            e.apply();
        } catch (Exception ex) {
            // keep the last good result
        }
        draw(c);
    }

    static void draw(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, SmpcWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        SharedPreferences sp = c.getSharedPreferences("smpc", 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.smpc_widget);
        SimpleDateFormat df = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
        df.setTimeZone(TimeZone.getTimeZone("Pacific/Auckland"));
        long t = sp.getLong("t", 0);
        String when = t > 0 ? "Checked " + df.format(t) + " NZ" : "Not checked yet";
        Intent open;
        if (sp.getInt("ok", -1) == 1) {
            v.setTextViewText(R.id.w_price, "\u00a5" + NumberFormat.getInstance(Locale.US).format(sp.getLong("p", 0)));
            v.setTextViewText(R.id.w_title, sp.getString("title", ""));
            v.setTextViewText(R.id.w_meta, sp.getString("shop", "") + " \u00b7 " + sp.getInt("n", 0) + " found \u00b7 " + when);
            File f = new File(c.getFilesDir(), "smpc.png");
            if (sp.getBoolean("img", false) && f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) {
                    v.setImageViewBitmap(R.id.w_img, b);
                }
            }
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(sp.getString("url", "https://www.google.com")));
        } else {
            String err = sp.getInt("ok", -1) == 0 ? "No SMPc 41mm found" : "Tap \u21bb to check now";
            v.setTextViewText(R.id.w_price, "\u2014");
            v.setTextViewText(R.id.w_title, err);
            v.setTextViewText(R.id.w_meta, when);
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
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
