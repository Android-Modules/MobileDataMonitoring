package com.dalong.dataguard;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class GuardService extends Service {
  final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  String lastNotification;
  String lastPending;
  volatile boolean pauseOnWifi = true;

  final Handler main = new Handler(Looper.getMainLooper());
  final Set<String> activeWarnings = ConcurrentHashMap.newKeySet();

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("guard", "流量保护", NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(
        new NotificationChannel("directed", "定向流量用尽提醒", NotificationManager.IMPORTANCE_HIGH));
    nm.createNotificationChannel(
        new NotificationChannel("outside", "套外流量提醒", NotificationManager.IMPORTANCE_HIGH));
    startForeground(7, notification("正在连接监控进程"));
    arm(0);
  }

  void arm(long delay) {
    if (worker.isShutdown()) return;
    worker.schedule(this::beat, delay, TimeUnit.MILLISECONDS);
  }

  /** Screen-on cellular use stays near 10 seconds; idle or screen-off backs off. */
  void beat() {
    long next = 45_000;
    boolean stop = false;
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (GuardApplication.off(this)) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        stop = true;
        return;
      }
      JSONObject s;
      try {
        s = RootClient.action("health");
      } catch (Exception unavailable) {
        RootClient.start(this);
        s = RootClient.action("health");
      }
      GuardApplication.syncLock(s);
      pauseOnWifi = s.optBoolean("pauseOnWifi", true);
      String msg = s.optString("fault");
      if (msg.isEmpty())
        msg =
            (s.optBoolean("wifiPaused") ? "Wi-Fi · 限制已暂停 · " : "")
                + (s.optInt("activeSlot") > 0 ? "卡 " + s.optInt("activeSlot") : "暂无数据卡")
                + " · "
                + (s.optBoolean("dataOn") ? "数据开启" : "数据关闭");
      updateNotification(nm, msg);
      if (s.optBoolean("wifiPaused")) nm.cancel(8);
      packageWarnings(s.optJSONArray("directedWarnings"));
      pendingPrompt(nm, s.optJSONObject("directedPending"));
      try {
        // Unsent alerts stay unmarked, so they appear once notifications are allowed.
        if (nm.areNotificationsEnabled()) {
          packageAlerts(nm, s.optJSONArray("directedAlerts"));
          outsideAlerts(nm, s.optJSONObject("outsideAlerts"));
        }
      } catch (SecurityException ignored) {
        /* Notification permission does not change guard health. */
      }
      boolean interactive =
          ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
      boolean busy = s.optBoolean("dataOn") && !s.optBoolean("wifiPaused");
      next = interactive && busy ? 10_000 : 45_000;
    } catch (Exception e) {
      updateNotification(
          getSystemService(NotificationManager.class), "监控不可用，请打开应用检查 Root 授权");
    } finally {
      if (!stop) arm(next);
    }
  }

  void packageWarnings(JSONArray warnings) throws Exception {
    var prefs = getSharedPreferences("package-alerts", 0);
    Set<String> old = prefs.getStringSet("toasted", Set.of()), next = new HashSet<>();
    long delay = 0;
    if (warnings != null)
      for (int i = 0; i < warnings.length(); i++) {
        JSONObject w = warnings.getJSONObject(i);
        String id = w.getString("id");
        next.add(id);
        if (old.contains(id)) continue;
        String text = "卡 " + w.getInt("slot") + " 定向流量已用尽，继续使用将消耗通用流量";
        for (int n = 1; n <= 3; n++) {
          String message = text + "（" + n + "/3）";
          main.postDelayed(
              () -> {
                if (activeWarnings.contains(id) && !(pauseOnWifi && WifiState.connected(this)))
                  android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT)
                      .show();
              },
              delay);
          delay += 2300;
        }
      }
    activeWarnings.retainAll(next);
    activeWarnings.addAll(next);
    if (!old.equals(next)) prefs.edit().putStringSet("toasted", next).apply();
  }

  void pendingPrompt(NotificationManager nm, JSONObject pending) {
    String id = pending == null ? "" : pending.optString("id");
    if (id.equals(lastPending)) return;
    if (pending == null) {
      nm.cancel(9);
      lastPending = id;
      return;
    }
    var prefs = getSharedPreferences("package-alerts", 0);
    if (id.equals(prefs.getString("pending", ""))) return;
    Intent open = new Intent(this, DirectedPromptActivity.class).putExtra("episode", id);
    try {
      nm.notify(
          9,
          new Notification.Builder(this, "directed")
              .setSmallIcon(R.drawable.ic_guard)
              .setContentTitle("是否继续使用通用流量？")
              .setContentText("移动数据已关闭，点击确认或保持关闭")
              .setAutoCancel(true)
              .setContentIntent(
                  PendingIntent.getActivity(
                      this,
                      9,
                      open,
                      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
              .build());
      prefs.edit().putString("pending", id).apply();
      lastPending = id;
    } catch (SecurityException ignored) {
    }
  }

  void updateNotification(NotificationManager nm, String message) {
    if (message.equals(lastNotification)) return;
    nm.notify(7, notification(message));
    lastNotification = message;
  }

  void packageAlerts(NotificationManager nm, JSONArray alerts) throws Exception {
    var prefs = getSharedPreferences("package-alerts", 0);
    Set<String> old = prefs.getStringSet("posted", Set.of()), next = new HashSet<>();
    if (alerts != null)
      for (int i = 0; i < alerts.length(); i++) {
        JSONObject alert = alerts.getJSONObject(i);
        String id = alert.getString("id");
        next.add(id);
        if (old.contains(id)) continue;
        String text =
            "卡 "
                + alert.getInt("slot")
                + " · "
                + alert.getString("name")
                + (alert.optBoolean("official") ? "已用尽" : "估算已达额度")
                + "，继续使用可能消耗通用流量";
        Intent open =
            new Intent(this, MainActivity.class)
                .putExtra("slot", alert.getInt("slot"))
                .putExtra("tab", 1);
        nm.notify(
            "directed:" + id,
            8,
            new Notification.Builder(this, "directed")
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("定向流量用尽提醒")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        id.hashCode(),
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build());
      }
    for (String id : old) if (!next.contains(id)) nm.cancel("directed:" + id, 8);
    if (!old.equals(next)) prefs.edit().putStringSet("posted", next).apply();
  }

  void outsideAlerts(NotificationManager nm, JSONObject alerts) throws Exception {
    var prefs = getSharedPreferences("package-alerts", 0);
    Set<String> old = prefs.getStringSet("outsideShown", Set.of()), next = new HashSet<>();
    if (alerts != null)
      for (Iterator<String> it = alerts.keys(); it.hasNext(); ) {
        JSONObject alert = alerts.getJSONObject(it.next());
        String id = alert.getString("id"), text = alert.getString("text");
        next.add(id);
        if (old.contains(id)) continue;
        Intent open = new Intent(this, MainActivity.class).putExtra("slot", alert.getInt("slot"));
        nm.notify(
            "outside:" + id,
            10,
            new Notification.Builder(this, "outside")
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("已产生套外流量")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        id.hashCode(),
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build());
      }
    for (String id : old) if (!next.contains(id)) nm.cancel("outside:" + id, 10);
    if (!old.equals(next)) prefs.edit().putStringSet("outsideShown", next).apply();
  }

  Notification notification(String text) {
    return new Notification.Builder(this, "guard")
        .setSmallIcon(R.drawable.ic_guard)
        .setContentTitle("流量守卫 · 用量保护")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
        .build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onDestroy() {
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
