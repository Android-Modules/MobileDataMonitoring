package com.dalong.dataguard;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/**
 * Runs the official balance page on an invisible display: the carrier's own code does the signed
 * query, nothing is re-implemented, and the user's screen is never touched.
 */
final class HiddenPage {
  static final int FLAGS =
      DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
          | (1 << 8) // DESTROY_CONTENT_ON_REMOVAL: the page must not move to the real screen
          | (1 << 10) // TRUSTED
          | (1 << 11) // OWN_DISPLAY_GROUP
          | (1 << 12) // ALWAYS_UNLOCKED
          | (1 << 14); // OWN_FOCUS: keep the user's focus where the system honours it (HyperOS may not)
  static final long LOAD_MS = 45000;

  static final AtomicBoolean running = new AtomicBoolean();
  static final long SMS_EVERY_MS = 3600000;
  static final Map<String, Long> failureLoggedAt = new HashMap<>();
  /** When the official app last showed its login screen; a read in progress stops there. */
  static volatile long loginAt;

  private HiddenPage() {}

  static boolean queryUpdated(GuardDaemon daemon, String identity, long since) throws Exception {
    synchronized (daemon) {
      JSONObject all = daemon.store.data.optJSONObject("officialQueries");
      JSONObject query = all == null ? null : all.optJSONObject(identity);
      return query != null
          && "updated".equals(query.optString("state"))
          && query.optLong("at") >= since;
    }
  }

  interface Launch {
    void at(int display) throws Exception;
  }

  /**
   * Runs a page on a fresh hidden display until this card's balance arrives, the page gives up,
   * or the wait runs out. The device may be asleep: the whole read finishes under a wake lock.
   */
  @android.annotation.SuppressLint("WrongConstant") // The hidden flags need the root caller.
  static boolean onDisplay(
      GuardDaemon daemon,
      String identity,
      long waitMs,
      Launch launch,
      java.util.function.LongPredicate gaveUp,
      Runnable cleanUp)
      throws Exception {
    long since = System.currentTimeMillis();
    var wake =
        daemon
            .platform
            .context
            .getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dataguard:query");
    wake.setReferenceCounted(false);
    wake.acquire(waitMs + 5000);
    HandlerThread frames = new HandlerThread("hidden-page");
    frames.start();
    ImageReader reader = ImageReader.newInstance(720, 1600, PixelFormat.RGBA_8888, 2);
    // An undrained reader would stall the page's renderer after two frames.
    reader.setOnImageAvailableListener(
        r -> {
          Image image = r.acquireLatestImage();
          if (image != null) image.close();
        },
        new Handler(frames.getLooper()));
    VirtualDisplay display =
        daemon
            .platform
            .context
            .getSystemService(DisplayManager.class)
            .createVirtualDisplay("DataGuardQuery", 720, 1600, 320, reader.getSurface(), FLAGS);
    try {
      launch.at(display.getDisplay().getDisplayId());
      long deadline = SystemClock.elapsedRealtime() + waitMs;
      while (SystemClock.elapsedRealtime() < deadline) {
        if (queryUpdated(daemon, identity, since)) return true;
        if (gaveUp.test(since)) return false;
        Thread.sleep(1000);
      }
      return false;
    } finally {
      // Tasks still on a display being released make HyperOS bring Home to the real screen.
      cleanUp.run();
      display.release();
      reader.close();
      frames.quitSafely();
      wake.release();
    }
  }

  /** The official app's balance page; a login screen ends the wait at once. */
  static boolean read(GuardDaemon daemon, String identity, int user, JSONObject route)
      throws Exception {
    Set<Integer> tasks;
    boolean wasRunning = true;
    try {
      tasks = daemon.platform.appTasks(Platform.MOBILE_APP, user);
      wasRunning = daemon.platform.appRunning(Platform.MOBILE_APP, user);
    } catch (Exception unknown) {
      // Without a list nothing is removed afterwards: the user's own tasks are never guessed at.
      tasks = null;
    }
    Set<Integer> before = tasks;
    boolean keepRunning = wasRunning;
    long started = System.currentTimeMillis();
    boolean done =
        onDisplay(
            daemon,
            identity,
            LOAD_MS,
            d -> daemon.platform.openMobile(user, route, d),
            since -> loginAt >= since,
            () -> cleanUp(daemon, Platform.MOBILE_APP, user, before, keepRunning));
    if (!done && loginAt >= started) Logins.lost(daemon, identity, Logins.APP);
    return done;
  }

  /**
   * Each read opens a task of its own; it is removed afterwards, and an app that was not running
   * before is stopped again. Tasks the user already had are left alone.
   */
  static void cleanUp(
      GuardDaemon daemon, String pkg, int user, Set<Integer> before, boolean wasRunning) {
    if (before == null) return;
    try {
      for (int id : daemon.platform.appTasks(pkg, user))
        if (!before.contains(id)) daemon.platform.removeTask(id);
      if (!wasRunning) daemon.platform.forceStop(pkg, user);
    } catch (Exception keep) {
    }
  }

  /** User-started: a failed silent read falls back to the visible page, for a fresh login. */
  static void refresh(GuardDaemon daemon, String identity, int user, JSONObject route) {
    if (!running.compareAndSet(false, true)) return;
    new Thread(
            () -> {
              long started = System.currentTimeMillis();
              try {
                boolean done;
                try {
                  done = read(daemon, identity, user, route);
                } catch (Exception unsupported) {
                  // Some systems refuse hidden displays; the visible page still works there.
                  done = false;
                }
                if (!done)
                  synchronized (daemon) {
                    // Never leave a page behind for a phone that is locked in someone's pocket.
                    boolean visible = daemon.platform.interactive(), login = loginAt >= started;
                    OfficialClient.queryState(
                        daemon,
                        identity,
                        visible && !login ? "waiting" : "error",
                        login
                            ? "官方 App 登录已失效，" + (visible ? "已打开登录页，请用本卡号码登录后再更新" : "请亮屏后在官方 App 重新登录")
                            : visible ? "后台未读到余量，已打开官方页面，请确认本卡已登录" : "手机熄屏时无法读取，亮屏后再试",
                        System.currentTimeMillis());
                    if (visible) daemon.platform.openMobile(user, route);
                  }
              } catch (Throwable e) {
                try {
                  synchronized (daemon) {
                    OfficialClient.queryState(
                        daemon,
                        identity,
                        "error",
                        "未能读取官方余量：" + GuardDaemon.safeMessage(e),
                        System.currentTimeMillis());
                  }
                } catch (Exception ignored) {
                }
              } finally {
                running.set(false);
              }
            },
            "hidden-page")
        .start();
  }

  /**
   * The web service hall, read by this app's own page on a hidden display: an activity start
   * works while the phone sleeps, where a background service start may be refused.
   */
  static boolean web(GuardDaemon daemon, String identity) throws Exception {
    String self = "com.dalong.dataguard";
    Set<Integer> tasks;
    try {
      tasks = daemon.platform.appTasks(self, 0);
    } catch (Exception unknown) {
      tasks = null;
    }
    Set<Integer> before = tasks;
    return onDisplay(
        daemon,
        identity,
        LOAD_MS + 10000,
        d -> daemon.platform.openWebRead(identity, d),
        since -> {
          synchronized (daemon) {
            JSONObject card = daemon.store.data.optJSONObject("webCard");
            return card == null || !identity.equals(card.optString("identity"));
          }
        },
        // Only the reader's own task goes; this app keeps running for its other work.
        () -> cleanUp(daemon, self, 0, before, true));
  }

  static boolean webSession(GuardDaemon daemon, String identity) {
    synchronized (daemon) {
      JSONObject card = daemon.store.data.optJSONObject("webCard");
      return card != null && identity.equals(card.optString("identity"));
    }
  }

  /** Unattended: web hall first, one immediate retry while the login remains, then SMS, then the app. */
  static void scheduled(GuardDaemon daemon) throws Exception {
    JSONArray due = new JSONArray();
    long now = System.currentTimeMillis();
    synchronized (daemon) {
      long interval = GuardDaemon.officialMinutes(daemon.store.config()) * 60000L;
      JSONObject routes = daemon.store.data.optJSONObject("mobileRoutes"),
          fresh = OfficialClient.byCard(daemon),
          texted = daemon.store.data.optJSONObject("smsScheduledAt");
      for (int i = 0; i < daemon.cards.length(); i++) {
        JSONObject card = daemon.cards.getJSONObject(i), c = daemon.cardConfig(card);
        String id = card.getString("identity");
        JSONObject route = OfficialClient.mobileRoute(routes, id);
        JSONObject latest = fresh.optJSONObject(id);
        // A card already updated within the interval (by the web service hall, say) is left alone.
        if (!"mobile".equals(card.optString("carrierCode"))
            || now - c.optLong("mobileAttemptAt") < interval
            || (latest != null && now - latest.optLong("fetchedAt") < interval)) continue;
        boolean page = route != null && daemon.queryRouteUsable() && daemon.jiangsuApp(card);
        JSONObject webCard = daemon.store.data.optJSONObject("webCard");
        boolean web = webCard != null && id.equals(webCard.optString("identity"));
        // A short query interval must not turn into a text message every few minutes.
        boolean sms =
            c.optBoolean("smsSync")
                && now - (texted == null ? 0 : texted.optLong(id)) >= Math.max(interval, SMS_EVERY_MS);
        if (!web && !page && !sms) continue;
        c.put("mobileAttemptAt", now);
        JSONObject item =
            new JSONObject()
                .put("id", id)
                .put("user", c.optInt("officialUser", 0))
                .put("web", web)
                .put("sms", sms);
        if (page) item.put("route", route);
        due.put(item);
      }
      if (due.length() > 0) daemon.store.save();
    }
    for (int i = 0; i < due.length() && running.compareAndSet(false, true); i++) {
      JSONObject item = due.getJSONObject(i);
      String id = item.getString("id");
      boolean done = false;
      try {
        if (item.optBoolean("web")) {
          int reads = com.dalong.dataguard.core.OfficialRoute.webReads(true);
          for (int attempt = 0; attempt < reads && !done; attempt++) {
            if (attempt > 0 && !webSession(daemon, id)) break;
            try {
              done = web(daemon, id);
            } catch (Exception unsupported) {
              done = false;
            }
          }
        }
      } finally {
        running.set(false);
      }
      if (done) continue;
      boolean webRemains = webSession(daemon, id);
      com.dalong.dataguard.core.OfficialRoute.Step step =
          com.dalong.dataguard.core.OfficialRoute.afterWeb(
              webRemains, item.optBoolean("sms"), item.has("route"));
      if (step == com.dalong.dataguard.core.OfficialRoute.Step.SMS) {
        synchronized (daemon) {
          long at = System.currentTimeMillis();
          JSONObject texted = daemon.store.data.optJSONObject("smsScheduledAt");
          if (texted == null) daemon.store.data.put("smsScheduledAt", texted = new JSONObject());
          texted.put(id, at);
          SmsSync.refresh(daemon, id);
        }
        continue;
      }
      if (step == com.dalong.dataguard.core.OfficialRoute.Step.APP && running.compareAndSet(false, true)) {
        try {
          synchronized (daemon) {
            OfficialClient.queryState(daemon, id, "waiting", "网上营业厅不可用，正在读取江苏 App", now);
          }
          done = read(daemon, id, item.getInt("user"), item.getJSONObject("route"));
        } catch (Exception unsupported) {
          done = false;
        } finally {
          running.set(false);
        }
        if (done) continue;
      }
      synchronized (daemon) {
        long at = System.currentTimeMillis();
        if (webRemains) {
          OfficialClient.queryState(
              daemon,
              id,
              "error",
              OfficialClient.slotLabel(daemon, id) + "网上营业厅连读两次都没有读到，登录仍在，下次仍先走网上营业厅",
              at);
          if (at - failureLoggedAt.getOrDefault(id, 0L) >= SMS_EVERY_MS) {
            failureLoggedAt.put(id, at);
            daemon.store.event(
                "query",
                "网上营业厅这次没有读到",
                "登录仍有效，已立刻再读一次，仍没有结果，没有改用江苏 App。",
                "");
          }
        } else {
          OfficialClient.queryState(
              daemon,
              id,
              "error",
              OfficialClient.slotLabel(daemon, id) + "后台更新未完成，请确认江苏 App 已登录或开启短信查询",
              at);
          if (at - failureLoggedAt.getOrDefault(id, 0L) >= SMS_EVERY_MS) {
            failureLoggedAt.put(id, at);
            daemon.store.event(
                "query", "移动后台更新未完成", "网上营业厅未登录。已尝试短信或江苏 App，仍未读到本卡余量。", "");
          }
        }
      }
    }
  }
}
