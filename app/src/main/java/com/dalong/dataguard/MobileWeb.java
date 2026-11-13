package com.dalong.dataguard;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import com.dalong.dataguard.core.WebSession;
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
  /** Visible text only. The login decision is {@link WebSession#judge}, not the address. */
  static final String PAGE_TEXT =
      "(function(){try{var t=document.body&&document.body.innerText||'';return t.slice(0,4000)}"
          + "catch(e){return ''}})()";
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
            // A live session is sent through this address and stays there saying it is signed in.
            if (!loginPage(url)) return;
            main.postDelayed(() -> classify(web, ended, finish, false), 8000);
          }
        });
    poll[0] =
        () -> {
          if (ended[0]) return;
          if (SystemClock.elapsedRealtime() > deadline) {
            if (loginPage(web.getUrl())) classify(web, ended, finish, true);
            else finish.accept(null, "timeout");
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

  /** "in" goes home and keeps reading; "out" is a real logout; anything else is not one. */
  static void classify(
      WebView web,
      boolean[] ended,
      java.util.function.BiConsumer<JSONObject, String> finish,
      boolean deadline) {
    if (ended[0]) return;
    if (!loginPage(web.getUrl())) {
      if (deadline) finish.accept(null, "timeout");
      return;
    }
    web.evaluateJavascript(
        PAGE_TEXT,
        raw -> {
          if (ended[0]) return;
          switch (WebSession.judge(jsText(raw))) {
            case WebSession.IN -> {
              if (deadline) finish.accept(null, "timeout");
              else web.loadUrl(HOME);
            }
            case WebSession.OUT -> finish.accept(null, "login");
            default -> {
              if (deadline) finish.accept(null, "timeout");
            }
          }
        });
  }

  static String jsText(String raw) {
    if (raw == null || "null".equals(raw)) return "";
    try {
      Object value = new JSONTokener(raw).nextValue();
      return value instanceof String text ? text : "";
    } catch (Exception ignored) {
      return "";
    }
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

  /** When the user signed in with a code; 0 for a login made before this was kept. */
  static long loginAt(Context c, String identity) {
    return identity.equals(identity(c)) ? prefs(c).getLong("loginAt", 0) : 0;
  }

  static void remember(Context c, String identity, String telephone, long loginAt) {
    prefs(c)
        .edit()
        .putString("identity", identity)
        .putString("telephone", telephone)
        .putBoolean("expired", false)
        .putLong("at", System.currentTimeMillis())
        .putLong("loginAt", loginAt)
        .apply();
  }

  static void expire(Context c) {
    prefs(c).edit().putBoolean("expired", true).apply();
  }

  /**
   * Drops every cookie, cache and saved login this app keeps for the web hall. Cookie removal is
   * asynchronous; flushing first would write the old cookies back.
   */
  static void clearAll(Context context) {
    String previous = identity(context);
    CookieManager cookies = CookieManager.getInstance();
    cookies.removeAllCookies(
        done ->
            new Thread(
                    () -> {
                      try {
                        CookieManager.getInstance().flush();
                      } catch (RuntimeException ignored) {
                      }
                    },
                    "mobile-web-clear")
                .start());
    WebStorage.getInstance().deleteAllData();
    WebView web = new WebView(context.getApplicationContext());
    web.clearCache(true);
    web.clearHistory();
    web.clearFormData();
    web.destroy();
    prefs(context).edit().clear().commit();
    if (!previous.isEmpty()) lost(previous, true);
  }

  /**
   * The app still has this card's web login, so the worker should keep trying it first. Repairs a
   * logout reported earlier that this app did not confirm.
   */
  static void keep(Context c) {
    String id = identity(c);
    if (id.isEmpty()) return;
    boolean expired = prefs(c).getBoolean("expired", false);
    new Thread(
            () -> {
              try {
                RootClient.request(
                    new JSONObject()
                        .put("action", "mobileWebKeep")
                        .put("identity", id)
                        .put("expired", expired));
              } catch (Exception ignored) {
              }
            },
            "mobile-web-keep")
        .start();
  }

  /**
   * Tells the root worker the session has ended, so its schedule stops relying on it. A login the
   * user cleared is not an expiry.
   */
  static void lost(String identity, boolean cleared) {
    new Thread(
            () -> {
              try {
                RootClient.request(
                    new JSONObject()
                        .put("action", "mobileWebLost")
                        .put("identity", identity)
                        .put("cleared", cleared));
              } catch (Exception ignored) {
              }
            },
            "mobile-web-lost")
        .start();
  }

  /** Hands the page's figures to the root worker, which checks the number before using them. */
  static JSONObject submit(String identity, JSONObject page, long loginAt) throws Exception {
    return RootClient.request(
        new JSONObject()
            .put("action", "mobileWeb")
            .put("identity", identity)
            .put("loginAt", loginAt)
            .put("telephone", page.optString("telephone"))
            .put("oprTime", page.optString("oprTime"))
            .put("flow", page.getJSONObject("flow")));
  }
}
