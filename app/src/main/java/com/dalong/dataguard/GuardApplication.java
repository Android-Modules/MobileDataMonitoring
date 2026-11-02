package com.dalong.dataguard;

import android.app.Application;
import io.github.libxposed.service.*;
import org.json.JSONObject;

public final class GuardApplication extends Application {
  static volatile XposedService framework;
  static XposedService syncedFramework;
  static String syncedSubs;
  static boolean syncedWifiPause;

  @Override
  public void onCreate() {
    super.onCreate();
    XposedServiceHelper.registerListener(
        new XposedServiceHelper.OnServiceListener() {
          @Override
          public void onServiceBind(XposedService service) {
            framework = service;
            getSharedPreferences("module", 0)
                .edit()
                .putString(
                    "framework", service.getFrameworkName() + " " + service.getFrameworkVersion())
                .apply();
            syncLock();
          }

          @Override
          public void onServiceDied(XposedService service) {
            framework = null;
          }
        });
  }

  /** The user's own off switch: while set, nothing here starts the Root worker. */
  static boolean off(android.content.Context context) {
    return context.getSharedPreferences("module", 0).getBoolean("off", false);
  }

  static void setOff(android.content.Context context, boolean off) {
    context.getSharedPreferences("module", 0).edit().putBoolean("off", off).commit();
  }

  static void syncLock() {
    if (framework != null)
      new Thread(
              () -> {
                try {
                  syncLock(RootClient.action("health"));
                } catch (Exception ignored) {
                }
              },
              "guard-lock-sync")
          .start();
  }

  static synchronized void syncLock(JSONObject status) {
    XposedService service = framework;
    if (service == null) return;
    // Before the first sample the list is empty, not yet known to be empty.
    if (status.optLong("sampleAt") == 0 || !status.has("blockedSubs")) return;
    String subs = status.optString("blockedSubs");
    boolean wifiPause = status.optBoolean("pauseOnWifi", true);
    if (service == syncedFramework && subs.equals(syncedSubs) && wifiPause == syncedWifiPause)
      return;
    try {
      if (service
          .getRemotePreferences("guard")
          .edit()
          .putString("blockedSubs", subs)
          .putBoolean("pauseOnWifi", wifiPause)
          .commit()) {
        syncedSubs = subs;
        syncedWifiPause = wifiPause;
        syncedFramework = service;
      }
    } catch (Exception ignored) {
    }
  }
}
