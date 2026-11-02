package com.dalong.dataguard;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Reads only the already logged-in official balance page, after its own SDK decrypts it. */
final class MobilePageObserver {
  static final Handler main = new Handler(Looper.getMainLooper());
  static final Set<WebView> watched = Collections.newSetFromMap(new WeakHashMap<>());
  static final Map<WebView, WeakReference<Activity>> owners = new WeakHashMap<>();
  static boolean installed;
  static final String SCRIPT =
      "(function(){try{if(location.origin!=='https://wx.10086.cn'||location.pathname!=='/cmcc-service/app-pages/main/marginQuery')return"
          + " null;var es=document.querySelectorAll('*');for(var i=0;i<es.length;i++){var"
          + " s=es[i].__vue__&&es[i].__vue__.$store&&es[i].__vue__.$store.state;if(s&&s.login&&s.login.isLogin&&s.planRemainQryInfo&&s.planRemainQryInfo.newPlanRemainQryLoadState==='loadCompleted'&&s.planRemainQryInfo.newPlanRemainQry){return"
          + " JSON.stringify({account:s.userInfo.phoneNumber,flow:s.planRemainQryInfo.newPlanRemainQry.planRemianFlowListRes});}}}catch(e){}return"
          + " null;})()";

  static boolean quota(String url) {
    try {
      URI u = URI.create(url);
      return "https".equals(u.getScheme())
          && "wx.10086.cn".equals(u.getHost())
          && "/cmcc-service/app-pages/main/marginQuery".equals(u.getPath());
    } catch (Exception ignored) {
      return false;
    }
  }

  static synchronized void install(XposedModule module, String pkg) {
    if (installed || !Platform.MOBILE_APP.equals(pkg)) return;
    try {
      for (Method method : WebView.class.getDeclaredMethods())
        if (Set.of("loadUrl", "postUrl").contains(method.getName())
            && method.getParameterCount() > 0
            && method.getParameterTypes()[0] == String.class)
          module
              .hook(method)
              .intercept(chain -> {
                Object result = chain.proceed();
                if (quota((String) chain.getArg(0))) watch((WebView) chain.getThisObject(), pkg);
                return result;
              });
      module
          .hook(Activity.class.getDeclaredMethod("onResume"))
          .intercept(chain -> {
            Object result = chain.proceed();
            Activity a = (Activity) chain.getThisObject();
            // A login screen means the page cannot be read; saying so spares a long silent wait.
            if (a.getClass().getName().endsWith(".LoginActivity"))
              new Thread(
                      () -> {
                        try {
                          RootClient.request(
                              new JSONObject().put("action", "mobileLogin").put("package", pkg));
                        } catch (Exception ignored) {
                        }
                      },
                      "official-mobile-login")
                  .start();
            for (long delay : new long[] {1000, 4000, 8000})
              main.postDelayed(() -> scan(a.getWindow().getDecorView(), pkg, a), delay);
            return result;
          });
      installed = true;
      module.log(4, "DataGuard", "Official mobile balance observer installed");
    } catch (Throwable e) {
      module.log(
          5, "DataGuard", "Mobile balance observer unavailable: " + e.getClass().getSimpleName());
    }
  }

  static void watch(WebView view, String pkg) {
    main.post(() -> {
      if (!watched.add(view)) return;
      WeakReference<WebView> ref = new WeakReference<>(view);
      main.postDelayed(
          new Runnable() {
            String last = "";
            long sentAt;
            int attempts;

            @Override
            public void run() {
              WebView v = ref.get();
              if (v == null) return;
              // A hidden read may start with the screen off: the first minute is scanned anyway.
              if (++attempts > 360
                  || !quota(v.getUrl())
                  || !v.isAttachedToWindow()
                  || (v.getWindowVisibility() != View.VISIBLE && attempts > 12)) {
                watched.remove(v);
                return;
              }
              try {
                v.evaluateJavascript(
                    SCRIPT,
                    result -> {
                      try {
                        Object decoded = new JSONTokener(result).nextValue();
                        if (!(decoded instanceof String text) || text.length() > 256000) return;
                        if (text.equals(last) && System.currentTimeMillis() - sentAt < 30000)
                          return;
                        JSONObject raw = new JSONObject(text);
                        last = text;
                        JSONObject route = route(v, pkg);
                        sentAt = System.currentTimeMillis();
                        new Thread(
                                () -> {
                                  try {
                                    RootClient.request(
                                        new JSONObject()
                                            .put("action", "mobileSnapshot")
                                            .put("package", pkg)
                                            .put("page", "mobile-margin-query")
                                            .put("snapshot", raw)
                                            .put("route", route)
                                            .put("fetchedAt", System.currentTimeMillis()));
                                  } catch (Exception e) {
                                    main.post(() -> last = "");
                                  }
                                },
                                "official-mobile-snapshot")
                            .start();
                      } catch (Exception ignored) {
                      }
                    });
              } catch (Throwable ignored) {
              }
              main.postDelayed(this, 5000);
            }
          },
          3000);
    });
  }

  static void scan(View view, String pkg, Activity a) {
    if (view instanceof WebView web) {
      owners.put(web, new WeakReference<>(a));
      if (quota(web.getUrl())) watch(web, pkg);
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i), pkg, a);
  }

  /** How the page was opened, so a later hidden read can go straight back to it. */
  static JSONObject route(WebView view, String pkg) {
    try {
      Context context = owners.containsKey(view) ? owners.get(view).get() : view.getContext();
      for (int i = 0; i < 8 && context instanceof ContextWrapper && !(context instanceof Activity); i++)
        context = ((ContextWrapper) context).getBaseContext();
      if (!(context instanceof Activity activity)) return null;
      Intent intent = activity.getIntent();
      return new JSONObject()
          .put("package", pkg)
          .put("class", activity.getClass().getName())
          .put("extras", bundle(intent.getExtras(), 0));
    } catch (Exception ignored) {
      return null;
    }
  }

  static JSONObject bundle(Bundle b, int depth) throws Exception {
    JSONObject out = new JSONObject();
    if (b == null || depth > 3) return out;
    for (String key : b.keySet()) {
      Object v = b.get(key);
      if (v instanceof Bundle nested) out.put(key, bundle(nested, depth + 1));
      else {
        if (v instanceof String s && s.length() < 16000) out.put(key, s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) out.put(key, v);
      }
    }
    return out;
  }
}
