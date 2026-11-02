package com.dalong.dataguard;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())||Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))if(!GuardApplication.off(c))c.startForegroundService(new Intent(c,GuardService.class));}
}
