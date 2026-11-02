package com.dalong.dataguard;

import android.app.Activity;
import android.os.*;
import android.webkit.*;
import io.github.libxposed.api.XposedModule;
import org.json.JSONObject;

/** Hands the official app's own WebView login to the root worker; nothing is kept in this app. */
final class UnicomSession {
  static final String PACKAGE = "com.sinovatech.unicom.ui";
  static final Handler main = new Handler(Looper.getMainLooper());
  static boolean installed;
  static String last = "";

  private UnicomSession() {}

  static synchronized void install(XposedModule module, String pkg) {
    if (installed || !PACKAGE.equals(pkg)) return;
    try {
      module
          .hook(Activity.class.getDeclaredMethod("onResume"))
          .intercept(chain -> {
            Object result = chain.proceed();
            Activity activity = (Activity) chain.getThisObject();
            // The app's own pages may finish setting cookies shortly after they appear.
            for (long delay : new long[] {2000, 8000}) main.postDelayed(() -> report(activity), delay);
            return result;
          });
      installed = true;
      module.log(4, "DataGuard", "Unicom session observer installed");
    } catch (Throwable e) {
      module.log(5, "DataGuard", "Unicom observer unavailable: " + e.getClass().getSimpleName());
    }
  }

  static void report(Activity activity) {
    try {
      String cookie = CookieManager.getInstance().getCookie(OfficialClient.UNICOM);
      if (cookie == null || !cookie.contains("ecs_token=") || cookie.equals(last)) return;
      last = cookie;
      String agent = WebSettings.getDefaultUserAgent(activity);
      new Thread(
              () -> {
                try {
                  RootClient.request(
                      new JSONObject()
                          .put("action", "unicomSession")
                          .put("package", PACKAGE)
                          .put("cookie", cookie)
                          .put("userAgent", agent));
                } catch (Exception ignored) {
                  last = "";
                }
              },
              "unicom-session")
          .start();
    } catch (RuntimeException ignored) {
      // A WebView that is not ready yet is simply checked again on the next resume.
    }
  }
}
