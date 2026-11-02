package com.dalong.dataguard;

import android.net.*;
import android.os.*;
import com.dalong.dataguard.core.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Root worker owns the journal and policy; the activity is only an IPC client. */
final class GuardDaemon {
  static final String SOCKET = "com.dalong.dataguard.v1";
  static final String DEBUG_REASON = "本机保护用量已达到上限";
  final Platform platform;
  final GuardStore store;
  final int appUid;
  final Map<String, JSONObject> usage = new ConcurrentHashMap<>();
  volatile JSONArray cards = new JSONArray();
  volatile String fault = "", statsFault = "", receipt = "等待首次采样";
  volatile long sampleAt, statsAt;
  volatile int activeSlot, defaultSlot;
  volatile boolean dataOn;
  /** The off-then-on steps of a card switch are one action in the log, not two outages. */
  boolean switching;
  boolean wifiConnected, wifiPaused, wifiStateKnown;
  final java.util.concurrent.atomic.AtomicBoolean networkTickQueued =
      new java.util.concurrent.atomic.AtomicBoolean();
  long lastSave, lastAction, lastSwitchAt;

  GuardDaemon(int appUid) throws Exception {
    this.appUid = appUid;
    platform = new Platform();
    store = new GuardStore();
    receipt = store.data.optString("receipt", "等待首次采样");
    // A heartbeat saved before this worker started proves nothing about the phone process now.
    store.data.remove("hookAt");
    store.data.put("recoveryRequired", strict());
    store.save();
    refreshSupervisor();
    if (strict()) {
      platform.setData(platform.defaultSub(), false);
      if (platform.activeSub() >= 0 && platform.activeSub() != platform.defaultSub())
        platform.setData(platform.activeSub(), false);
    }
  }

  /** Fail-closed behaviour belongs to debug sessions; a phone in daily use keeps its network. */
  boolean strict() {
    return System.currentTimeMillis() < store.data.optLong("debugUntil");
  }

  /** The boot script outlives app updates, so an updated worker brings its own copy. */
  static void refreshSupervisor() {
    java.nio.file.Path path = java.nio.file.Path.of("/data/adb/service.d/mobile-data-guard.sh");
    try (InputStream in =
        GuardDaemon.class.getClassLoader().getResourceAsStream("assets/guard-supervisor.sh")) {
      if (in != null && java.nio.file.Files.exists(path))
        java.nio.file.Files.write(path, in.readAllBytes());
    } catch (Exception keepPrevious) {
    }
  }

  /** Hands the phone back: nothing of the module keeps running or returns at boot. */
  void shutdown() throws Exception {
    platform.blockedSubs = Set.of();
    store.data.put("debugUntil", 0);
    store.event("settings", "模块已关闭", "已恢复系统数据开关，开机不再启动。重新打开可在流量守卫中开启。", "");
    new ProcessBuilder(
            "/system/bin/sh",
            "-c",
            // The bracket keeps pkill from matching this very command line.
            "touch /data/adb/mobile-data-guard/disabled;"
                + " rm -f /data/adb/service.d/mobile-data-guard.sh;"
                + " pkill -f '[m]obile-data-guard.sh'")
        .start()
        .waitFor();
    try {
      platform.setData(platform.defaultSub(), true);
    } catch (Exception systemKeepsItsState) {
    }
    new Thread(
            () -> {
              try {
                Thread.sleep(800);
              } catch (InterruptedException ignored) {
              }
              Runtime.getRuntime().halt(0);
            },
            "guard-exit")
        .start();
  }

  void run() throws Exception {
    try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
      ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
      control.scheduleWithFixedDelay(() -> tickSafe(), 0, 1, TimeUnit.SECONDS);
      try {
        WifiState.observe(
            platform.context,
            () -> {
              if (networkTickQueued.compareAndSet(false, true))
                control.execute(() -> {
                  networkTickQueued.set(false);
                  tickSafe();
                });
            });
      } catch (RuntimeException unavailable) {
        // The existing one-second control sample remains the bounded fallback.
      }
      ScheduledExecutorService history = Executors.newSingleThreadScheduledExecutor();
      history.scheduleWithFixedDelay(() -> refreshUsage(), 0, 30, TimeUnit.SECONDS);
      Executors.newSingleThreadScheduledExecutor()
          .scheduleWithFixedDelay(() -> OfficialClient.scheduled(this), 20, 60, TimeUnit.SECONDS);
      while (true) {
        LocalSocket socket = server.accept();
        new Thread(() -> serve(socket), "guard-client").start();
      }
    }
  }

  void serve(LocalSocket socket) {
    try (socket) {
      socket.setSoTimeout(15000);
      int uid = socket.getPeerCredentials().getUid();
      DataInputStream in = new DataInputStream(socket.getInputStream());
      int length = in.readInt();
      if (length < 1 || length > 512000) return;
      byte[] bytes = new byte[length];
      in.readFully(bytes);
      JSONObject request =
          new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      String action = request.optString("action", "status");
      if (action.equals("mobileSnapshot")) request.put("sourceUser", uid / 100000);
      if (action.equals("debugSession") && uid != 0) return;
      if (action.equals("unicomSession")) request.put("sourceUser", uid / 100000);
      if (action.equals("blockedSubs") && (uid == 0 || uid == 1001 || uid == 1000)) {
        // Answered off the daemon lock: this arrives while setData or a card switch holds it.
        reply(socket, new JSONObject().put("blockedSubs", blockedSubs()));
        return;
      }
      if (uid != 0 && uid != appUid) {
        if (action.equals("hookAlive") && (uid == 1001 || uid == 1000)) {
        } else if (action.equals("flow")) {
          String[] packages = platform.context.getPackageManager().getPackagesForUid(uid);
          if (packages == null || !Arrays.asList(packages).contains(request.optString("package")))
            return;
        } else if (action.equals("mobileSnapshot") || action.equals("mobileLogin")) {
          String pkg = request.optString("package");
          String[] packages = platform.context.getPackageManager().getPackagesForUid(uid);
          if (!Platform.MOBILE_APP.equals(pkg)
              || packages == null
              || !Arrays.asList(packages).contains(pkg)) return;
        } else if (action.equals("unicomSession")) {
          String[] packages = platform.context.getPackageManager().getPackagesForUid(uid);
          if (packages == null || !Arrays.asList(packages).contains(UnicomSession.PACKAGE)) return;
        } else return;
      }
      JSONObject result;
      try {
        result = dispatch(request);
      } catch (Throwable e) {
        String message = GuardStore.cleanMessage(safeMessage(e));
        result = new JSONObject().put("error", message);
        if (uid == 0 || uid == appUid)
          try {
            synchronized (this) {
              logFailure(action, message);
            }
          } catch (Exception ignored) {
          }
      }
      reply(socket, result);
    } catch (Exception ignored) {
    }
  }

  static void reply(LocalSocket socket, JSONObject result) throws IOException {
    byte[] output = result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
    out.writeInt(output.length);
    out.write(output);
    out.flush();
  }

  static String safeMessage(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return (e instanceof IllegalArgumentException || e instanceof SecurityException)
            && e.getMessage() != null
            && !e.getMessage().isBlank()
        ? e.getMessage()
        : e.getClass().getSimpleName() + "：操作未完成";
  }

  void logFailure(String action, String message) throws Exception {
    String operation =
        switch (action) {
          case "resume" -> "恢复移动数据被拒绝";
          case "confirmDirected" -> "继续使用通用流量被拒绝";
          case "switchCard", "quickSwitch" -> "切换数据卡未完成";
          case "config" -> "保存设置未完成";
          case "manualGeneral" -> "手动设置流量未完成";
          case "importSettings", "previewSettings" -> "导入设置未完成";
          case "stop", "dismissDirected" -> "关闭移动数据未完成";
          case "calibrateDirected" -> "定向已用量设置未完成";
          case "calibrate" -> "通用用量校准未完成";
          case "officialRefresh", "refreshCurrent", "smsQuery" -> "更新余量未完成";
          case "clearLogs" -> "清空日志未完成";
          default -> "操作未完成";
        };
    boolean protection =
        Set.of("resume", "confirmDirected", "switchCard", "quickSwitch", "stop", "dismissDirected")
            .contains(action);
    store.event(
        protection
            ? "protection"
            : Set.of(
                        "config",
                        "clearLogs",
                        "bindNumber",
                        "calibrateDirected",
                        "manualGeneral")
                    .contains(action)
                ? "settings"
                : "query",
        operation,
        "原因："
            + message
            + (protection ? "\n" + protectionAdvice(message) : "\n原设置与用量记录未因此清零，请检查输入或连接后重试。"),
        protection ? protectionDestination(message) : "");
  }

  static String protectionDestination(String reason) {
    if (reason.contains("Wi-Fi")) return "wifi";
    if (reason.contains("累计") || reason.contains("独立上限")) return "budget";
    if (reason.contains("定向") || reason.contains("等待确认") || reason.contains("待确认"))
      return "directed";
    if (reason.contains("通用") || reason.contains("额度") || reason.contains("预留")) return "general";
    return "limits";
  }

  static String protectionAdvice(String reason) {
    return switch (protectionDestination(reason)) {
      case "wifi" -> "处理：设置 → Wi-Fi 暂停限制。Wi-Fi 联网期间可手动切卡与开数据，断开后自动恢复限制。";
      case "budget" -> "处理：设置 → 本机累计上限。提高停止线上限不会清零用量，也不会自动恢复数据。";
      case "general" -> "处理：设置 → 通用流量限制。核对通用官方余额与剩余停止阈值，必要时更新官方余量后再恢复。";
      case "directed" -> "处理：设置 → 定向包用尽设置。核对对应包的余额、用尽保护及断开确认；待确认时先完成选择。";
      default -> "处理：设置 → 限制设置与保护健康。核对实际数据卡、SIM 身份及电话保护；已禁止使用数据的卡在 Wi-Fi 暂停结束后不能启用。";
    };
  }

  synchronized JSONObject dispatch(JSONObject r) throws Exception {
    refreshWifiState();
    switch (r.optString("action", "status")) {
      case "status":
        return status();
      case "health":
        return health();
      case "clearLogs":
        store.clearEvents();
        break;
      case "config":
        saveConfig(r.getJSONObject("config"));
        break;
      case "stop":
        stop("用户停止移动数据");
        break;
      case "switchCard":
        selectDataCard(r.getInt("slot"));
        break;
      case "cardAccess":
        setCardAccess(r);
        break;
      case "quickSwitch":
        quickSwitch(r);
        break;
      case "shutdown":
        shutdown();
        return new JSONObject().put("off", true).put("blockedSubs", "").put("sampleAt", 1);
      case "manualGeneral":
        manualGeneral(r);
        break;
      case "debugSession":
        return debugSession(r);
      case "smsQuery":
        JSONObject smsCard = card(r.getInt("slot"));
        if (!smsCard.getString("identity").equals(r.getString("identity")))
          throw new SecurityException("SIM 已变化，请重新选择");
        SmsSync.refresh(this, smsCard.getString("identity"));
        break;
      case "unicomSession":
        OfficialClient.unicomSession(this, r);
        return new JSONObject().put("ok", true);
      case "exportSettings":
        return SettingsBackup.export(this);
      case "previewSettings":
        return SettingsBackup.preview(this, r.getJSONObject("backup"));
      case "importSettings":
        SettingsBackup.restore(this, r.getJSONObject("backup"));
        break;
      case "bindNumber":
        bindNumber(r);
        break;
      case "resume":
        resumeData();
        break;
      case "directedPrompt":
        return DirectedPackages.prompt(this, r.getString("episode"));
      case "confirmDirected":
        DirectedPackages.confirm(this, r.getString("episode"));
        break;
      case "dismissDirected":
        DirectedPackages.dismiss(this, r.getString("episode"));
        break;
      case "calibrate":
        calibrate(r);
        break;
      case "calibrateDirected":
        calibrateDirected(r);
        break;
      case "refresh":
        refreshUsage();
        break;
      case "hookAlive":
        store.data.put("hookAt", System.currentTimeMillis()).put("hookBoot", platform.bootId());
        store
            .data
            .put("hookNetworkReady", r.optBoolean("networkReady"))
            .put("hookWifiConnected", r.optBoolean("wifiConnected"));
        store.data.put("hookProcess", r.optString("process"));
        return new JSONObject().put("ok", true);
      case "seedBudget":
        if (store.data.optBoolean("seeded")) throw new SecurityException("预算已初始化，不允许清零或重复覆盖");
        long prior = r.getLong("used");
        if (prior < 0) throw new IllegalArgumentException("预算非法");
        store
            .data
            .put("budgetUsed", Math.max(prior, store.data.optLong("budgetUsed")))
            .put("seeded", true);
        store.event("已恢复累计用量保护记录");
        break;
      case "officialImport":
        store.data.put("official", r.getJSONObject("snapshot"));
        store.event("已导入官方只读查询结果；账号归属待核实");
        break;
      case "mobileSnapshot":
        OfficialClient.mobileSnapshot(this, r);
        return new JSONObject().put("ok", true);
      case "mobileLogin":
        HiddenPage.loginAt = System.currentTimeMillis();
        return new JSONObject().put("ok", true);
      case "officialRefresh":
        OfficialClient.refresh(this);
        break;
      case "refreshCurrent":
        JSONObject requested = card(r.getInt("slot"));
        if (!requested.getString("identity").equals(r.getString("identity")))
          throw new SecurityException("SIM 已变化，请重新选择");
        refreshUsage();
        store.event("query", "", "已更新卡 " + r.getInt("slot") + " 的蜂窝用量", "");
        if (r.optBoolean("official") && "unicom".equals(requested.optString("carrierCode")))
          OfficialClient.refreshOrOpen(this, requested);
        if (r.optBoolean("official") && "mobile".equals(requested.optString("carrierCode")))
          OfficialClient.openMobile(this, requested);
        break;
      case "flow":
        observe(r);
        return new JSONObject().put("ok", true);
      default:
        throw new IllegalArgumentException("不支持的操作");
    }
    return status();
  }

  void validateResume() throws Exception {
    refreshWifiState();
    int target = defaultSlot;
    if (target < 1) throw new SecurityException("没有可用的数据卡");
    if (strict()
        && (activeSlot != target
            || !fault.isEmpty()
            || !statsFault.isEmpty()
            || sampleAt == 0
            || System.currentTimeMillis() - sampleAt > 5000))
      throw new SecurityException("卡身份／用量保护／监控未就绪，不能恢复");
    boolean same = false;
    JSONArray current = platform.cards();
    for (int i = 0; i < current.length(); i++) {
      JSONObject row = current.getJSONObject(i);
      if (row.getInt("slot") == target)
        same = card(target).getString("identity").equals(row.getString("identity"));
    }
    if (!same) throw new SecurityException("卡 " + target + " 身份已变化，不能恢复");
    JSONObject c = cardConfig(card(target));
    requireCardPermitted(c, target);
    if (!phoneGuardReady()) throw new SecurityException("本次启动的 LSPosed 电话保护尚未就绪，请确认模块作用域并重启");
    if (strict() && (statsAt == 0 || System.currentTimeMillis() - statsAt > 90000))
      throw new SecurityException("按卡统计已过期，不能恢复");
    requireWithinLimits(card(target), c);
  }

  /** The user's own limits; checked before a switch too, so a refused card costs no outage. */
  void requireWithinLimits(JSONObject card, JSONObject c) throws Exception {
    if (store.data.optLong("budgetUsed") >= cumulativeLimit())
      throw new SecurityException(budgetReason());
    if (debugExhausted()) throw new SecurityException(DEBUG_REASON);
    long left = remaining(c, usage.get(card.getString("identity")));
    if (!wifiPaused
        && store.config().optBoolean("enabled")
        && left >= 0
        && left <= c.optLong("reserve", 500000000))
      throw new SecurityException(generalReason(card.getInt("slot"), c, left));
  }

  /** One tap from either card to a working connection on the chosen one. */
  void quickSwitch(JSONObject r) throws Exception {
    refreshWifiState();
    int target = r.getInt("slot");
    JSONObject card = card(target), c = cardConfig(card);
    // Limits first: a refused switch must leave the card's permission exactly as it was.
    requireWithinLimits(card, c);
    if (r.optBoolean("allow") && c.optBoolean("dataForbidden")) {
      c.put("dataForbidden", false).remove("newSim");
      syncBlockedSubs();
      store.event("settings", "已允许此卡使用移动数据", "卡 " + target + "：切换时由用户允许。", "limits");
    }
    requireCardPermitted(c, target);
    int from = defaultSlot;
    if (target == defaultSlot && activeSlot == target) {
      if (!dataOn) resumeData();
      return;
    }
    switching = true;
    try {
      selectDataCard(target);
      resumeData();
    } finally {
      switching = false;
    }
    store.event(
        "protection",
        "已切换数据卡",
        "卡 "
            + from
            + " → 卡 "
            + target
            + "\n结果：已核验卡 "
            + target
            + " 身份与限制，移动数据"
            + (dataOn ? "已开启。" : "未能开启，请在用卡与数据中重试。"),
        "limits");
  }

  void requireCardPermitted(JSONObject c, int slot) {
    if (c.optBoolean("newSim"))
      throw new SecurityException("卡 " + slot + " 是新 SIM，请先在用卡与数据中确认");
    if (c.optBoolean("dataForbidden") && !wifiPaused)
      throw new SecurityException("卡 " + slot + " 已设置禁止使用移动数据");
  }

  void resumeData() throws Exception {
    validateResume();
    String reason = DirectedPackages.resumeReason(this);
    if (!reason.isEmpty()) throw new SecurityException(reason);
    platform.setData(platform.defaultSub(), true);
    dataOn = platform.dataEnabled(platform.defaultSub());
    store.data.put("recoveryRequired", false);
    receipt = dataOn ? "已核验：卡 " + defaultSlot + " 数据开启" : "恢复请求未生效";
    if (switching) return;
    store.event(
        "protection",
        dataOn ? "卡 " + defaultSlot + " 移动数据已开启" : "恢复移动数据未完成",
        "结果："
            + receipt
            + "\n本机累计已用 "
            + gb(store.data.optLong("budgetUsed"))
            + " / 当前停止线 "
            + (cumulativeLimit() == PolicyEngine.NO_LIMIT ? "未设置" : gb(cumulativeLimit()))
            + (wifiPaused ? "。Wi-Fi 联网中，额度限制临时暂停；断开后自动恢复核验，不会自动切卡或开启数据。" : "。SIM、统计、电话保护与当前限制已核验。"),
        "limits");
  }

  void observe(JSONObject r) throws Exception {
    if (defaultSlot < 1 || activeSlot != defaultSlot || !dataOn || !fault.isEmpty()) return;
    long rx = r.getLong("rx"), tx = r.getLong("tx");
    if (rx < 0 || tx < 0 || rx > 100_000_000 || tx > 100_000_000)
      throw new IllegalArgumentException("观察量非法");
    String host = r.getString("host"), path = r.optString("path", "/");
    if (host.length() > 253 || path.length() > 4096) return;
    JSONObject cc = cardConfig(card(defaultSlot));
    JSONArray packs = cc.getJSONArray("packages");
    List<JSONObject> ordered = new ArrayList<>();
    for (int i = 0; i < packs.length(); i++) ordered.add(packs.getJSONObject(i));
    ordered.sort(Comparator.comparingInt(p -> p.optInt("priority", 100)));
    for (JSONObject pack : ordered) {
      boolean app = false, rule = false;
      JSONArray apps = pack.getJSONArray("apps"), rules = pack.getJSONArray("rules");
      for (int i = 0; i < apps.length(); i++)
        app |= apps.getString(i).equals(r.optString("package"));
      for (int i = 0; i < rules.length(); i++) {
        String s = rules.getString(i);
        int slash = s.indexOf('/');
        String domain = slash < 0 ? s : s.substring(0, slash),
            prefix = slash < 0 ? "" : s.substring(slash);
        try {
          rule |= new RuleMatcher.Rule(domain, prefix).matches("https://" + host + path, true);
        } catch (Exception ignored) {
        }
      }
      if (!(pack.optBoolean("requireBoth") ? app && rule : app || rule)) continue;
      long cycle =
          UsageMath.carrierCycleStart(System.currentTimeMillis(), cc.optInt("cycleDay", 1));
      JSONObject obs = cc.optJSONObject("observations");
      if (obs == null || obs.optLong("cycleStart") != cycle)
        obs = new JSONObject().put("cycleStart", cycle).put("packages", new JSONObject());
      JSONObject counts = obs.getJSONObject("packages"),
          p = counts.optJSONObject(pack.getString("id"));
      if (p == null) p = new JSONObject();
      p.put("rx", Math.addExact(p.optLong("rx"), rx))
          .put("tx", Math.addExact(p.optLong("tx"), tx))
          .put("at", System.currentTimeMillis());
      counts.put(pack.getString("id"), p);
      cc.put("observations", obs);
      store.save();
      break;
    }
  }

  void selectDataCard(int target) throws Exception {
    refreshWifiState();
    JSONObject selected = null;
    JSONArray live = platform.cards();
    for (int i = 0; i < live.length(); i++)
      if (live.getJSONObject(i).getInt("slot") == target) selected = live.getJSONObject(i);
    if (selected == null
        || !selected.getString("identity").equals(card(target).getString("identity")))
      throw new SecurityException("卡 " + target + " 身份已变化，请停止使用移动数据");
    requireCardPermitted(cardConfig(selected), target);
    if (strict()
        && (!phoneGuardReady()
            || sampleAt == 0
            || System.currentTimeMillis() - sampleAt > 5000
            || !statsFault.isEmpty())) throw new SecurityException("监控或电话保护未就绪，请稍后重试");
    if (store.data.optLong("budgetUsed") >= cumulativeLimit())
      throw new SecurityException(budgetReason());
    if (platform.slotForSub(platform.defaultSub()) == target
        && platform.slotForSub(platform.activeSub()) == target) {
      receipt = "已核验：当前数据卡已是卡 " + target;
      store.event(receipt);
      return;
    }
    stop("切换数据卡前保持数据关闭");
    platform.switchTo(selected.getInt("subId"));
    long deadline = SystemClock.elapsedRealtime() + 3000;
    while (SystemClock.elapsedRealtime() < deadline
        && (platform.slotForSub(platform.defaultSub()) != target
            || platform.slotForSub(platform.activeSub()) != target)) Thread.sleep(100);
    activeSlot = platform.slotForSub(platform.activeSub());
    defaultSlot = platform.slotForSub(platform.defaultSub());
    if (activeSlot != target || defaultSlot != target)
      throw new IllegalArgumentException("系统未完成切卡，移动数据保持关闭，请重试");
    stop("已切至卡 " + target + "；如需联网请单独恢复数据");
  }

  void autoSwitch(int target, String reason) throws Exception {
    try {
      switching = true;
      try {
        selectDataCard(target);
        resumeData();
      } finally {
        switching = false;
      }
      store.event(
          "protection",
          "已自动切换数据卡",
          "原因：" + reason + "\n结果：已核验卡 " + target + " 身份与限制，并恢复移动数据。",
          "limits");
    } catch (Exception e) {
      stop("自动切卡未完成，保持数据关闭：" + safeMessage(e));
    }
  }

  void setCardAccess(JSONObject r) throws Exception {
    JSONObject card = card(r.getInt("slot")), c = cardConfig(card);
    if (!card.getString("identity").equals(r.getString("identity")))
      throw new SecurityException("SIM 已变化，请重新选择");
    boolean forbidden = r.getBoolean("forbidden");
    c.put("dataForbidden", forbidden).remove("newSim");
    store.event(
        "settings",
        forbidden ? "已禁止此卡使用移动数据" : "已允许此卡使用移动数据",
        "卡 "
            + card.getInt("slot")
            + (forbidden ? "：不能作为数据卡或备用卡；Wi-Fi 暂停期间可手动使用。" : "：可作为数据卡；保存不会自动开启移动数据。"),
        "limits");
    if (forbidden && card.getInt("slot") == defaultSlot && dataOn && !wifiPaused)
      stop("当前数据卡已设置禁止使用移动数据");
    syncBlockedSubs();
  }

  /** Root-only and absent from the UI: only a time-boxed session spends the debug budget. */
  JSONObject debugSession(JSONObject r) throws Exception {
    long hours = r.getLong("hours");
    if (hours < 0 || hours > 12) throw new IllegalArgumentException("会话时长非法");
    long until = hours == 0 ? 0 : System.currentTimeMillis() + hours * 3600000L;
    store.data.put("debugUntil", until);
    store.save();
    return new JSONObject()
        .put("debugUntil", until)
        .put("debugUsed", store.data.optLong("debugUsed"));
  }

  boolean debugExhausted() {
    return PolicyEngine.debugExhausted(
        store.data.optLong("debugUntil"),
        System.currentTimeMillis(),
        store.data.optLong("debugUsed"));
  }

  JSONObject findCardByIdentity(String identity) throws Exception {
    for (int i = 0; i < cards.length(); i++)
      if (identity.equals(cards.getJSONObject(i).optString("identity")))
        return cards.getJSONObject(i);
    throw new IllegalArgumentException("SIM 已变化");
  }

  JSONObject findCard(int slot) throws Exception {
    for (int i = 0; i < cards.length(); i++) {
      JSONObject c = cards.getJSONObject(i);
      if (c.getInt("slot") == slot) return c;
    }
    return null;
  }

  void syncBlockedSubs() throws Exception {
    Set<Integer> blocked = new HashSet<>();
    for (int i = 0; i < cards.length(); i++) {
      JSONObject card = cards.getJSONObject(i);
      if (cardConfig(card).optBoolean("dataForbidden")) blocked.add(card.getInt("subId"));
    }
    platform.blockedSubs = blocked;
  }

  String blockedSubs() {
    StringJoiner list = new StringJoiner(",");
    for (int sub : platform.blockedSubs) list.add(String.valueOf(sub));
    return list.toString();
  }

  /** The Root worker stops a barred card by itself; the Hook is only mandatory in debugging. */
  boolean phoneGuardReady() throws Exception {
    return !strict() || hookReady();
  }

  /** Official queries travel over Wi-Fi or a permitted data card, never a barred one. */
  boolean queryRouteUsable() throws Exception {
    if (wifiConnected) return true;
    JSONObject data = findCard(defaultSlot);
    return data != null && activeSlot == defaultSlot && !cardConfig(data).optBoolean("dataForbidden");
  }

  boolean anyDataEnabled() throws Exception {
    int sub = platform.defaultSub(), active = platform.activeSub();
    return platform.dataEnabled(sub) || platform.dataEnabled(active);
  }

  int usableFixedSlot(JSONObject config) throws Exception {
    JSONObject card = findCard(config.optInt("fixedSlot"));
    if (card == null) return 0;
    JSONObject c = cardConfig(card);
    return c.optBoolean("dataForbidden") || c.optBoolean("newSim") ? 0 : card.getInt("slot");
  }

  int backupSlot(int active) throws Exception {
    for (int i = 0; i < cards.length(); i++) {
      JSONObject card = cards.getJSONObject(i), c = cardConfig(card);
      if (card.getInt("slot") != active
          && PolicyEngine.eligibleBackup(
              !c.optBoolean("dataForbidden") && !c.optBoolean("newSim"),
              remaining(c, usage.get(card.getString("identity"))),
              c.optLong("reserve", 500000000))) return card.getInt("slot");
    }
    return 0;
  }

  void bindNumber(JSONObject r) throws Exception {
    JSONObject live = null;
    JSONArray list = platform.cards();
    for (int i = 0; i < list.length(); i++)
      if (list.getJSONObject(i).getInt("slot") == r.getInt("slot")) live = list.getJSONObject(i);
    if (live == null || !live.getString("identity").equals(r.getString("identity")))
      throw new SecurityException("SIM 已变化，请重新选择");
    String phone = PhoneIdentity.normalize(r.getString("number"));
    if (phone.isEmpty()) throw new IllegalArgumentException("请输入完整的 11 位本卡号码");
    JSONObject c = cardConfig(live);
    c.put("numberKey", Platform.hash(phone))
        .put("numberMasked", PhoneIdentity.masked(phone))
        .put("numberSource", "user");
    store.event("卡 " + r.getInt("slot") + " 的号码已关联，日志仅保留脱敏标识");
  }

  JSONObject card(int slot) throws Exception {
    for (int i = 0; i < cards.length(); i++) {
      JSONObject c = cards.getJSONObject(i);
      if (c.getInt("slot") == slot) return c;
    }
    throw new IllegalArgumentException("卡槽不可用");
  }

  JSONObject cardConfig(JSONObject card) throws Exception {
    JSONObject all = store.config().getJSONObject("cards");
    String id = card.getString("identity");
    if (!all.has(id)) {
      // During a debug session an unseen SIM carries no data until the user accepts it.
      if (strict())
        store.event(
            "protection",
            "检测到新 SIM，等待确认",
            "卡 " + card.getInt("slot") + " 是未使用过的 SIM。确认前不能作为数据卡。\n处理：用卡与数据 → 选择此卡确认。",
            "limits");
      all.put(
          id,
          new JSONObject()
              .put("alias", "卡 " + card.getInt("slot"))
              .put("dataForbidden", strict())
              .put("newSim", strict())
              .put("cycleDay", 1)
              .put("quota", -1)
              .put("officialQuotaManaged", true)
              .put("reserve", 500_000_000L)
              .put("packages", new JSONArray()));
    }
    return all.getJSONObject(id);
  }

  void tickSafe() {
    try {
      tick();
    } catch (Throwable e) {
      String previous = fault;
      fault = safeMessage(e);
      try {
        // Monitoring trouble is reported, not turned into an outage; debugging still fails closed.
        if (!strict()) {
          if (!fault.equals(previous)) store.event("protection", "监控异常", "原因：" + fault, "limits");
          return;
        }
        synchronized (this) {
          store.data.put("recoveryRequired", true);
          store.save();
        }
        // A persistent fault re-stops data only if it came back on, not once per second.
        if (!fault.equals(previous) || anyDataEnabled()) stop("监控异常，已请求停数据：" + fault);
      } catch (Throwable ignored) {
        receipt = "保护执行失败，请关闭移动数据";
      }
    }
  }

  synchronized void tick() throws Exception {
    refreshWifiState();
    long now = System.currentTimeMillis();
    JSONArray next = platform.cards();
    cards = next;
    int defaultSub = platform.defaultSub(), activeSub = platform.activeSub();
    activeSlot = Platform.slotForSub(next, activeSub);
    defaultSlot = Platform.slotForSub(next, defaultSub);
    for (int i = 0; i < cards.length(); i++) cardConfig(cards.getJSONObject(i));
    syncBlockedSubs();
    OfficialClient.syncPackages(this);
    OfficialClient.expireQueries(this);
    if (next.length() == 0) {
      // No active subscription carries no cellular data; keep the journal for the next sample.
      dataOn = false;
      sampleAt = now;
      fault = "";
      return;
    }
    Map<String, Long> current = platform.counters(), previous = new HashMap<>();
    if (current.isEmpty()
        && (now < store.data.optLong("debugUntil") || store.config().optBoolean("budgetEnabled")))
      throw new IllegalArgumentException("蜂窝计数接口不可读");
    JSONObject old = store.data.getJSONObject("counters");
    for (Iterator<String> it = old.keys(); it.hasNext(); ) {
      String k = it.next();
      previous.put(k, old.getLong(k));
    }
    String boot = platform.bootId(), lastBoot = store.data.optString("boot");
    // Only the very first sample establishes a baseline. A later new interface
    // contributes its complete counter, including bytes before its first poll.
    long delta =
        lastBoot.isEmpty() && previous.isEmpty() ? 0 : UsageMath.counterDelta(previous, current);
    if (!lastBoot.isEmpty() && !lastBoot.equals(boot)) {
      delta = UsageMath.counterDelta(Map.of(), current);
      store.event(
          "protection",
          "重启后累计用量已接续",
          "本次启动的蜂窝计数继续计入本机累计上限，退出或重启不会清零。",
          "limits");
      if (strict()) stop("重启恢复保护，已请求停数据");
      previous.clear();
    }
    previous.putAll(current);
    store
        .data
        .put("counters", new JSONObject(previous))
        .put("boot", boot)
        .put("budgetUsed", Math.addExact(store.data.optLong("budgetUsed"), delta))
        .put("lastSampleAt", now);
    if (now < store.data.optLong("debugUntil"))
      store.data.put("debugUsed", Math.addExact(store.data.optLong("debugUsed"), delta));
    sampleAt = now;
    boolean wasDataOn = dataOn;
    dataOn =
        platform.dataEnabled(defaultSub)
            || (activeSub >= 0 && activeSub != defaultSub && platform.dataEnabled(activeSub));
    // Some dual-SIM systems mirror the user-data flag on the idle subscription.
    // Decide from actual/default data subscriptions; writing the idle flag can turn both off.
    JSONObject config = store.config(), data = findCard(defaultSlot);
    JSONObject cc = data == null ? null : cardConfig(data);
    JSONObject currentUsage = data == null ? null : usage.get(data.getString("identity"));
    if (activeSlot == defaultSlot && cc != null && currentUsage != null) {
      long cycle = UsageMath.carrierCycleStart(now, cc.optInt("cycleDay", 1));
      JSONObject previousUsage = cc.optJSONObject("protectedUsage");
      long protectedUsed =
          UsageMath.conservativeUsed(
              previousUsage == null ? -1 : previousUsage.optLong("used", -1),
              previousUsage == null ? -1 : previousUsage.optLong("cycleStart", -1),
              currentUsage.optLong("raw", -1),
              currentUsage.optLong("cycleStart", -1),
              cycle,
              delta);
      if (protectedUsed >= 0)
        cc.put(
            "protectedUsage", new JSONObject().put("cycleStart", cycle).put("used", protectedUsed));
    }
    long left = cc == null ? -1 : remaining(cc, currentUsage);
    long reserve = cc == null ? 500000000 : cc.optLong("reserve", 500000000);
    // A call may briefly move data to a permitted card; a barred card still stops immediately.
    JSONObject active = findCard(activeSlot);
    boolean known =
        activeSlot > 0
            && defaultSlot > 0
            && active != null
            && (activeSlot == defaultSlot
                || (!cardConfig(active).optBoolean("dataForbidden")
                    && !cardConfig(active).optBoolean("newSim")))
            && cc != null
            && !cc.optBoolean("newSim")
            && (!strict()
                || (statsFault.isEmpty()
                    && statsAt > 0
                    && now - statsAt <= 90000
                    && (!store.data.optBoolean("recoveryRequired") || wifiPaused)
                    && phoneGuardReady()));
    PolicyEngine.Decision decision =
        PolicyEngine.decide(
            new PolicyEngine.Input(
                (cc != null && cc.optBoolean("dataForbidden"))
                    || (active != null && cardConfig(active).optBoolean("dataForbidden")),
                known,
                defaultSlot,
                config.optBoolean("enabled"),
                config.optString("mode"),
                usableFixedSlot(config),
                left,
                reserve,
                store.data.optLong("budgetUsed"),
                cumulativeLimit(),
                backupSlot(defaultSlot),
                wifiPaused,
                strict()));
    if (wifiPaused && known && dataOn) store.data.put("recoveryRequired", false);
    if (decision.action() == PolicyEngine.Action.STOP && dataOn)
      stop(
          store.data.optLong("budgetUsed") >= cumulativeLimit()
              ? budgetReason()
              : !wifiPaused
                      && known
                      && !cc.optBoolean("dataForbidden")
                      && config.optBoolean("enabled")
                      && left >= 0
                      && left <= reserve
                  ? generalReason(defaultSlot, cc, left)
                  : decision.reason());
    if (debugExhausted() && dataOn) stop(DEBUG_REASON);
    DirectedPackages.enforce(this);
    if (decision.action() == PolicyEngine.Action.SWITCH && dataOn) {
      // Bounded retries: a card flip-flopping faster than this stays off rather than overrunning.
      if (now - lastSwitchAt < 60000) stop(decision.reason() + "；切卡过于频繁，保持数据关闭");
      else {
        lastSwitchAt = now;
        autoSwitch(decision.targetSlot(), decision.reason());
      }
    }
    fault = "";
    // Persist every changed counter, including IMS while data is off. Idle heartbeats
    // and timestamps need not rewrite the entire ledger every second.
    if (delta > 0 || wasDataOn != dataOn || now - lastSave >= 30000) {
      store.save();
      lastSave = now;
    }
  }

  synchronized void stop(String reason) throws Exception {
    long now = System.currentTimeMillis();
    int sub = platform.defaultSub(), active = platform.activeSub();
    if (active >= 0 && active != sub) platform.setData(active, false);
    platform.setData(sub, false);
    JSONArray live = platform.cards();
    for (int i = 0; i < live.length(); i++) {
      int other = live.getJSONObject(i).getInt("subId");
      if (other != sub && other != active && platform.dataEnabled(other))
        platform.setData(other, false);
    }
    dataOn =
        platform.dataEnabled(sub) || (active >= 0 && active != sub && platform.dataEnabled(active));
    for (int i = 0; i < live.length(); i++)
      dataOn |= platform.dataEnabled(live.getJSONObject(i).getInt("subId"));
    receipt = dataOn ? "停数据未生效：" + reason : "已核验：移动数据关闭 · " + reason;
    if (dataOn) throw new IllegalStateException("停数据未通过核验");
    lastAction = now;
    store.data.put("receipt", receipt);
    if (switching) return;
    store.event(
        "protection",
        dataOn ? "关闭移动数据未完成" : "移动数据已关闭",
        "结果："
            + (dataOn ? "关闭后仍检测到移动数据开启" : "已核验数据关闭")
            + "\n原因："
            + reason
            + "\n"
            + (reason.equals("用户停止移动数据") ? "处理：需要联网时，在保护页点击核验后恢复数据。" : protectionAdvice(reason)),
        protectionDestination(reason));
  }

  void refreshUsage() {
    try {
      JSONArray list = platform.cards();
      long now = System.currentTimeMillis();
      for (int i = 0; i < list.length(); i++) {
        JSONObject card = list.getJSONObject(i);
        int day;
        synchronized (this) {
          day = cardConfig(card).optInt("cycleDay", 1);
        }
        long start = UsageMath.carrierCycleStart(now, day);
        JSONObject result =
            platform
                .usage(card.getInt("subId"), start, now)
                .put("cycleStart", start)
                .put("at", now);
        usage.put(card.getString("identity"), result);
      }
      statsAt = now;
      statsFault = "";
    } catch (Throwable e) {
      statsFault = "按卡历史统计不可用：" + safeMessage(e);
      try {
        if (strict()) stop(statsFault);
      } catch (Exception ignored) {
      }
    }
  }

  long protectedRaw(JSONObject c, JSONObject u) {
    if (u == null) return -1;
    long raw = u.optLong("raw", -1);
    JSONObject protection = c.optJSONObject("protectedUsage");
    if (raw >= 0
        && protection != null
        && protection.optLong("cycleStart", -1) == u.optLong("cycleStart", -2))
      raw = Math.max(raw, protection.optLong("used", -1));
    return raw;
  }

  long remaining(JSONObject c, JSONObject u) {
    if (u == null) return -1;
    JSONObject snap = c.optJSONObject("calibration");
    return UsageMath.generalRemaining(
        c.optLong("quota", -1),
        protectedRaw(c, u),
        u.optLong("cycleStart", -1),
        UsageMath.carrierCycleStart(System.currentTimeMillis(), c.optInt("cycleDay", 1)),
        snap == null ? -1 : snap.optLong("used", -1),
        snap == null ? -1 : snap.optLong("rawAt", -1),
        snap == null ? -1 : snap.optLong("cycleStart", -1),
        snap == null ? -1 : snap.optLong("remaining", -1));
  }

  long cumulativeLimit() throws Exception {
    return PolicyEngine.cumulativeLimit(
        store.config().optBoolean("budgetEnabled"),
        store.config().optLong("budgetLimit", 1000000000),
        wifiPaused);
  }

  void refreshWifiState() throws Exception {
    boolean connected = WifiState.connected(platform.context);
    boolean paused = connected && store.config().optBoolean("pauseOnWifi", true);
    boolean changed = wifiPaused != paused;
    wifiConnected = connected;
    wifiPaused = paused;
    if (changed || (!wifiStateKnown && paused)) {
      if (paused) DirectedPackages.pause(this);
      store.event(
          "protection",
          paused ? "Wi-Fi 联网，额度限制已暂停" : "Wi-Fi 暂停结束，限制已恢复",
          paused
              ? "当前默认网络为已联网的 Wi-Fi。通用阈值、本机累计设置、定向用尽提醒及断开确认临时暂停；两张卡可手动切换和开启数据。原开关与数值保留，蜂窝计数继续累计。\n"
                  + "断开或 Wi-Fi 不再承担联网时立即重新核验，不会自动切卡或开启数据。SIM 与监控异常保护仍生效。"
              : "Wi-Fi 不再承担联网，或已关闭 Wi-Fi"
                  + " 暂停选项。已恢复原来的通用阈值、本机累计设置、定向用尽规则与卡槽限制；达到停止条件会关闭数据。不会自动切卡或开启数据。",
          "wifi");
    }
    wifiStateKnown = true;
  }

  String budgetReason() throws Exception {
    return "本机累计用量已达设置上限（已用 "
        + gb(store.data.optLong("budgetUsed"))
        + " / 上限 "
        + gb(cumulativeLimit())
        + "），与运营商通用、定向余额分别计算";
  }

  static String gb(long n) {
    return n < 10000000
        ? String.format(Locale.ROOT, "%.1f MB", n / 1e6)
        : String.format(Locale.ROOT, "%.2f GB", n / 1e9);
  }

  String generalReason(int slot, JSONObject c, long left) {
    if (left < 0) return "卡 " + slot + " 通用可用余量尚未核实；通用流量限制已开启，保持数据关闭";
    return String.format(
        Locale.ROOT,
        "卡 %d 通用限制触线：本地可用估算 %.2f GiB，已达到停止阈值 %.1f MB",
        slot,
        left / 1073741824d,
        c.optLong("reserve", 500000000) / 1e6);
  }

  /** Run only for a new, identity-bound official snapshot, never rebase an old one at now. */
  void syncGeneralCalibration(JSONObject card, JSONObject general, long at) throws Exception {
    JSONObject c = cardConfig(card);
    long now = System.currentTimeMillis();
    long cycle = UsageMath.carrierCycleStart(now, c.optInt("cycleDay", 1));
    if (at < cycle || at > now || general.optLong("used", -1) < 0) return;
    JSONObject previous = c.optJSONObject("calibration");
    if (previous != null && previous.optLong("asOf") > at) return;
    String signature = generalSignature(general, cycle);
    String priorSignature = c.optString("generalBaselineSignature");
    JSONObject priorGeneral = c.optJSONObject("officialGeneral");
    if (priorSignature.isEmpty()
        && previous != null
        && priorGeneral != null
        && "本卡官方通用余量".equals(previous.optString("source"))
        && previous.optLong("asOf") == priorGeneral.optLong("at"))
      priorSignature = generalSignature(priorGeneral, previous.optLong("cycleStart"));
    // A repeated DOM read or rounded/cached balance is NOT a fresh accounting baseline.
    if (signature.equals(priorSignature)) {
      c.put("generalBaselineSignature", signature).put("officialCalibrationAt", at);
      return;
    }
    if (c.optLong("officialCalibrationAt") == at) return;
    // A transient history failure cannot invalidate the official display or cause a hot retry loop.
    if (now - c.optLong("generalBaselineAttempt") < 30000) return;
    c.put("generalBaselineAttempt", now);
    try {
      long rawAt = platform.usage(card.getInt("subId"), cycle, at).getLong("raw");
      if (rawAt < 0) return;
      c.put(
          "calibration",
          new JSONObject()
              .put("used", general.getLong("used"))
              .put("remaining", general.optLong("remaining", -1))
              .put("rawAt", rawAt)
              .put("asOf", at)
              .put("cycleStart", cycle)
              .put("fetchedAt", now)
              .put("source", "本卡官方通用余量"));
      c.put("officialCalibrationAt", at).put("generalBaselineSignature", signature);
    } catch (Exception ignored) {
      // Keep any previous baseline and retry at the next bounded opportunity.
    }
  }

  static String generalSignature(JSONObject general, long cycle) {
    return cycle
        + ":"
        + general.optLong("total", -1)
        + ":"
        + general.optLong("used", -1)
        + ":"
        + general.optLong("remaining", -1);
  }

  void saveConfig(JSONObject config) throws Exception {
    JSONObject previous = store.config();
    List<String> changes = new ArrayList<>();
    String mode = config.getString("mode");
    if (!Set.of("fixed", "stop", "auto").contains(mode))
      throw new IllegalArgumentException("用卡策略非法");
    long limit = config.getLong("budgetLimit");
    if (limit < 1_000_000 || limit > 1_000_000_000_000L)
      throw new IllegalArgumentException("本机累计上限应在 0.001–1000 GB 内");
    int fixedSlot = config.optInt("fixedSlot");
    if (fixedSlot < 0 || fixedSlot > 2) throw new IllegalArgumentException("固定用卡非法");
    if ("fixed".equals(mode)) {
      JSONObject fixed = findCard(fixedSlot);
      if (fixed == null) throw new IllegalArgumentException("请选择有 SIM 的卡作为固定用卡");
      requireCardPermitted(cardConfig(fixed), fixedSlot);
    }
    config.put("budgetEnabled", config.optBoolean("budgetEnabled", false));
    config.put("pauseOnWifi", config.optBoolean("pauseOnWifi", true));
    int hours = config.optInt("scheduleHours", 2);
    if (hours < 1 || hours > 168) throw new IllegalArgumentException("查询间隔非法");
    JSONObject all = config.getJSONObject("cards");
    // A form built from an older status cannot drop a card or its permission.
    JSONObject stored = previous.getJSONObject("cards");
    for (Iterator<String> it = stored.keys(); it.hasNext(); ) {
      String key = it.next();
      if (!all.has(key)) all.put(key, stored.getJSONObject(key));
    }
    Set<String> pools = new HashSet<>();
    for (Iterator<String> it = all.keys(); it.hasNext(); ) {
      String key = it.next();
      if (!key.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("卡身份非法");
      JSONObject c = all.getJSONObject(key);
      JSONObject current = store.config().getJSONObject("cards").optJSONObject(key);
      if (current != null)
        for (String owned :
            new String[] {
              "observations",
              "protectedUsage",
              "calibration",
              "officialCalibrationAt",
              "generalBaselineAttempt",
              "generalBaselineSignature",
              "officialCatalog",
              "officialGeneral",
              "officialSyncedAt",
              "officialUser",
              "dataForbidden",
              "newSim",
              "mobileAttemptAt"
            }) {
          c.remove(owned);
          if (current.has(owned)) c.put(owned, current.get(owned));
        }
      int day = c.optInt("cycleDay", 1);
      if (day < 1 || day > 31 || c.optLong("quota", -1) < -1 || c.optLong("reserve", 0) < 0)
        throw new IllegalArgumentException("额度／账期不合法");
      JSONArray packs = c.optJSONArray("packages");
      if (packs == null || packs.length() > 100) throw new IllegalArgumentException("流量包配置非法");
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < packs.length(); i++) {
        JSONObject p = packs.getJSONObject(i);
        if (p.optString("name").isBlank() || p.optLong("quota", -1) < -1)
          throw new IllegalArgumentException("流量包名称／额度非法");
        if (p.optString("id").isBlank() || !ids.add(p.getString("id")))
          throw new IllegalArgumentException("流量包 ID 为空或重复");
        JSONArray apps = p.optJSONArray("apps"), rules = p.optJSONArray("rules");
        if (apps == null || rules == null || apps.length() > 500 || rules.length() > 500)
          throw new IllegalArgumentException("流量包应用或规则清单非法");
        for (int j = 0; j < apps.length(); j++)
          if (!(apps.get(j) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("流量包应用名称非法");
        for (int j = 0; j < rules.length(); j++)
          if (!(rules.get(j) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("流量包规则非法");
        if (p.optBoolean("shared")) {
          String pool = p.optString("poolKey").trim();
          if (pool.isEmpty() || !pools.add(pool)) throw new IllegalArgumentException("共享池标识为空或重复");
        }
        if (p.optBoolean("requireBoth")
            && (p.optJSONArray("apps") == null
                || p.getJSONArray("apps").length() == 0
                || p.optJSONArray("rules") == null
                || p.getJSONArray("rules").length() == 0))
          throw new IllegalArgumentException("同时匹配需要应用和规则");
        // App/host evidence stays an estimate; it cannot grant a billing exemption.
        p.put("billingVerified", false);
        p.put("notifyWhenEmpty", p.optBoolean("notifyWhenEmpty", true))
            .put("stopWhenEmpty", p.optBoolean("stopWhenEmpty", false))
            .put("confirmWhenEmpty", p.optBoolean("confirmWhenEmpty", false));
        p.remove("officialBalance");
        p.remove("manualUsage");
        if (current != null) {
          JSONArray oldPacks = current.getJSONArray("packages");
          for (int j = 0; j < oldPacks.length(); j++) {
            JSONObject oldPack = oldPacks.getJSONObject(j);
            if (p.getString("id").equals(oldPack.optString("id"))
                && oldPack.has("manualUsage")
                && oldPack.optString("officialPackageId").equals(p.optString("officialPackageId")))
              p.put("manualUsage", oldPack.getJSONObject("manualUsage"));
            if (p.getString("id").equals(oldPack.optString("id"))
                && oldPack.has("officialBalance")
                && oldPack.optString("officialPackageId").equals(p.optString("officialPackageId")))
              p.put("officialBalance", oldPack.getJSONObject("officialBalance"));
          }
        }
      }
    }
    for (int i = 0; i < cards.length(); i++) {
      JSONObject card = cards.getJSONObject(i);
      String key = card.getString("identity");
      JSONObject before = previous.getJSONObject("cards").optJSONObject(key);
      JSONObject after = all.optJSONObject(key);
      if (before == null || after == null) continue;
      if (!before
          .getJSONArray("packages")
          .toString()
          .equals(after.getJSONArray("packages").toString()))
        changes.add(
            packageChanges(
                card.getInt("slot"),
                before.getJSONArray("packages"),
                after.getJSONArray("packages")));
      if (before.optLong("quota", -1) != after.optLong("quota", -1)
          || before.optLong("reserve") != after.optLong("reserve")
          || before.optInt("cycleDay", 1) != after.optInt("cycleDay", 1)
          || before.optBoolean("officialQuotaManaged") != after.optBoolean("officialQuotaManaged"))
        changes.add(
            String.format(
                Locale.ROOT,
                "卡 %d 通用设置：额度 %s → %s（%s）；停止阈值 %.1f → %.1f MB；账期开始日 %d → %d",
                card.getInt("slot"),
                quotaLabel(before.optLong("quota", -1)),
                quotaLabel(after.optLong("quota", -1)),
                after.optBoolean("officialQuotaManaged") ? "随官方" : "自定",
                before.optLong("reserve", 500000000) / 1e6,
                after.optLong("reserve", 500000000) / 1e6,
                before.optInt("cycleDay", 1),
                after.optInt("cycleDay", 1)));
    }
    if (!mode.equals(previous.optString("mode")))
      changes.add(
          "用卡策略："
              + policyLabel(previous.optString("mode"), previous.optInt("fixedSlot"))
              + " → "
              + policyLabel(mode, fixedSlot)
              + "；Wi-Fi 暂停期间只允许手动切卡，已禁止使用数据的卡不会成为切换目标");
    if (config.optBoolean("enabled") != previous.optBoolean("enabled"))
      changes.add(
          "通用流量限制："
              + onOff(previous.optBoolean("enabled"))
              + " → "
              + onOff(config.optBoolean("enabled"))
              + "（双卡共用开关）；累计限制与定向用尽设置按各自开关生效");
    if (config.optBoolean("pauseOnWifi", true) != previous.optBoolean("pauseOnWifi", true))
      changes.add(
          "Wi-Fi 暂停限制："
              + onOff(config.optBoolean("pauseOnWifi", true))
              + "；保存后按当前默认网络重新核验，原额度开关和数值不变");
    if (hours != previous.optInt("scheduleHours", 2)) changes.add("官方查询间隔设置为 " + hours + " 小时");
    if (limit != previous.optLong("budgetLimit")
        || config.optBoolean("budgetEnabled") != previous.optBoolean("budgetEnabled"))
      changes.add(
          "本机累计限制："
              + onOff(config.optBoolean("budgetEnabled"))
              + "；设置上限 "
              + gb(previous.optLong("budgetLimit"))
              + " → "
              + gb(limit)
              + "；累计已用 "
              + gb(store.data.optLong("budgetUsed"))
              + "，记录不清零"
              + (config.optBoolean("budgetEnabled") && store.data.optLong("budgetUsed") >= limit
                  ? "；新上限已触线，Wi-Fi 暂停结束后将停止数据"
                  : "；调整不会自动开启移动数据"));
    store.data.put("config", config);
    refreshWifiState();
    for (int i = 0; i < cards.length(); i++) {
      JSONObject card = cards.getJSONObject(i), c = cardConfig(card);
      JSONArray active = c.getJSONArray("packages"), catalog = c.optJSONArray("officialCatalog");
      for (int j = 0; j < active.length(); j++) {
        JSONObject p = active.getJSONObject(j);
        if (!p.has("officialPackageId")) continue;
        boolean matched = false;
        if (catalog != null)
          for (int k = 0; k < catalog.length(); k++) {
            JSONObject item = catalog.getJSONObject(k);
            if (p.optString("officialPackageId").equals(item.optString("officialPackageId"))) {
              matched = true;
              p.put("quota", item.optLong("quota", -1)).put("expires", item.optString("expires"));
              if (!p.has("officialBalance")) {
                JSONObject balance =
                    new JSONObject(item.getJSONObject("officialBalance").toString());
                balance.put("referenceAt", DirectedPackages.reference(this, card, p));
                p.put("officialBalance", balance);
              }
            }
          }
        if (!matched && !p.has("officialBalance")) p.remove("officialPackageId");
      }
    }
    store.event(
        "settings",
        "限制与流量包设置已保存",
        changes.isEmpty() ? "设置保存成功，没有变更限制数值或开关。" : String.join("\n", changes),
        "limits");
    refreshUsage();
  }

  static String quotaLabel(long n) {
    return n < 0 ? "未设置" : String.format(Locale.ROOT, "%.2f GiB", n / 1073741824d);
  }

  static String onOff(boolean enabled) {
    return enabled ? "开启" : "关闭";
  }

  static String policyLabel(String mode, int fixedSlot) {
    return switch (mode) {
      case "auto" -> "自动切卡";
      case "fixed" -> "固定使用卡 " + fixedSlot;
      default -> "额度不足停数据";
    };
  }

  static String packageChanges(int slot, JSONArray before, JSONArray after) throws Exception {
    Map<String, JSONObject> old = new LinkedHashMap<>();
    for (int i = 0; i < before.length(); i++)
      old.put(before.getJSONObject(i).optString("id"), before.getJSONObject(i));
    List<String> detail = new ArrayList<>();
    for (int i = 0; i < after.length(); i++) {
      JSONObject p = after.getJSONObject(i), prior = old.remove(p.optString("id"));
      String summary =
          "提醒"
              + onOff(p.optBoolean("notifyWhenEmpty", true))
              + "、用尽保护"
              + onOff(p.optBoolean("stopWhenEmpty"))
              + "、先断开确认"
              + onOff(p.optBoolean("confirmWhenEmpty") && p.optBoolean("stopWhenEmpty"));
      boolean changed = prior == null || !p.optString("name").equals(prior.optString("name"));
      for (String flag : new String[] {"notifyWhenEmpty", "stopWhenEmpty", "confirmWhenEmpty"})
        changed |=
            prior != null
                && p.optBoolean(flag, flag.equals("notifyWhenEmpty"))
                    != prior.optBoolean(flag, flag.equals("notifyWhenEmpty"));
      changed |=
          prior != null
              && (p.optLong("quota", -1) != prior.optLong("quota", -1)
                  || !p.getJSONArray("apps")
                      .toString()
                      .equals(prior.getJSONArray("apps").toString())
                  || !p.getJSONArray("rules")
                      .toString()
                      .equals(prior.getJSONArray("rules").toString())
                  || p.optInt("priority", 100) != prior.optInt("priority", 100)
                  || p.optBoolean("requireBoth") != prior.optBoolean("requireBoth"));
      if (changed)
        detail.add(
            (prior == null ? "新增" : "更新")
                + "「"
                + p.optString("name")
                + "」："
                + summary
                + "；额度 "
                + quotaLabel(p.optLong("quota", -1))
                + "，应用 "
                + p.getJSONArray("apps").length()
                + " 个、规则 "
                + p.getJSONArray("rules").length()
                + " 条");
    }
    for (JSONObject p : old.values()) detail.add("移除「" + p.optString("name") + "」");
    return "卡 "
        + slot
        + " 定向包配置：现有 "
        + after.length()
        + " 个"
        + (detail.isEmpty()
            ? "；套餐关联或其他配置已更新"
            : "\n" + String.join("\n", detail.subList(0, Math.min(10, detail.size()))))
        + (detail.size() > 10 ? "\n另有 " + (detail.size() - 10) + " 项变更，请在流量包中查看。" : "");
  }

  void calibrateDirected(JSONObject r) throws Exception {
    JSONObject card = card(r.getInt("slot")), c = cardConfig(card);
    if (!card.getString("identity").equals(r.getString("identity")))
      throw new SecurityException("SIM 已变化，请重新选择本卡流量包");
    JSONObject pack = null;
    JSONArray packs = c.getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++)
      if (packs.getJSONObject(i).optString("id").equals(r.getString("packageId")))
        pack = packs.getJSONObject(i);
    if (pack == null) throw new IllegalArgumentException("流量包已变化，请重新打开");
    long now = System.currentTimeMillis();
    String message;
    if (r.optBoolean("clear")) {
      pack.remove("manualUsage");
      message = "已恢复官方／自动估算口径";
    } else {
      long used = r.getLong("used");
      if (used < 0) throw new IllegalArgumentException("定向已用量不能为负");
      JSONObject u = usage.get(card.getString("identity"));
      if (u == null
          || u.optLong("cycleStart", -1) != DirectedPackages.cycle(c)
          || statsAt == 0
          || now - statsAt > 90000
          || !statsFault.isEmpty()) throw new SecurityException("本卡统计未就绪，暂不能设置定向已用量");
      if (com.dalong.dataguard.core.OfficialTotals.expired(pack.optString("expires"), now))
        throw new IllegalArgumentException("流量包已过期，不能设置本期已用量");
      pack.put(
          "manualUsage",
          new JSONObject()
              .put("used", used)
              .put("referenceAt", DirectedPackages.reference(this, card, pack))
              .put("cycleStart", DirectedPackages.cycle(c))
              .put("at", now));
      message =
          "手动已用设置为 "
              + String.format(Locale.ROOT, "%.3f GiB", used / 1073741824d)
              + "；从此刻追加本包识别增量，本账期有效，官方查询不会覆盖手动值";
    }
    store.event(
        "settings",
        "定向已用量已更新",
        "卡 "
            + card.getInt("slot")
            + "「"
            + pack.optString("name")
            + "」\n"
            + message
            + "。通用余额、本机累计及原始用量不变。",
        "directed:" + card.getInt("slot"));
  }

  /** The carrier's remaining figure typed by the user, for cards without an official query. */
  void manualGeneral(JSONObject r) throws Exception {
    JSONObject card = card(r.getInt("slot")), c = cardConfig(card);
    if (!card.getString("identity").equals(r.getString("identity")))
      throw new SecurityException("SIM 已变化，请重新选择");
    long left = r.getLong("remaining"), now = System.currentTimeMillis();
    if (left < 0) throw new IllegalArgumentException("剩余流量不能为负");
    long cycle = UsageMath.carrierCycleStart(now, c.optInt("cycleDay", 1));
    c.put("quota", left)
        .put("officialQuotaManaged", false)
        .put(
            "calibration",
            new JSONObject()
                .put("used", 0)
                .put("remaining", left)
                .put("rawAt", platform.usage(card.getInt("subId"), cycle, now).getLong("raw"))
                .put("asOf", now)
                .put("cycleStart", cycle)
                .put("fetchedAt", now)
                .put("source", "手动设置余量"));
    // Forget the last official figures, so even an unchanged balance replaces this manual value.
    c.remove("generalBaselineSignature");
    c.remove("officialCalibrationAt");
    refreshUsage();
    store.event(
        "settings",
        "卡 " + card.getInt("slot") + " 通用余量已手动设置",
        "通用剩余 " + OfficialClient.amount(left) + "，此后的蜂窝用量从中扣减。官方查询成功后以官方为准。",
        "general");
  }

  void calibrate(JSONObject r) throws Exception {
    JSONObject card = card(r.getInt("slot")), c = cardConfig(card);
    if (!card.getString("identity").equals(r.getString("identity")))
      throw new SecurityException("SIM 已变化，请重新核对本卡用量");
    long now = System.currentTimeMillis(), at = r.getLong("asOf"), used = r.getLong("used");
    long cycle = UsageMath.carrierCycleStart(now, c.optInt("cycleDay", 1));
    if (at > now || at < cycle || used < 0) throw new IllegalArgumentException("官方时间／用量不在当前账期");
    JSONObject u = platform.usage(card.getInt("subId"), cycle, at);
    JSONObject snap =
        new JSONObject()
            .put("used", used)
            .put("asOf", at)
            .put("rawAt", u.getLong("raw"))
            .put("cycleStart", cycle)
            .put("fetchedAt", now)
            .put("source", "手动官方快照");
    c.put("calibration", snap);
    JSONArray history = store.data.getJSONArray("snapshots");
    if (history.length() > 100) history.remove(0);
    history.put(snap);
    store.event("已校准本卡通用用量；此后新增蜂窝用量暂按通用计入，原始总账保持不变");
  }

  synchronized JSONObject health() throws Exception {
    return new JSONObject()
        .put("activeSlot", activeSlot)
        .put("dataOn", dataOn)
        .put("sampleAt", sampleAt)
        .put("blockedSubs", blockedSubs())
        .put("pauseOnWifi", store.config().optBoolean("pauseOnWifi", true))
        .put("wifiConnected", wifiConnected)
        .put("wifiPaused", wifiPaused)
        .put("fault", fault.isEmpty() ? statsFault : fault)
        .put("directedWarnings", store.data.optJSONArray("directedWarnings"))
        .put("directedPending", store.data.optJSONObject("directedPending"))
        .put("directedAlerts", store.data.optJSONArray("directedAlerts"))
        .put("outsideAlerts", store.data.optJSONObject("outsideAlerts"));
  }

  synchronized JSONObject status() throws Exception {
    JSONArray out = new JSONArray();
    for (int i = 0; i < cards.length(); i++) {
      JSONObject card = new JSONObject(cards.getJSONObject(i).toString()),
          config = cardConfig(card),
          u = usage.get(card.getString("identity"));
      if (card.optString("numberKey").isEmpty())
        card.put("numberKey", config.optString("numberKey"))
            .put("numberMasked", config.optString("numberMasked"))
            .put("numberSource", config.optString("numberSource"));
      card.put("config", config)
          .put("usage", u == null ? new JSONObject() : u)
          .put("remaining", remaining(config, u));
      out.put(card);
    }
    return new JSONObject()
        .put("cards", out)
        .put("config", store.config())
        .put("budgetUsed", store.data.optLong("budgetUsed"))
        .put("budgetSeeded", store.data.optBoolean("seeded"))
        .put("blockedSubs", blockedSubs())
        .put("pauseOnWifi", store.config().optBoolean("pauseOnWifi", true))
        .put("activeSlot", activeSlot)
        .put("defaultSlot", defaultSlot)
        .put("dataOn", dataOn)
        .put("wifiConnected", wifiConnected)
        .put("wifiPaused", wifiPaused)
        .put("sampleAt", sampleAt)
        .put("statsAt", statsAt)
        .put("fault", fault.isEmpty() ? statsFault : fault)
        .put("receipt", receipt)
        .put("recoveryRequired", store.data.optBoolean("recoveryRequired"))
        .put("hookAt", store.data.optLong("hookAt"))
        .put("hookCurrentBoot", hookReady())
        .put("phoneNetworkReady", store.data.optBoolean("hookNetworkReady"))
        .put("phoneWifiConnected", store.data.optBoolean("hookWifiConnected"))
        .put("events", store.data.getJSONArray("events"))
        .put("official", store.data.optJSONObject("official"))
        .put("officialResults", OfficialClient.associated(this))
        .put("officialByCard", OfficialClient.byCard(this))
        .put("officialQueries", store.data.optJSONObject("officialQueries"))
        .put("directedBalances", DirectedPackages.all(this))
        .put("directedWarnings", store.data.optJSONArray("directedWarnings"))
        .put("directedPending", store.data.optJSONObject("directedPending"))
        .put("directedAlerts", store.data.optJSONArray("directedAlerts"))
        .put("officialError", store.data.optString("officialError"))
        .put("officialProfiles", OfficialClient.profiles());
  }

  boolean hookReady() throws Exception {
    long age = System.currentTimeMillis() - store.data.optLong("hookAt");
    return age >= 0
        && age <= 30000
        && store.data.optBoolean("hookNetworkReady")
        && platform.bootId().equals(store.data.optString("hookBoot"));
  }
}
