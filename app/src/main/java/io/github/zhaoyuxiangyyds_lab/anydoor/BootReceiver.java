package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restart the driver after a reboot when spoofing was left on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Config.config(c).getBoolean(Keys.STARTED, false)) return;
        Intent i = new Intent(c, SpoofService.class).setAction(SpoofService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
