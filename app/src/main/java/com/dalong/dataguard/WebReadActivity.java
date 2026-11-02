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
    if (identity == null || !MobileWeb.usable(this, identity)) {
      if (identity != null) MobileWeb.lost(identity);
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
              MobileWeb.lost(identity);
            }
            finish();
            return;
          }
          new Thread(
                  () -> {
                    try {
                      MobileWeb.submit(identity, page);
                      MobileWeb.remember(this, identity, page.optString("telephone"));
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
