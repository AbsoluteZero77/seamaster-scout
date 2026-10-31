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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SmpcWorker extends Worker {
    public SmpcWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    // Fetches pages in parallel (8 at a time) and hands each result back to the page.
    static class Http {
        final WebView w;
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final Handler main = new Handler(Looper.getMainLooper());
        volatile boolean dead = false;

        Http(WebView w) {
            this.w = w;
        }

        @JavascriptInterface
        public void fetch(final String u, final int id) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final String b = load(u);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!dead) {
                                w.evaluateJavascript("window.__nh(" + id + "," + JSONObject.quote(b) + ")", null);
                            }
                        }
                    });
                }
            });
        }

        static String load(String u) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                h.setConnectTimeout(12000);
                h.setReadTimeout(20000);
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
        final Http[] http = new Http[1];
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                WebView w = new WebView(c);
                holder[0] = w;
                http[0] = new Http(w);
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                w.addJavascriptInterface(http[0], "NativeHttp");
                w.addJavascriptInterface(new Bridge(c, done), "Android");
                w.loadUrl("file:///android_asset/public/index.html?widget=1");
                w.onResume();
                w.resumeTimers();
            }
        });
        try {
            done.await(4, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // fall through
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (http[0] != null) {
                    http[0].dead = true;
                    http[0].pool.shutdownNow();
                }
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
