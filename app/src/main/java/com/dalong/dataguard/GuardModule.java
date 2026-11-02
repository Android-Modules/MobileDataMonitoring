package com.dalong.dataguard;

import io.github.libxposed.api.*;
import java.lang.reflect.*;
import org.json.*;

public final class GuardModule extends XposedModule {
  boolean phoneProcess, heartbeat, simGuardReady, dataGuardReady;
  volatile android.content.Context phoneContext;

  @Override
  public void onModuleLoaded(ModuleLoadedParam p) {
    phoneProcess = "com.android.phone".equals(p.getProcessName());
    log(4, "DataGuard", "API 102 / " + BuildConfig.VERSION_NAME + " loaded: " + p.getProcessName());
    try {
      hook(android.app.Application.class.getDeclaredMethod("attach", android.content.Context.class))
          .intercept(chain -> {
            Object result = chain.proceed();
            android.content.Context context = (android.content.Context) chain.getThisObject();
            if (phoneProcess) phoneContext = context;
            else installApp(context.getClassLoader(), context.getPackageName());
            return result;
          });
    } catch (Throwable e) {
      log(5, "DataGuard", "Application observer unavailable: " + e.getClass().getSimpleName());
    }
  }

  void installApp(ClassLoader loader, String pkg) {
    FlowObserver.install(this, loader, pkg);
    MobilePageObserver.install(this, pkg);
    UnicomSession.install(this, pkg);
  }

  @Override
  public void onPackageReady(PackageReadyParam p) {
    if (!p.getPackageName().equals("com.android.phone")) return;
    for (String name :
        new String[] {
          "com.android.internal.telephony.subscription.SubscriptionManagerService",
          "com.android.internal.telephony.SubscriptionController"
        })
      try {
        Class<?> c = Class.forName(name, false, p.getClassLoader());
        for (Method m : c.getDeclaredMethods())
          if (m.getName().equals("setDefaultDataSubId")
              && m.getParameterCount() > 0
              && m.getParameterTypes()[0] == int.class
              && m.getReturnType() == void.class) {
            hook(m)
                .intercept(chain -> {
                  if (blocked((Integer) chain.getArg(0))) {
                    log(5, "DataGuard", "Blocked forbidden data-sub change");
                    return null;
                  }
                  return chain.proceed();
                });
            simGuardReady = true;
            log(4, "DataGuard", "Data-SIM guard installed");
          }
      } catch (ClassNotFoundException ignored) {
      } catch (Throwable e) {
        log(5, "DataGuard", "Hook unavailable: " + e.getClass().getSimpleName());
      }
    try {
      Class<?> c =
          Class.forName("com.android.phone.PhoneInterfaceManager", false, p.getClassLoader());
      for (Method m : c.getDeclaredMethods())
        if (m.getName().equals("setDataEnabledForReason")
            && m.getParameterCount() >= 3
            && m.getParameterTypes()[0] == int.class
            && m.getParameterTypes()[2] == boolean.class
            && m.getReturnType() == void.class) {
          hook(m)
              .intercept(chain -> {
                if ((Boolean) chain.getArg(2) && blocked((Integer) chain.getArg(0))) {
                  log(5, "DataGuard", "Blocked forbidden data enable");
                  return null;
                }
                return chain.proceed();
              });
          dataGuardReady = true;
          log(4, "DataGuard", "Data-enable guard installed");
        }
    } catch (Throwable e) {
      log(5, "DataGuard", "Data-enable guard unavailable: " + e.getClass().getSimpleName());
    }
    // A heartbeat certifies both controls. A partial install must never report healthy.
    if (simGuardReady && dataGuardReady && !heartbeat) {
      heartbeat = true;
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
          .scheduleWithFixedDelay(
              () -> {
                try {
                  RootClient.request(
                      new JSONObject()
                          .put("action", "hookAlive")
                          .put("networkReady", phoneContext != null)
                          .put("wifiConnected", liveWifiConnected())
                          .put("process", "com.android.phone"));
                } catch (Exception ignored) {
                }
              },
              0,
              10,
              java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  /** Only cards the user barred are refused; no list means nothing has been barred yet. */
  boolean blocked(int target) {
    var prefs = getRemotePreferences("guard");
    if (prefs.getBoolean("pauseOnWifi", true) && liveWifiConnected()) return false;
    String list;
    try {
      // The worker is the authority; a synced copy can lag behind a card the user just allowed.
      list =
          RootClient.request(new JSONObject().put("action", "blockedSubs"), 1500)
              .getString("blockedSubs");
    } catch (Exception workerUnavailable) {
      // Before the worker is up (early boot) only cards the user barred are refused; a setting
      // left by an early build must not hold the system's own data enable.
      list = prefs.getString("blockedSubs", "");
    }
    int sub = target < 0 ? android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() : target;
    return java.util.Arrays.asList(list.split(",")).contains(String.valueOf(sub));
  }

  boolean liveWifiConnected() {
    android.content.Context context = phoneContext;
    return context != null && WifiState.connected(context);
  }
}
