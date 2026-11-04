package com.dalong.dataguard;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import org.json.*;

/**
 * Reads China Mobile's web service hall in an invisible WebView of this app. The page itself makes
 * its signed request; only the decrypted figures it keeps for display are read back.
 */
final class MobileWeb {
  static final String HOME = "https://wx.10086.cn/website/spa/main/newHome";
  static final long WAIT_MS = 40000;
  static final String SCRIPT =
      "(function(){try{if(location.origin!=='https://wx.10086.cn')return null;"
          + "var es=document.querySelectorAll('*');for(var i=0;i<es.length;i++){var vm=es[i].__vue__;"
          + "if(!vm||!vm.$store)continue;var s=vm.$store.state,f=s.newflow;"
          + "if(!f||!f.planRemian||!f.planRemian.unit||!s.user||!s.user.telephone)return null;"
          + "return JSON.stringify({telephone:s.user.telephone,oprTime:f.oprTime||'',"
          + "flow:{planRemian:f.planRemian,otherRemian:f.otherRemian||null,outUsedInfo:f.outUsedInfo||null}});}"
          + "}catch(e){}return null;})()";
  static final Handler main = new Handler(Looper.getMainLooper());

  interface Done {
    /** Exactly one of the two is set; an error of "login" means the session has ended. */
    void finished(JSONObject page, String error);
  }

  private MobileWeb() {}

  static boolean loginPage(String url) {
    return url != null && url.startsWith("https://wx.10086.cn/website/bind/");
  }

  /** Must be called on the main thread; the view is destroyed whatever the outcome. */
  static void read(Context context, Done done) {
    read(new WebView(context.getApplicationContext()), done);
  }

  static void read(WebView web, Done done) {
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    CookieManager.getInstance().setAcceptCookie(true);
    long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
    boolean[] ended = {false};
    Runnable[] poll = new Runnable[1];
    java.util.function.BiConsumer<JSONObject, String> finish =
        (page, error) -> {
          if (ended[0]) return;
          ended[0] = true;
          main.removeCallbacks(poll[0]);
          web.stopLoading();
          web.destroy();
          done.finished(page, error);
        };
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView view, String url) {
            // A live session can pass through the bind URL. Only a page that stays there is logged out.
            if (!loginPage(url)) return;
            main.postDelayed(
                () -> {
                  if (!ended[0] && loginPage(web.getUrl())) finish.accept(null, "login");
                },
                8000);
          }
        });
    poll[0] =
        () -> {
          if (ended[0]) return;
          if (SystemClock.elapsedRealtime() > deadline) {
            finish.accept(null, loginPage(web.getUrl()) ? "login" : "timeout");
            return;
          }
          web.evaluateJavascript(
              SCRIPT,
              result -> {
                try {
                  Object value = new JSONTokener(result).nextValue();
                  if (value instanceof String text && text.length() < 64000) {
                    finish.accept(new JSONObject(text), null);
                    return;
                  }
                } catch (Exception ignored) {
                }
                main.postDelayed(poll[0], 1000);
              });
        };
    web.loadUrl(HOME);
    main.postDelayed(poll[0], 3000);
  }

  static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("mobile-web", 0);
  }

  /** The card whose number is signed in on the web service hall; empty when none. */
  static String identity(Context c) {
    return prefs(c).getString("identity", "");
  }

  static boolean usable(Context c, String identity) {
    return !identity.isEmpty()
        && identity.equals(identity(c))
        && !prefs(c).getBoolean("expired", false);
  }

  static void remember(Context c, String identity, String telephone) {
    prefs(c)
        .edit()
        .putString("identity", identity)
        .putString("telephone", telephone)
        .putBoolean("expired", false)
        .putLong("at", System.currentTimeMillis())
        .apply();
  }

  static void expire(Context c) {
    prefs(c).edit().putBoolean("expired", true).apply();
  }

  /** Tells the root worker the session has ended, so its schedule stops relying on it. */
  static void lost(String identity) {
    new Thread(
            () -> {
              try {
                RootClient.request(
                    new JSONObject().put("action", "mobileWebLost").put("identity", identity));
              } catch (Exception ignored) {
              }
            },
            "mobile-web-lost")
        .start();
  }

  /** Hands the page's figures to the root worker, which checks the number before using them. */
  static JSONObject submit(String identity, JSONObject page) throws Exception {
    return RootClient.request(
        new JSONObject()
            .put("action", "mobileWeb")
            .put("identity", identity)
            .put("telephone", page.optString("telephone"))
            .put("oprTime", page.optString("oprTime"))
            .put("flow", page.getJSONObject("flow")));
  }
}
