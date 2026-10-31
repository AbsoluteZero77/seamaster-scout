package com.matthew.seamasterscout;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SmpcWorker extends Worker {
    public SmpcWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static class Http {
        @JavascriptInterface
        public String get(String u) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(25000);
                h.setInstanceFollowRedirects(true);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                h.setRequestProperty("Accept-Language", "ja");
                if (h.getResponseCode() >= 400) {
                    return "";
                }
                InputStream in = h.getInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                }
                in.close();
                return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }

    static class Bridge {
        final Context c;
        final CountDownLatch done;

        Bridge(Context c, CountDownLatch done) {
            this.c = c;
            this.done = done;
        }

        @JavascriptInterface
        public void report(String json) {
            WidgetCore.save(c, json);
            done.countDown();
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context c = getApplicationContext();
        final CountDownLatch done = new CountDownLatch(1);
        final WebView[] holder = new WebView[1];
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                WebView w = new WebView(c);
                holder[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                w.addJavascriptInterface(new Http(), "NativeHttp");
                w.addJavascriptInterface(new Bridge(c, done), "Android");
                w.loadUrl("file:///android_asset/public/index.html?widget=1");
                w.onResume();
                w.resumeTimers();
            }
        });
        try {
            done.await(6, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // fall through
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (holder[0] != null) {
                    holder[0].destroy();
                }
            }
        });
        WidgetCore.schedule(c);
        WidgetCore.draw(c);
        return Result.success();
    }
}
