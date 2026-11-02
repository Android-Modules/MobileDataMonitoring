package com.dalong.dataguard;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import java.util.Locale;
import java.util.concurrent.*;
import org.json.JSONObject;

/** A native system dialog; confirmation is validated by the root policy owner. */
public final class DirectedPromptActivity extends Activity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  String episode;
  AlertDialog dialog;
  boolean resolving;
  android.net.ConnectivityManager.NetworkCallback networkCallback;

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (!java.util.Objects.equals(episode, intent.getStringExtra("episode"))) recreate();
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      networkCallback =
          WifiState.observe(
              this,
              () -> {
                try {
                  worker.execute(() -> {
                    try {
                      if (RootClient.action("health").optBoolean("wifiPaused"))
                        runOnUiThread(this::finish);
                    } catch (Exception ignored) {
                    }
                  });
                } catch (RejectedExecutionException ignored) {
                }
              });
    } catch (RuntimeException ignored) {
    }
    episode = getIntent().getStringExtra("episode");
    worker.execute(() -> {
      try {
        JSONObject p = request("directedPrompt");
        long remaining = p.optLong("remaining", -1);
        String balance =
            remaining < 0 ? "待核实" : String.format(Locale.CHINA, "%.2f GB", remaining / 1e9);
        runOnUiThread(() -> showPrompt(p.optString("name"), balance));
      } catch (Exception e) {
        runOnUiThread(() -> showError(e.getMessage()));
      }
    });
  }

  JSONObject request(String action) throws Exception {
    return RootClient.request(
        new JSONObject().put("action", action).put("episode", episode == null ? "" : episode));
  }

  void showPrompt(String name, String remaining) {
    if (isFinishing() || isDestroyed()) return;
    dialog =
        new AlertDialog.Builder(this)
            .setTitle("是否继续使用通用流量？")
            .setMessage(
                name
                    + "已用尽，移动数据已关闭。\n\n通用保护余量："
                    + remaining
                    + "\n继续使用该应用将消耗通用流量，可能产生套餐外费用。允许后，本卡本账期这次用尽不再重复询问；通用及用量保护仍生效。")
            .setNegativeButton("保持关闭", (d, which) -> dismissPrompt())
            .setPositiveButton("继续使用通用流量", null)
            .setOnCancelListener(d -> dismissPrompt())
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnShowListener(
        d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> confirm()));
    dialog.show();
  }

  void confirm() {
    if (resolving) return;
    resolving = true;
    dialog.setCancelable(false);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
    worker.execute(() -> {
      try {
        request("confirmDirected");
        runOnUiThread(this::finish);
      } catch (Exception e) {
        runOnUiThread(() -> {
          dialog.dismiss();
          showError(e.getMessage());
        });
      }
    });
  }

  void dismissPrompt() {
    if (resolving) return;
    resolving = true;
    worker.execute(() -> {
      try {
        request("dismissDirected");
      } catch (Exception ignored) {
      }
    });
    finish();
  }

  void showError(String message) {
    if (isFinishing() || isDestroyed()) return;
    new AlertDialog.Builder(this)
        .setTitle("暂时不能继续")
        .setMessage(GuardStore.cleanMessage(message == null ? "请返回保护页检查当前状态" : message))
        .setPositiveButton("知道了", (d, w) -> finish())
        .setOnCancelListener(d -> finish())
        .show();
  }

  @Override
  public void onDestroy() {
    if (networkCallback != null)
      try {
        getSystemService(android.net.ConnectivityManager.class)
            .unregisterNetworkCallback(networkCallback);
      } catch (RuntimeException ignored) {
      }
    worker.shutdown();
    if (dialog != null) dialog.dismiss();
    super.onDestroy();
  }
}
