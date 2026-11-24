package com.dalong.dataguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.*;
import android.webkit.*;
import com.dalong.dataguard.core.WebSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/**
 * Reads China Mobile's web service hall in an invisible WebView of this app. The page itself makes
 * its signed request; only the decrypted figures it keeps for display are read back.
 *
 * <p>The web hall sets a 72-hour {@code uuid} cookie at login and 30-minute {@code d.sid} and
 * {@code shareToken} cookies. When both {@code d.sid} and {@code shareToken} are sent together on
 * normal page loads, the server slides neither, so both expire right after {@code uuid} ends.
 * Requesting {@code /website/bind/bindAccount/new} with only {@code d.sid} makes the server slide
 * {@code d.sid} (+30 min) and re-encrypt a fresh {@code shareToken} (+30 min); if {@code d.sid}
 * has lapsed, {@code shareToken}, {@code uuid}, or the {@code .10086.cn} Unified Auth SSO token
 * ({@code cmccssotoken}) mints a new {@code d.sid} in turn.
 */
final class MobileWeb {
  static final String ORIGIN = "https://wx.10086.cn";
  static final String HOME = "https://wx.10086.cn/website/spa/main/newHome";
  static final String BIND = "https://wx.10086.cn/website/bind/bindAccount/new";
  static final String UNI_AUTH = "https://wx.10086.cn/website/api/uniAuth";
  static final String LOGIN_ORIGIN = "https://login.10086.cn";
  static final String DEFAULT_UA =
      "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
  static final long WAIT_MS = 40000;
  static final long RENEW_INTERVAL_MS = 12 * 60_000L;
  static final long RENEW_RETRY_MS = 2 * 60_000L;
  static final long READ_RENEW_MS = 5 * 60_000L;
  static final long SSO_REFRESH_MS = 20 * 60_000L;
  static final int HTTP_TIMEOUT_MS = 6000;
  static final Object RENEW_LOCK = new Object();
  static final AtomicBoolean keepAliveRunning = new AtomicBoolean();
  static final AtomicLong sessionGen = new AtomicLong();
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
    Context app = web.getContext().getApplicationContext();
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    CookieManager.getInstance().setAcceptCookie(true);
    long[] deadline = {SystemClock.elapsedRealtime() + WAIT_MS};
    boolean[] ended = {false};
    Runnable[] poll = new Runnable[1];
    java.util.function.BiConsumer<JSONObject, String> finish =
        (page, error) -> {
          if (ended[0]) return;
          ended[0] = true;
          main.removeCallbacks(poll[0]);
          web.stopLoading();
          web.destroy();
          onReadFinished(app, page != null);
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
          if (SystemClock.elapsedRealtime() > deadline[0]) {
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
    if (shouldRenewBeforeRead(app)) {
      new Thread(
              () -> {
                try {
                  renew(app);
                } catch (Exception ignored) {
                }
                main.post(
                    () -> {
                      if (ended[0]) return;
                      deadline[0] = SystemClock.elapsedRealtime() + WAIT_MS;
                      web.loadUrl(HOME);
                      main.postDelayed(poll[0], 3000);
                    });
              },
              "mobile-web-pre-read")
          .start();
    } else {
      web.loadUrl(HOME);
      main.postDelayed(poll[0], 3000);
    }
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
    SharedPreferences p = prefs(c);
    boolean freshLogin =
        !identity.equals(p.getString("identity", ""))
            || (!telephone.isEmpty() && !telephone.equals(p.getString("telephone", "")))
            || (loginAt > 0 && loginAt != p.getLong("loginAt", 0));
    if (freshLogin) sessionGen.incrementAndGet();
    SharedPreferences.Editor ed =
        p.edit()
            .putString("identity", identity)
            .putString("telephone", telephone)
            .putBoolean("expired", false)
            .putLong("at", System.currentTimeMillis())
            .putLong("loginAt", loginAt);
    if (freshLogin) {
      ed.remove("cookie:d.sid")
          .remove("cookie:shareToken")
          .remove("cookie:uuid")
          .remove("cookie:cmccssotoken")
          .remove("cookie:c")
          .putLong("renewedAt", 0)
          .putLong("renewTryAt", 0)
          .putLong("ssoAt", 0);
    }
    ed.commit();
    syncCookiesFromWebView(c);
    keepAlive(c, true);
  }

  static void expire(Context c) {
    sessionGen.incrementAndGet();
    prefs(c).edit().putBoolean("expired", true).apply();
  }

  static boolean shouldRenewBeforeRead(Context c) {
    if (identity(c).isEmpty()) return false;
    long now = System.currentTimeMillis();
    if (now - prefs(c).getLong("renewedAt", 0) >= READ_RENEW_MS) return true;
    try {
      String wx = CookieManager.getInstance().getCookie(ORIGIN);
      return WebSession.cookie(wx, "d.sid").isEmpty()
          || WebSession.cookie(wx, "shareToken").isEmpty();
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  static void onReadFinished(Context c, boolean ok) {
    new Thread(
            () -> {
              try {
                if (ok) {
                  syncCookiesFromWebView(c);
                  if (System.currentTimeMillis() - prefs(c).getLong("renewedAt", 0)
                      >= READ_RENEW_MS) {
                    renew(c);
                  }
                }
                CookieManager.getInstance().flush();
              } catch (RuntimeException ignored) {
              }
            },
            "mobile-web-flush")
        .start();
  }

  /** Copies live WebView cookies into private preferences so a 30-minute idle gap can recover. */
  static void syncCookiesFromWebView(Context c) {
    try {
      CookieManager cm = CookieManager.getInstance();
      String wx = cm.getCookie(ORIGIN);
      String login = cm.getCookie(LOGIN_ORIGIN);
      SharedPreferences.Editor ed = prefs(c).edit();
      boolean changed = false;
      for (String name : new String[] {"d.sid", "shareToken", "uuid"}) {
        String v = WebSession.cookie(wx, name);
        if (!v.isEmpty()) {
          ed.putString("cookie:" + name, v);
          changed = true;
        }
      }
      for (String name : new String[] {"cmccssotoken", "c"}) {
        String v = WebSession.cookie(login, name);
        if (!v.isEmpty()) {
          ed.putString("cookie:" + name, v);
          changed = true;
        }
      }
      if (changed) ed.apply();
    } catch (RuntimeException ignored) {
    }
  }

  /**
   * Background keep-alive called from {@link GuardService#beat}. Keeps {@code d.sid},
   * {@code shareToken} and the Unified Auth SSO backup cookie rotated well inside their 30-minute
   * window without opening a WebView or virtual display.
   */
  static void keepAlive(Context c, boolean force) {
    Context app = c.getApplicationContext();
    if (identity(app).isEmpty()) return;
    SharedPreferences p = prefs(app);
    if (!force && p.getBoolean("expired", false)) return;
    long now = System.currentTimeMillis();
    if (!force) {
      if (now - p.getLong("renewedAt", 0) < RENEW_INTERVAL_MS) return;
      if (now - p.getLong("renewTryAt", 0) < RENEW_RETRY_MS) return;
    }
    if (!keepAliveRunning.compareAndSet(false, true)) return;
    new Thread(
            () -> {
              try {
                renew(app);
              } catch (Exception ignored) {
              } finally {
                keepAliveRunning.set(false);
              }
            },
            "mobile-web-renew")
        .start();
  }

  static final class BindProbe {
    final boolean signedIn;
    final String dsid;
    final String shareToken;
    final List<String> setCookies;

    BindProbe(boolean signedIn, String dsid, String shareToken, List<String> setCookies) {
      this.signedIn = signedIn;
      this.dsid = dsid;
      this.shareToken = shareToken;
      this.setCookies = setCookies;
    }
  }

  /**
   * Renews {@code d.sid} and {@code shareToken} (+30 min each) against the web hall and refreshes
   * the {@code .10086.cn} Unified Auth SSO cookie backup.
   */
  static boolean renew(Context c) {
    synchronized (RENEW_LOCK) {
      long gen = sessionGen.get();
      String id = identity(c);
      if (id.isEmpty()) return false;
      SharedPreferences p = prefs(c);
      long now = System.currentTimeMillis();
      p.edit().putLong("renewTryAt", now).apply();
      CookieManager cm;
      try {
        cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
      } catch (RuntimeException unavailable) {
        return false;
      }
      syncCookiesFromWebView(c);
      String wx = cm.getCookie(ORIGIN);
      String login = cm.getCookie(LOGIN_ORIGIN);
      String dsid = firstNonEmpty(WebSession.cookie(wx, "d.sid"), p.getString("cookie:d.sid", ""));
      String shareToken =
          firstNonEmpty(WebSession.cookie(wx, "shareToken"), p.getString("cookie:shareToken", ""));
      String uuid = firstNonEmpty(WebSession.cookie(wx, "uuid"), p.getString("cookie:uuid", ""));
      String ssoToken =
          firstNonEmpty(
              WebSession.cookie(login, "cmccssotoken"), p.getString("cookie:cmccssotoken", ""));
      String ssoC = firstNonEmpty(WebSession.cookie(login, "c"), p.getString("cookie:c", ""));
      if (dsid.isEmpty() && shareToken.isEmpty() && uuid.isEmpty() && ssoToken.isEmpty()) {
        return false;
      }
      String expectedPhone = p.getString("telephone", "");
      BindProbe ok = null;

      // 1. Sending d.sid without shareToken slides d.sid (+30m) and mints a fresh shareToken (+30m).
      if (!dsid.isEmpty()) {
        BindProbe r = probeBind("d.sid=" + dsid, expectedPhone);
        if (r.signedIn && !r.shareToken.isEmpty()) {
          ok =
              new BindProbe(
                  true,
                  firstNonEmpty(r.dsid, dsid),
                  r.shareToken,
                  r.setCookies);
        }
      }

      // 2. If d.sid lapsed, shareToken mints a fresh d.sid, which in turn mints a fresh shareToken.
      if (ok == null && !shareToken.isEmpty()) {
        BindProbe r1 = probeBind("shareToken=" + shareToken, expectedPhone);
        if (r1.signedIn && !r1.dsid.isEmpty()) {
          BindProbe r2 = probeBind("d.sid=" + r1.dsid, expectedPhone);
          if (r2.signedIn && !r2.shareToken.isEmpty()) {
            List<String> combined = new ArrayList<>(r1.setCookies);
            combined.addAll(r2.setCookies);
            ok =
                new BindProbe(
                    true,
                    firstNonEmpty(r2.dsid, r1.dsid),
                    r2.shareToken,
                    combined);
          }
        }
      }

      // 3. Within the initial 72 hours, uuid restores both d.sid and shareToken.
      if (ok == null && !uuid.isEmpty()) {
        BindProbe r = probeBind("uuid=" + uuid, expectedPhone);
        if (r.signedIn && !r.dsid.isEmpty() && !r.shareToken.isEmpty()) {
          ok = r;
        }
      }

      // 4. Unified Auth SSO fallback (.10086.cn cmccssotoken -> SSOCheck.action -> complaint/list).
      if (ok == null && !ssoToken.isEmpty()) {
        BindProbe r = renewViaSso(ssoToken, ssoC, expectedPhone);
        if (r.signedIn && !r.dsid.isEmpty() && !r.shareToken.isEmpty()) {
          ok = r;
        }
      }

      if (ok == null || !ok.signedIn) return false;
      if (gen != sessionGen.get() || !id.equals(identity(c))) return false;

      for (String sc : ok.setCookies) {
        cm.setCookie(ORIGIN, sc);
      }
      SharedPreferences.Editor ed =
          p.edit()
              .putString("cookie:d.sid", ok.dsid)
              .putString("cookie:shareToken", ok.shareToken)
              .putLong("renewedAt", now)
              .putLong("renewTryAt", now);
      if (!uuid.isEmpty()) ed.putString("cookie:uuid", uuid);

      // Keep the Unified Auth SSO backup ticket fresh so a >30m wx session gap can recover.
      if (ssoToken.isEmpty() || now - p.getLong("ssoAt", 0) >= SSO_REFRESH_MS) {
        refreshSsoBackup(cm, ed, ok.dsid, ok.shareToken, now, gen);
      }
      if (gen != sessionGen.get() || !id.equals(identity(c))) return false;
      ed.apply();
      try {
        cm.flush();
      } catch (RuntimeException ignored) {
      }
      return true;
    }
  }

  static BindProbe probeBind(String cookieHeader, String expectedPhone) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpsURLConnection) new URL(BIND).openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(HTTP_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_TIMEOUT_MS);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("User-Agent", DEFAULT_UA);
      conn.setRequestProperty(
          "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
      conn.setRequestProperty("Cookie", cookieHeader);
      int code = conn.getResponseCode();
      List<String> setCookies = extractSetCookies(conn.getHeaderFields());
      String body = code == 200 ? readBody(conn.getInputStream(), 65536) : "";
      if (code != 200 || !WebSession.signedInPage(body, expectedPhone)) {
        return new BindProbe(false, "", "", List.of());
      }
      String dsid = "", st = "";
      for (String sc : setCookies) {
        String d = WebSession.setCookieValue(sc, "d.sid");
        if (!d.isEmpty()) dsid = d;
        String s = WebSession.setCookieValue(sc, "shareToken");
        if (!s.isEmpty()) st = s;
      }
      return new BindProbe(true, dsid, st, setCookies);
    } catch (Exception ignored) {
      return new BindProbe(false, "", "", List.of());
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  static BindProbe renewViaSso(String ssoToken, String ssoC, String expectedPhone) {
    HttpURLConnection c1 = null;
    HttpURLConnection c2 = null;
    try {
      String checkUrl =
          LOGIN_ORIGIN
              + "/SSOCheck.action?channelID=12028&backUrl="
              + URLEncoder.encode(HOME, StandardCharsets.UTF_8);
      c1 = (HttpsURLConnection) new URL(checkUrl).openConnection();
      c1.setInstanceFollowRedirects(false);
      c1.setConnectTimeout(HTTP_TIMEOUT_MS);
      c1.setReadTimeout(HTTP_TIMEOUT_MS);
      c1.setRequestProperty("User-Agent", DEFAULT_UA);
      c1.setRequestProperty(
          "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      String cookie =
          "cmccssotoken=" + ssoToken + "; is_login=true" + (ssoC.isEmpty() ? "" : "; c=" + ssoC);
      c1.setRequestProperty("Cookie", cookie);
      int code1 = c1.getResponseCode();
      String loc = c1.getHeaderField("Location");
      if (code1 != 302 || !WebSession.validSsoRedirect(loc)) {
        return new BindProbe(false, "", "", List.of());
      }
      c2 = (HttpsURLConnection) new URL(loc).openConnection();
      c2.setInstanceFollowRedirects(false);
      c2.setConnectTimeout(HTTP_TIMEOUT_MS);
      c2.setReadTimeout(HTTP_TIMEOUT_MS);
      c2.setRequestProperty("User-Agent", DEFAULT_UA);
      c2.setRequestProperty(
          "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      c2.getResponseCode();
      List<String> setCookies = extractSetCookies(c2.getHeaderFields());
      String dsid = "", st = "";
      for (String sc : setCookies) {
        String d = WebSession.setCookieValue(sc, "d.sid");
        if (!d.isEmpty()) dsid = d;
        String s = WebSession.setCookieValue(sc, "shareToken");
        if (!s.isEmpty()) st = s;
      }
      if (dsid.isEmpty() || st.isEmpty()) return new BindProbe(false, "", "", List.of());
      BindProbe verify = probeBind("d.sid=" + dsid, expectedPhone);
      if (!verify.signedIn) return new BindProbe(false, "", "", List.of());
      List<String> all = new ArrayList<>(setCookies);
      all.addAll(verify.setCookies);
      return new BindProbe(
          true,
          firstNonEmpty(verify.dsid, dsid),
          firstNonEmpty(verify.shareToken, st),
          all);
    } catch (Exception ignored) {
      return new BindProbe(false, "", "", List.of());
    } finally {
      if (c1 != null) c1.disconnect();
      if (c2 != null) c2.disconnect();
    }
  }

  static void refreshSsoBackup(
      CookieManager cm,
      SharedPreferences.Editor ed,
      String dsid,
      String shareToken,
      long now,
      long gen) {
    if (dsid.isEmpty() || shareToken.isEmpty()) return;
    HttpURLConnection c1 = null;
    HttpURLConnection c2 = null;
    try {
      c1 = (HttpsURLConnection) new URL(UNI_AUTH).openConnection();
      c1.setInstanceFollowRedirects(false);
      c1.setConnectTimeout(HTTP_TIMEOUT_MS);
      c1.setReadTimeout(HTTP_TIMEOUT_MS);
      c1.setRequestMethod("POST");
      c1.setDoOutput(true);
      c1.setFixedLengthStreamingMode(0);
      c1.setRequestProperty("User-Agent", DEFAULT_UA);
      c1.setRequestProperty("Origin", ORIGIN);
      c1.setRequestProperty("Referer", BIND);
      c1.setRequestProperty("reqTmp", "2025080702");
      c1.setRequestProperty("X-Requested-With", "XMLHttpRequest");
      c1.setRequestProperty("Cookie", "d.sid=" + dsid + "; shareToken=" + shareToken);
      if (c1.getResponseCode() != 200) return;
      JSONObject uni = new JSONObject(readBody(c1.getInputStream(), 8192));
      String artifact = uni.optString("artifact");
      String channelId = uni.optString("channelID", "12028");
      String txId = uni.optString("transactionID");
      if (artifact.isEmpty() || txId.isEmpty()) return;
      String addUid =
          LOGIN_ORIGIN
              + "/AddUID.action?channelID="
              + URLEncoder.encode(channelId, StandardCharsets.UTF_8)
              + "&Artifact="
              + URLEncoder.encode(artifact, StandardCharsets.UTF_8)
              + "&backUrl="
              + URLEncoder.encode(HOME, StandardCharsets.UTF_8)
              + "&TransactionID="
              + URLEncoder.encode(txId, StandardCharsets.UTF_8);
      c2 = (HttpsURLConnection) new URL(addUid).openConnection();
      c2.setInstanceFollowRedirects(false);
      c2.setConnectTimeout(HTTP_TIMEOUT_MS);
      c2.setReadTimeout(HTTP_TIMEOUT_MS);
      c2.setRequestProperty("User-Agent", DEFAULT_UA);
      c2.setRequestProperty(
          "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      c2.getResponseCode();
      if (gen != sessionGen.get()) return;
      List<String> setCookies = extractSetCookies(c2.getHeaderFields());
      String token = "", cVal = "";
      for (String sc : setCookies) {
        cm.setCookie(LOGIN_ORIGIN, sc);
        String t = WebSession.setCookieValue(sc, "cmccssotoken");
        if (!t.isEmpty()) token = t;
        String cv = WebSession.setCookieValue(sc, "c");
        if (!cv.isEmpty()) cVal = cv;
      }
      if (!token.isEmpty()) {
        ed.putString("cookie:cmccssotoken", token).putLong("ssoAt", now);
        if (!cVal.isEmpty()) ed.putString("cookie:c", cVal);
      }
    } catch (Exception ignored) {
    } finally {
      if (c1 != null) c1.disconnect();
      if (c2 != null) c2.disconnect();
    }
  }

  static List<String> extractSetCookies(Map<String, List<String>> headers) {
    List<String> out = new ArrayList<>();
    if (headers == null) return out;
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
        for (String v : e.getValue()) {
          if (v != null && !v.isEmpty()) out.add(v);
        }
      }
    }
    return out;
  }

  static String readBody(InputStream in, int maxBytes) throws Exception {
    if (in == null) return "";
    try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) {
        int take = Math.min(n, maxBytes - out.size());
        if (take > 0) out.write(buf, 0, take);
        if (out.size() >= maxBytes) break;
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  static String firstNonEmpty(String a, String b) {
    return a != null && !a.isEmpty() ? a : (b != null ? b : "");
  }

  /**
   * Drops every cookie, cache and saved login this app keeps for the web hall. Cookie removal is
   * asynchronous; flushing first would write the old cookies back.
   */
  static void clearAll(Context context) {
    sessionGen.incrementAndGet();
    String previous = identity(context);
    prefs(context).edit().clear().commit();
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
