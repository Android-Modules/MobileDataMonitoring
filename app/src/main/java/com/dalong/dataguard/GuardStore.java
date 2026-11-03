package com.dalong.dataguard;

import android.system.Os;
import java.io.*;
import java.nio.file.*;
import java.util.Iterator;
import org.json.*;

final class GuardStore {
  static final Path DIR = Path.of("/data/adb/mobile-data-guard");
  final Path file = DIR.resolve("ledger.json");
  JSONObject data;

  GuardStore() throws Exception {
    Files.createDirectories(DIR);
    Os.chmod(DIR.toString(), 0700);
    if (Files.exists(file)) data = new JSONObject(Platform.read(file));
    else
      data =
          new JSONObject()
              .put("version", 1)
              .put(
                  "config",
                  new JSONObject()
                      .put("cards", new JSONObject())
                      .put("mode", "stop")
                      .put("enabled", false)
                      .put("fixedSlot", 0)
                      .put("budgetEnabled", false)
                      .put("pauseOnWifi", true)
                      .put("budgetLimit", 1_000_000_000L)
                      .put("scheduleMinutes", 10)
                      .put("localSyncMinutes", 1))
              .put("budgetUsed", 0)
              .put("seeded", true)
              .put("counters", new JSONObject())
              .put("events", new JSONArray())
              .put("snapshots", new JSONArray());
    if (data.optInt("version") != 1) throw new IOException("账本版本不支持");
    if (!data.has("deviceGuard")) {
      // A ledger from a slot-locked build keeps its protections, now bound to SIM identity.
      String trusted = data.optString("testIdentity");
      data.put("deviceGuard", !trusted.isEmpty()).put("seeded", true);
      if (!trusted.isEmpty()) {
        JSONObject all = config().getJSONObject("cards");
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
          String key = it.next();
          if (!key.equals(trusted)) all.getJSONObject(key).put("dataForbidden", true);
        }
      }
      save();
    }
    if (!data.has("debugUsed")) {
      // Prior cumulative use cannot be told apart, so all of it stays charged to the debug budget.
      data.put("debugUsed", data.optBoolean("deviceGuard") ? data.optLong("budgetUsed") : 0);
      save();
    }
    if (!data.optBoolean("thresholdSettingsMigrated")) {
      config().put("budgetEnabled", config().optBoolean("budgetEnabled", false));
      JSONObject all = config().getJSONObject("cards");
      for (Iterator<String> it = all.keys(); it.hasNext(); ) {
        JSONObject c = all.getJSONObject(it.next()), official = c.optJSONObject("officialGeneral");
        c.put("officialQuotaManaged", true);
        if (official != null && official.optLong("total", -1) >= 0)
          c.put("quota", official.getLong("total"));
      }
      data.put("thresholdSettingsMigrated", true);
      save();
    }
    if (!data.optBoolean("packageSelectionMigrated")) {
      JSONObject cards = config().getJSONObject("cards");
      for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
        JSONObject card = cards.getJSONObject(it.next());
        JSONArray packs = card.optJSONArray("packages"),
            active = new JSONArray(),
            catalog = card.optJSONArray("packageCatalog");
        if (catalog == null) catalog = new JSONArray();
        if (packs != null)
          for (int i = 0; i < packs.length(); i++) {
            JSONObject p = packs.getJSONObject(i);
            if (!p.optBoolean("userSelected")
                && (p.has("sourceKey") || p.optString("source").startsWith("用户示例"))) catalog.put(p);
            else active.put(p);
          }
        card.put("packages", active).put("packageCatalog", catalog);
      }
      data.put("packageSelectionMigrated", true);
      save();
    }
    if (!data.optBoolean("neutralRuleLabelsMigrated")) {
      JSONObject cards = config().getJSONObject("cards");
      for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
        JSONObject card = cards.getJSONObject(it.next());
        for (String field : new String[] {"packages", "packageCatalog"}) {
          JSONArray packs = card.optJSONArray(field);
          if (packs == null) continue;
          for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.getJSONObject(i);
            if (pack.has("sourceKey")) pack.put("source", "候选规则 · 适用范围待核实");
          }
        }
      }
      data.put("neutralRuleLabelsMigrated", true);
      save();
    }
    if (!config().has("scheduleMinutes")) {
      config()
          .put("scheduleMinutes", scheduleMinutes(config().optInt("scheduleHours", 2)))
          .put("localSyncMinutes", config().optInt("localSyncMinutes", 1))
          .remove("scheduleHours");
      save();
    }
  }

  /** The old two-hour default becomes the new ten-minute one; a chosen interval is kept. */
  static int scheduleMinutes(int hours) {
    return hours == 2 ? 10 : Math.max(1, Math.min(168, hours)) * 60;
  }

  synchronized void save() throws Exception {
    Path temp = DIR.resolve("ledger.tmp");
    try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
      out.write(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    Os.chmod(temp.toString(), 0600);
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  synchronized void event(String message) throws Exception {
    String clean = cleanMessage(message),
        category =
            clean.contains("用尽")
                ? "protection"
                : clean.contains("查询") || clean.contains("官方") || clean.contains("校准")
                    ? "query"
                    : clean.contains("配置")
                            || clean.contains("设置")
                            || clean.contains("号码")
                            || clean.contains("流量包")
                        ? "settings"
                        : "protection";
    event(category, "", clean, "");
  }

  synchronized void event(String category, String title, String message, String destination)
      throws Exception {
    JSONArray old = data.getJSONArray("events"), next = new JSONArray();
    for (int i = Math.max(0, old.length() - 499); i < old.length(); i++) next.put(old.get(i));
    String clean = cleanMessage(message), cleanTitle = cleanMessage(title);
    String result = cleanTitle + clean;
    next.put(
        new JSONObject()
            .put("at", System.currentTimeMillis())
            .put("category", category)
            .put("title", cleanTitle)
            .put("destination", destination)
            .put(
                "level",
                result.contains("失败")
                        || result.contains("异常")
                        || result.contains("不可用")
                        || result.contains("未完成")
                        || result.contains("未应用")
                        || result.contains("拒绝")
                    ? "warning"
                    : "info")
            .put("message", clean));
    data.put("events", next);
    save();
  }

  synchronized void clearEvents() throws Exception {
    JSONArray previous = data.getJSONArray("events");
    data.put("events", new JSONArray());
    try {
      save();
    } catch (Exception e) {
      data.put("events", previous);
      throw e;
    }
  }

  static String cleanMessage(String s) {
    return s.replace("已从手机私有抓包缓存更新联通只读请求，登录态仅保存在手机 Root 私有目录", "运营商查询连接已更新")
        .replace("抓包", "查询连接")
        .replace("HttpCanary", "官方 App")
        .replace("累计测试预算", "额外用量上限")
        .replace("累计调试预算", "额外用量上限")
        .replace("调试", "保护")
        .replace("测试模式", "用卡限制")
        .replace("测试身份锁", "SIM 身份锁")
        .replace("测试", "保护")
        .replace("湖北移动规则", "候选规则")
        .replace("湖北配置", "导入规则")
        .replace("湖北", "")
        .replaceAll("(?<![0-9])(?:\\+?86)?(1[0-9]{2})[0-9]{4}([0-9]{4})(?![0-9])", "$1****$2")
        .replaceAll("(?i)(cookie|token|authorization|password|session)[=:][^\\s]+", "$1=[已隐藏]");
  }

  JSONObject config() throws JSONException {
    return data.getJSONObject("config");
  }
}
