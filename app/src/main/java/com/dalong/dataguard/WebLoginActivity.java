package com.dalong.dataguard;

import android.app.Activity;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/**
 * China Mobile's own web service hall, signed in by the user with the SMS code it sends. The
 * session stays in this app's private cookie store; nothing is typed on the user's behalf. Once
 * signed in, the page's balance is read once and checked against the chosen card.
 */
public final class WebLoginActivity extends Activity {
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  WebView web;
  TextView hint;
  String identity;
  int slot;
  /** The login form was shown, so the session read afterwards is a new one. */
  boolean submitting, done, codeLogin;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    identity = getIntent().getStringExtra("identity");
    slot = getIntent().getIntExtra("slot", 0);
    if (identity == null || identity.isEmpty()) {
      finish();
      return;
    }
    GuardUi ui = new GuardUi(this);
    getWindow().setStatusBarColor(ui.bg);
    LinearLayout root = ui.column();
    root.setBackgroundColor(ui.bg);
    root.setFitsSystemWindows(true);
    TextView title = ui.heading("卡 " + slot + " · 登录中国移动网上营业厅", 16);
    title.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(4));
    root.addView(title);
    hint = ui.text("用卡 " + slot + " 的号码和短信验证码登录；登录状态只保存在本机。", 12, ui.muted);
    hint.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8));
    root.addView(hint);
    web = new WebView(this);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    CookieManager.getInstance().setAcceptCookie(true);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView view, String url) {
            if (!MobileWeb.loginPage(url)) {
              if (url.startsWith("https://wx.10086.cn/")) poll(0);
              return;
            }
            // Visiting the login page while signed in only says so; the balance is on the home page.
            view.evaluateJavascript(
                MobileWeb.PAGE_TEXT,
                raw -> {
                  switch (com.dalong.dataguard.core.WebSession.judge(MobileWeb.jsText(raw))) {
                    case com.dalong.dataguard.core.WebSession.IN -> view.loadUrl(MobileWeb.HOME);
                    case com.dalong.dataguard.core.WebSession.OUT -> codeLogin = true;
                    default -> {}
                  }
                });
          }

          @Override
          public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            // A sign-in can move to the home page without a new page load.
            if (url != null && url.startsWith(MobileWeb.HOME)) poll(0);
          }
        });
    root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    // Signed out, the home page sends the user to the login page by itself.
    web.loadUrl(MobileWeb.HOME);
  }

  /** Signed in: the home page holds the balance once its own request returns. */
  void poll(int attempt) {
    if (attempt == 0) main.removeCallbacksAndMessages(null);
    if (done || submitting || isDestroyed() || attempt > 20) return;
    web.evaluateJavascript(
        MobileWeb.SCRIPT,
        result -> {
          try {
            Object value = new JSONTokener(result).nextValue();
            if (value instanceof String text) {
              submit(new JSONObject(text));
              return;
            }
          } catch (Exception ignored) {
          }
          main.postDelayed(() -> poll(attempt + 1), 1500);
        });
  }

  void submit(JSONObject page) {
    submitting = true;
    hint.setText("已登录，正在核对号码并更新卡 " + slot + " 的余量…");
    long login = codeLogin ? System.currentTimeMillis() : MobileWeb.loginAt(this, identity);
    io.execute(() -> {
      try {
        MobileWeb.submit(identity, page, login);
        MobileWeb.remember(this, identity, page.optString("telephone"), login);
        main.post(() -> {
          done = true;
          Toast.makeText(this, "卡 " + slot + " 已通过网上营业厅更新余量", Toast.LENGTH_LONG).show();
          finish();
        });
      } catch (Exception e) {
        main.post(() -> {
          submitting = false;
          if (isDestroyed()) return;
          String message = e.getMessage() == null ? "更新未完成" : GuardStore.cleanMessage(e.getMessage());
          hint.setText(message + "。请退出后用卡 " + slot + " 的号码重新登录。");
        });
      }
    });
  }

  @Override
  public void onBackPressed() {
    if (web != null && web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }

  @Override
  protected void onPause() {
    CookieManager.getInstance().flush();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    io.shutdownNow();
    if (web != null) web.destroy();
    super.onDestroy();
  }
}
