package com.dalong.dataguard;

import com.dalong.dataguard.core.AppPackage;
import java.util.*;
import org.json.*;

/** Settings only: logins, usage counters and official snapshots never enter a backup file. */
final class SettingsBackup {
  static final String FORMAT = "DATAGUARD_SETTINGS";
  static final Set<String> GLOBAL =
      Set.of("mode", "enabled", "fixedSlot", "budgetEnabled", "budgetLimit", "pauseOnWifi", "scheduleHours");
  static final Set<String> CARD =
      Set.of("alias", "cycleDay", "reserve", "numberKey", "numberMasked", "numberSource", "province");
  static final Set<String> VOLATILE_PACK =
      Set.of("officialBalance", "manualUsage", "officialPackageId", "expires");

  private SettingsBackup() {}

  static JSONObject export(GuardDaemon daemon) throws Exception {
    JSONObject config = daemon.store.config(), global = new JSONObject(), cards = new JSONObject();
    for (String key : GLOBAL) if (config.has(key)) global.put(key, config.get(key));
    JSONObject all = config.getJSONObject("cards");
    for (Iterator<String> it = all.keys(); it.hasNext(); ) {
      String id = it.next();
      JSONObject c = all.getJSONObject(id), out = new JSONObject();
      for (String key : CARD) if (c.has(key)) out.put(key, c.get(key));
      if (!c.optBoolean("officialQuotaManaged", true)) out.put("quota", c.optLong("quota", -1));
      out.put("dataForbidden", c.optBoolean("dataForbidden"));
      JSONArray packs = new JSONArray(), source = c.getJSONArray("packages");
      for (int i = 0; i < source.length(); i++) {
        JSONObject p = new JSONObject(source.getJSONObject(i).toString());
        String official = p.optString("officialPackageId");
        for (String key : VOLATILE_PACK) p.remove(key);
        if (!official.isEmpty()) p.put("officialPackageId", official);
        packs.put(p);
      }
      cards.put(id, out.put("packages", packs));
    }
    return new JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("exportedAt", System.currentTimeMillis())
        .put("settings", global)
        .put("cards", cards);
  }

  static void check(JSONObject backup) throws Exception {
    if (!FORMAT.equals(backup.optString("format")) || backup.optInt("version") != 1)
      throw new IllegalArgumentException("不是流量守卫的设置备份，或版本不受支持");
    JSONObject cards = backup.optJSONObject("cards");
    if (backup.optJSONObject("settings") == null || cards == null || cards.length() > 16)
      throw new IllegalArgumentException("备份文件内容不完整");
    for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
      JSONObject c = cards.getJSONObject(it.next());
      JSONArray packs = c.optJSONArray("packages");
      if (packs == null) throw new IllegalArgumentException("备份文件内容不完整");
      for (int i = 0; i < packs.length(); i++) {
        JSONArray apps = packs.getJSONObject(i).optJSONArray("apps");
        for (int j = 0; apps != null && j < apps.length(); j++)
          if (!AppPackage.valid(apps.optString(j)))
            throw new IllegalArgumentException("备份里的应用包名无效");
      }
    }
  }

  /** The current config with the backup laid over it, for the normal validated save path. */
  static JSONObject merged(GuardDaemon daemon, JSONObject backup) throws Exception {
    JSONObject next = new JSONObject(daemon.store.config().toString());
    JSONObject global = backup.getJSONObject("settings"), cards = backup.getJSONObject("cards");
    for (String key : GLOBAL) if (global.has(key)) next.put(key, global.get(key));
    if ("fixed".equals(next.optString("mode"))
        && daemon.findCard(next.optInt("fixedSlot")) == null) next.put("mode", "stop");
    JSONObject all = next.getJSONObject("cards");
    for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
      String id = it.next();
      JSONObject in = cards.getJSONObject(id);
      JSONObject c = all.optJSONObject(id);
      if (c == null)
        all.put(
            id,
            c =
                new JSONObject()
                    .put("quota", -1)
                    .put("officialQuotaManaged", true)
                    .put("reserve", 500_000_000L));
      for (String key : CARD) if (in.has(key)) c.put(key, in.get(key));
      if (in.has("quota")) c.put("quota", in.getLong("quota")).put("officialQuotaManaged", false);
      c.put("packages", new JSONArray(in.getJSONArray("packages").toString()));
    }
    return next;
  }

  /** A restore can add a data ban but never lift one that protects a card here. */
  static void applyBans(GuardDaemon daemon, JSONObject backup) throws Exception {
    JSONObject cards = backup.getJSONObject("cards");
    JSONObject all = daemon.store.config().getJSONObject("cards");
    for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
      String id = it.next();
      if (cards.getJSONObject(id).optBoolean("dataForbidden") && all.has(id))
        all.getJSONObject(id).put("dataForbidden", true);
    }
  }

  static JSONObject preview(GuardDaemon daemon, JSONObject backup) throws Exception {
    check(backup);
    JSONObject cards = backup.getJSONObject("cards"), known = new JSONObject();
    for (int i = 0; i < daemon.cards.length(); i++)
      known.put(daemon.cards.getJSONObject(i).getString("identity"), true);
    int packs = 0, here = 0;
    for (Iterator<String> it = cards.keys(); it.hasNext(); ) {
      String id = it.next();
      packs += cards.getJSONObject(id).getJSONArray("packages").length();
      if (known.has(id)) here++;
    }
    return new JSONObject()
        .put("cards", cards.length())
        .put("here", here)
        .put("packs", packs)
        .put("exportedAt", backup.optLong("exportedAt"))
        .put("appVersion", backup.optString("appVersion"));
  }

  static void restore(GuardDaemon daemon, JSONObject backup) throws Exception {
    check(backup);
    daemon.saveConfig(merged(daemon, backup));
    applyBans(daemon, backup);
    daemon.syncBlockedSubs();
    daemon.store.event("settings", "设置已从备份导入", "用量记录、官方余额和登录信息未改变。", "limits");
  }
}
