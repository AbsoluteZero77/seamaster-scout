package com.matthew.seamasterscout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DailyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        WidgetCore.schedule(c);
        if (WidgetCore.DAILY.equals(i.getAction())) {
            WidgetCore.runNow(c);
        }
    }
}
