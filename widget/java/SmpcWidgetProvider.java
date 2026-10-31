package com.matthew.seamasterscout;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

public class SmpcWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        WidgetCore.draw(c);
        WidgetCore.schedule(c);
    }

    @Override
    public void onEnabled(Context c) {
        WidgetCore.schedule(c);
        WidgetCore.runNow(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (WidgetCore.REFRESH.equals(i.getAction())) {
            WidgetCore.runNow(c);
        }
    }
}
