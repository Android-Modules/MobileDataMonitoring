package com.dalong.dataguard;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/**
 * Started by the root worker on a hidden display: loads the signed-in web service hall, hands the
 * figures over and closes. A lapsed login is reported once, never retried here.
 */
public final class WebReadActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    String identity = getIntent().getStringExtra("identity");
    // A missing local flag is not a logout. The page is, and only when it shows the login form.
    if (identity == null || identity.isEmpty()) {
      finish();
      return;
    }
    WebView web = new WebView(this);
    setContentView(web);
    MobileWeb.read(
        web,
        (page, error) -> {
          if (page == null) {
            if ("login".equals(error)) {
              MobileWeb.expire(this);
              MobileWeb.lost(identity, false);
            }
            finish();
            return;
          }
          new Thread(
                  () -> {
                    try {
                      long login = MobileWeb.loginAt(this, identity);
                      MobileWeb.submit(identity, page, login);
                      MobileWeb.remember(this, identity, page.optString("telephone"), login);
                    } catch (Exception ignored) {
                      // The worker falls back to the official app or SMS on its own.
                    }
                    runOnUiThread(this::finish);
                  },
                  "mobile-web-submit")
              .start();
        });
  }
}
