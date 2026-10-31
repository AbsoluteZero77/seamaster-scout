# Run after `npx cap add android`: adds the home-screen widget to the generated Android project.
import os, shutil, re
PKG = "com.matthew.seamasterscout"
A = "android/app/src/main"
dst = os.path.join(A, "java", *PKG.split("."))
os.makedirs(dst, exist_ok=True)
for f in os.listdir("widget/java"):
    shutil.copy(os.path.join("widget/java", f), dst)
for root, _, files in os.walk("widget/res"):
    for f in files:
        rel = os.path.relpath(root, "widget/res")
        out = os.path.join(A, "res", rel)
        os.makedirs(out, exist_ok=True)
        shutil.copy(os.path.join(root, f), out)
mf = os.path.join(A, "AndroidManifest.xml")
s = open(mf, encoding="utf-8").read()
if "SmpcWidgetProvider" not in s:
    recv = '''
        <receiver android:name=".SmpcWidgetProvider" android:exported="true">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/smpc_widget_info" />
        </receiver>
        <receiver android:name=".DailyReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="%s.DAILY" />
            </intent-filter>
        </receiver>
    ''' % PKG
    s = s.replace("</application>", recv + "</application>", 1)
    s = s.replace("<application", '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n    <application', 1)
    open(mf, "w", encoding="utf-8").write(s)
gr = "android/app/build.gradle"
g = open(gr, encoding="utf-8").read()
if "work-runtime" not in g:
    g = g.replace("dependencies {", 'dependencies {\n    implementation "androidx.work:work-runtime:2.9.0"', 1)
    open(gr, "w", encoding="utf-8").write(g)
print("widget installed")
