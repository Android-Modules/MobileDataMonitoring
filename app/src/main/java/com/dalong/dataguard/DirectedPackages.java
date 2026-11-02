package com.dalong.dataguard;

import com.dalong.dataguard.core.DirectedLimit;
import com.dalong.dataguard.core.UsageMath;
import java.util.*;
import org.json.*;

/** Per-SIM directed balances, estimates and persistent exhaustion episodes. */
final class DirectedPackages {
  static long cycle(JSONObject c) {
    return UsageMath.carrierCycleStart(System.currentTimeMillis(), c.optInt("cycleDay", 1));
  }

  static Map<String, Long> references(GuardDaemon d, JSONObject card) throws Exception {
    JSONObject c = d.cardConfig(card), u = d.usage.get(card.getString("identity"));
    long cycle = cycle(c);
    JSONObject obs = c.optJSONObject("observations");
    JSONObject counts =
        obs != null && obs.optLong("cycleStart") == cycle ? obs.optJSONObject("packages") : null;
    JSONObject uids =
        u != null && u.optLong("cycleStart", -1) == cycle ? u.optJSONObject("uids") : null;
    List<JSONObject> ordered = new ArrayList<>();
    JSONArray all = c.getJSONArray("packages");
    for (int i = 0; i < all.length(); i++) ordered.add(all.getJSONObject(i));
    ordered.sort(Comparator.comparingInt(p -> p.optInt("priority", 100)));
    Set<Integer> seen = new HashSet<>();
    Map<String, Long> result = new HashMap<>();
    for (JSONObject pack : ordered) {
      long apps = 0;
      JSONArray names = pack.optJSONArray("apps");
      if (!pack.optBoolean("requireBoth") && uids != null && names != null)
        for (int j = 0; j < names.length(); j++) {
          try {
            int uid =
                d.platform
                    .context
                    .getPackageManager()
                    .getApplicationInfo(names.getString(j), 0)
                    .uid;
            if (seen.add(uid)) apps = Math.addExact(apps, uids.optLong("" + uid));
          } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
          }
        }
      JSONObject count = counts == null ? null : counts.optJSONObject(pack.optString("id"));
      long observed = count == null ? 0 : Math.addExact(count.optLong("rx"), count.optLong("tx"));
      result.put(pack.optString("id"), Math.max(observed, apps));
    }
    return result;
  }

  static long reference(GuardDaemon d, JSONObject card, JSONObject pack) throws Exception {
    return references(d, card).getOrDefault(pack.optString("id"), 0L);
  }

  static JSONObject balance(GuardDaemon d, JSONObject card, JSONObject pack) throws Exception {
    return balance(d, card, pack, reference(d, card, pack));
  }

  static JSONObject balance(GuardDaemon d, JSONObject card, JSONObject pack, long ref)
      throws Exception {
    JSONObject c = d.cardConfig(card), evidence = pack.optJSONObject("officialBalance");
    boolean current =
        evidence != null
            && evidence.optLong("cycleStart") == cycle(c)
            && !evidence.optBoolean("expired")
            && !com.dalong.dataguard.core.OfficialTotals.expired(
                pack.optString("expires"), System.currentTimeMillis());
    JSONObject manual = pack.optJSONObject("manualUsage");
    boolean manualCurrent =
        manual != null
            && manual.optLong("cycleStart", -1) == cycle(c)
            && !com.dalong.dataguard.core.OfficialTotals.expired(
                pack.optString("expires"), System.currentTimeMillis());
    DirectedLimit.Result r =
        manualCurrent
            ? DirectedLimit.manual(
                pack.optLong("quota", -1),
                ref,
                manual.optLong("used", -1),
                manual.optLong("referenceAt", -1))
            : DirectedLimit.evaluate(
                pack.has("officialPackageId") && !current ? -1 : pack.optLong("quota", -1),
                ref,
                current,
                current ? evidence.optLong("used", -1) : -1,
                current ? evidence.optLong("remaining", -1) : -1,
                current ? evidence.optLong("referenceAt") : 0);
    return new JSONObject()
        .put("id", pack.getString("id"))
        .put("name", pack.optString("name"))
        .put("used", r.used())
        .put("remaining", r.remaining())
        .put("exhausted", r.exhausted())
        .put("official", r.official())
        .put("manual", manualCurrent)
        .put("manualAt", manualCurrent ? manual.optLong("at") : 0)
        .put("officialAt", current ? evidence.optLong("at") : 0)
        .put("notify", pack.optBoolean("notifyWhenEmpty", true))
        .put("stop", pack.optBoolean("stopWhenEmpty", false));
  }

  static JSONObject all(GuardDaemon d) throws Exception {
    JSONObject result = new JSONObject();
    for (int i = 0; i < d.cards.length(); i++) {
      JSONObject card = d.cards.getJSONObject(i);
      JSONArray packages = d.cardConfig(card).getJSONArray("packages"), values = new JSONArray();
      Map<String, Long> references = references(d, card);
      for (int j = 0; j < packages.length(); j++) {
        JSONObject pack = packages.getJSONObject(j);
        values.put(balance(d, card, pack, references.getOrDefault(pack.optString("id"), 0L)));
      }
      result.put(card.getString("identity"), values);
    }
    return result;
  }

  static String key(JSONObject card, JSONObject c, JSONObject pack) throws Exception {
    return card.getString("identity") + ":" + pack.getString("id") + ":" + cycle(c);
  }

  static String episodeId(String key, JSONObject episode) throws Exception {
    return Platform.hash(key + ":" + episode.getLong("at"));
  }

  static boolean affected(JSONObject c, JSONObject pack, String foreground, long now) {
    if (!pack.optBoolean("requireBoth") && hasApp(pack, foreground)) return true;
    JSONObject obs = c.optJSONObject("observations");
    JSONObject counts =
        obs != null && obs.optLong("cycleStart") == cycle(c) ? obs.optJSONObject("packages") : null;
    JSONObject count = counts == null ? null : counts.optJSONObject(pack.optString("id"));
    long age = count == null ? -1 : now - count.optLong("at");
    return age >= 0 && age < 10000;
  }

  static boolean hasApp(JSONObject pack, String name) {
    JSONArray apps = pack.optJSONArray("apps");
    if (apps != null && !name.isEmpty())
      for (int i = 0; i < apps.length(); i++) if (name.equals(apps.optString(i))) return true;
    return false;
  }

  static boolean pendingAppForeground(JSONObject c, String id, String top) throws Exception {
    JSONArray packs = c.getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++) {
      JSONObject p = packs.getJSONObject(i);
      if (id.equals(p.optString("id"))) return hasApp(p, top);
    }
    return false;
  }

  // The first eligible package owns the flow, consistently with observation and UID accounting.
  static String affectedPackage(JSONObject c, String foreground) throws Exception {
    List<JSONObject> ordered = new ArrayList<>();
    JSONArray packs = c.getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++) ordered.add(packs.getJSONObject(i));
    ordered.sort(Comparator.comparingInt(p -> p.optInt("priority", 100)));
    long now = System.currentTimeMillis();
    for (JSONObject p : ordered) if (affected(c, p, foreground, now)) return p.getString("id");
    return "";
  }

  static boolean generalAvailable(GuardDaemon d, JSONObject card) throws Exception {
    JSONObject c = d.cardConfig(card);
    long left = d.remaining(c, d.usage.get(card.getString("identity")));
    return left < 0 || left > c.optLong("reserve", 500000000);
  }

  static DirectedLimit.Action action(
      GuardDaemon d,
      JSONObject card,
      JSONObject pack,
      JSONObject balance,
      JSONObject episode,
      String affectedId)
      throws Exception {
    return DirectedLimit.action(
        balance.optBoolean("exhausted"),
        pack.optBoolean("stopWhenEmpty"),
        card.optInt("slot") == d.activeSlot && pack.optString("id").equals(affectedId),
        generalAvailable(d, card),
        pack.optBoolean("confirmWhenEmpty"),
        episode != null && episode.optBoolean("acceptedGeneral"));
  }

  static String resumeReason(GuardDaemon d) throws Exception {
    if (d.wifiPaused) return "";
    JSONObject card = d.card(d.defaultSlot),
        c = d.cardConfig(card),
        episodes = d.store.data.optJSONObject("directedEpisodes");
    JSONObject pending = d.store.data.optJSONObject("directedPending");
    if (pending != null) {
      JSONObject episode =
          episodes == null ? null : episodes.optJSONObject(pending.optString("key"));
      boolean valid;
      try {
        prompt(d, pending.optString("id"));
        valid = true;
      } catch (IllegalArgumentException | SecurityException expired) {
        valid = false;
      }
      if (valid && (episode == null || !episode.optBoolean("acceptedGeneral")))
        return "请先选择继续使用通用流量或保持关闭";
    }
    JSONArray packs = c.getJSONArray("packages");
    Map<String, Long> references = references(d, card);
    String affectedId = affectedPackage(c, d.platform.foregroundPackage());
    for (int i = 0; i < packs.length(); i++) {
      JSONObject p = packs.getJSONObject(i);
      DirectedLimit.Action a =
          action(
              d,
              card,
              p,
              balance(d, card, p, references.getOrDefault(p.optString("id"), 0L)),
              episodes == null ? null : episodes.optJSONObject(key(card, c, p)),
              affectedId);
      if (a == DirectedLimit.Action.STOP) return "定向包已用尽且通用保护余量不足：" + p.optString("name");
      if (a == DirectedLimit.Action.CONFIRM) return "请先确认是否使用通用流量：" + p.optString("name");
    }
    return "";
  }

  static JSONObject prompt(GuardDaemon d, String id) throws Exception {
    if (d.wifiPaused) throw new IllegalArgumentException("Wi-Fi 联网中，额度限制已暂停，此确认已取消");
    JSONObject pending = d.store.data.optJSONObject("directedPending");
    if (pending == null
        || !id.equals(pending.optString("id"))
        || !DirectedLimit.confirmationCurrent(System.currentTimeMillis(), pending.optLong("at")))
      throw new IllegalArgumentException("此确认已失效，请重新进入对应应用");
    JSONObject card = pendingCard(d, pending);
    if (card == null) throw new SecurityException("卡身份或账期已变化");
    JSONObject c = d.cardConfig(card);
    if (pending.optLong("cycle") != cycle(c)) throw new SecurityException("卡身份或账期已变化");
    JSONArray packs = c.getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++) {
      JSONObject p = packs.getJSONObject(i);
      if (p.optString("id").equals(pending.optString("packageId"))
          && p.optBoolean("stopWhenEmpty")
          && p.optBoolean("confirmWhenEmpty")
          && balance(d, card, p).optBoolean("exhausted"))
        return new JSONObject()
            .put("name", p.optString("name"))
            .put("remaining", d.remaining(c, d.usage.get(card.getString("identity"))));
    }
    throw new IllegalArgumentException("流量包保护已变化，请重新查看");
  }

  static JSONObject pendingCard(GuardDaemon d, JSONObject pending) throws Exception {
    for (int i = 0; i < d.cards.length(); i++) {
      JSONObject card = d.cards.getJSONObject(i);
      if (pending.optString("identity").equals(card.optString("identity"))) return card;
    }
    return null;
  }

  static void confirm(GuardDaemon d, String id) throws Exception {
    prompt(d, id);
    d.validateResume();
    JSONObject pending = d.store.data.getJSONObject("directedPending");
    if (!generalAvailable(d, pendingCard(d, pending)))
      throw new SecurityException("通用保护余量不足，移动数据保持关闭");
    JSONObject episode =
        d.store.data.getJSONObject("directedEpisodes").getJSONObject(pending.getString("key"));
    episode.put("acceptedGeneral", true);
    try {
      d.resumeData();
    } catch (Exception e) {
      episode.remove("acceptedGeneral");
      throw e;
    }
    d.store.data.remove("directedPending");
    d.store.event("已确认使用通用流量 · " + pending.optString("name") + " · 本次用尽期间有效");
  }

  static void dismiss(GuardDaemon d, String id) throws Exception {
    JSONObject pending = d.store.data.optJSONObject("directedPending");
    if (pending != null && id.equals(pending.optString("id"))) {
      d.stop("已选择保持移动数据关闭");
      d.store.data.remove("directedPending");
      d.store.event("已选择保持移动数据关闭 · " + pending.optString("name"));
    }
  }

  static void enforce(GuardDaemon d) throws Exception {
    if (d.wifiPaused) {
      d.store.data.put("directedWarnings", new JSONArray()).put("directedAlerts", new JSONArray());
      d.store.data.remove("directedPending");
      return;
    }
    JSONObject episodes = d.store.data.optJSONObject("directedEpisodes");
    if (episodes == null) episodes = new JSONObject();
    JSONArray alerts = new JSONArray(), warnings = new JSONArray();
    Set<String> live = new HashSet<>();
    Set<String> confirmationKeys = new HashSet<>();
    String affectedId = null;
    JSONObject promptToOpen = null;
    long now = System.currentTimeMillis();
    for (int i = 0; i < d.cards.length(); i++) {
      JSONObject card = d.cards.getJSONObject(i), c = d.cardConfig(card);
      JSONArray packs = c.getJSONArray("packages");
      Map<String, Long> references = references(d, card);
      for (int j = 0; j < packs.length(); j++) {
        JSONObject p = packs.getJSONObject(j),
            b = balance(d, card, p, references.getOrDefault(p.optString("id"), 0L));
        String key = key(card, c, p);
        if (!b.optBoolean("exhausted")) continue;
        live.add(key);
        if (p.optBoolean("stopWhenEmpty") && p.optBoolean("confirmWhenEmpty"))
          confirmationKeys.add(key);
        JSONObject episode = episodes.optJSONObject(key);
        if (episode == null) episode = new JSONObject().put("at", now);
        boolean notify = p.optBoolean("notifyWhenEmpty", true);
        if (notify && !episode.optBoolean("notified")) {
          episode.put("notified", true);
          d.store.event(
              "定向包用尽提醒 · 卡 "
                  + card.getInt("slot")
                  + " · "
                  + p.optString("name")
                  + (b.optBoolean("official") ? " · 官方余量为 0" : " · 本地估算达到额度")
                  + "，继续使用可能消耗通用流量");
        }
        episodes.put(key, episode);
        String id = episodeId(key, episode);
        if (notify)
          alerts.put(
              new JSONObject()
                  .put("id", id)
                  .put("slot", card.getInt("slot"))
                  .put("name", p.optString("name"))
                  .put("at", episode.optLong("at"))
                  .put("official", b.optBoolean("official")));
        if (d.activeSlot == d.defaultSlot && card.optInt("slot") == d.defaultSlot && d.dataOn) {
          if (affectedId == null) affectedId = affectedPackage(c, d.platform.foregroundPackage());
          DirectedLimit.Action a = action(d, card, p, b, episode, affectedId);
          if (a != DirectedLimit.Action.NONE) {
            if (notify
                && DirectedLimit.warningDue(
                    now, episode.optLong("affectedAt"), episode.optLong("warningAt"))) {
              episode.put("warningAt", now);
              d.store.event(
                  "protection",
                  "定向包用尽，已安排连续提醒 3 次",
                  "卡 "
                      + card.getInt("slot")
                      + " · "
                      + p.optString("name")
                      + "\n定向剩余为 0，当前使用对应应用或命中规则。"
                      + (a == DirectedLimit.Action.CONFIRM
                          ? "已开启先断开确认，将停止数据并等待选择。"
                          : a == DirectedLimit.Action.STOP
                              ? "通用可用余量不足或未知，将停止数据。"
                              : "通用可用余量充足，本次继续联网；新增流量可能消耗通用余额。")
                      + "\n处理：设置 → 定向包用尽设置，可分别调整提醒、用尽保护和断开确认。",
                  "directed");
            }
            episode.put("affectedAt", now);
          }
          if (a == DirectedLimit.Action.STOP || a == DirectedLimit.Action.CONFIRM) {
            d.stop(
                "定向包已用尽："
                    + p.optString("name")
                    + (a == DirectedLimit.Action.CONFIRM ? "；等待确认使用通用流量" : "；通用保护余量不足或未知"));
            if (a == DirectedLimit.Action.CONFIRM) {
              promptToOpen =
                  new JSONObject()
                      .put("id", Platform.hash(id + ":" + now))
                      .put("key", key)
                      .put("identity", card.getString("identity"))
                      .put("cycle", cycle(c))
                      .put("packageId", p.getString("id"))
                      .put("name", p.optString("name"))
                      .put("shownAt", now)
                      .put("at", now);
              d.store.data.put("directedPending", promptToOpen);
            }
          }
        }
        if (!notify) episode.remove("warningAt");
        if (notify && DirectedLimit.warningVisible(now, episode.optLong("warningAt")))
          warnings.put(
              new JSONObject()
                  .put("id", Platform.hash(id + ":" + episode.getLong("warningAt")))
                  .put("slot", card.getInt("slot"))
                  .put("name", p.optString("name")));
      }
    }
    JSONObject kept = new JSONObject();
    for (String key : live) kept.put(key, episodes.getJSONObject(key));
    d.store
        .data
        .put("directedEpisodes", kept)
        .put("directedAlerts", alerts)
        .put("directedWarnings", warnings);
    JSONObject pending = d.store.data.optJSONObject("directedPending");
    JSONObject pendingEpisode =
        pending == null ? null : kept.optJSONObject(pending.optString("key"));
    if (pending != null
        && (!confirmationKeys.contains(pending.optString("key"))
            || (pendingEpisode != null && pendingEpisode.optBoolean("acceptedGeneral"))
            || !DirectedLimit.confirmationCurrent(now, pending.optLong("at")))) {
      d.store.data.remove("directedPending");
      pending = null;
    }
    if (pending != null && d.dataOn) d.stop("等待确认是否使用通用流量");
    // Startup ads may replace the underlying activity after the dialog opens.
    // Only bring it forward when the affected application is foreground again;
    // Home and unrelated apps remain usable while the pending choice stays off.
    JSONObject pendingCard = pending == null ? null : pendingCard(d, pending);
    if (pendingCard != null
        && promptToOpen == null
        && now - pending.optLong("shownAt") >= 2000
        && pendingAppForeground(
            d.cardConfig(pendingCard),
            pending.optString("packageId"),
            d.platform.foregroundPackage())) {
      pending.put("shownAt", now);
      promptToOpen = pending;
    }
    if (promptToOpen != null) {
      d.store.save();
      try {
        d.platform.directedPrompt(promptToOpen.getString("id"));
      } catch (Exception e) {
        d.store.event("确认页面未能打开，请进入流量守卫的设置页继续");
      }
    }
  }

  static void pause(GuardDaemon d) throws Exception {
    d.store.data.remove("directedPending");
    d.store.data.put("directedWarnings", new JSONArray()).put("directedAlerts", new JSONArray());
    JSONObject episodes = d.store.data.optJSONObject("directedEpisodes");
    if (episodes != null)
      for (Iterator<String> it = episodes.keys(); it.hasNext(); ) {
        JSONObject episode = episodes.getJSONObject(it.next());
        episode.remove("warningAt");
        episode.remove("affectedAt");
      }
  }
}
