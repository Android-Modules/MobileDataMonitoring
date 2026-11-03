package com.dalong.dataguard;

import com.dalong.dataguard.core.OfficialTotals;
import com.dalong.dataguard.core.OutsidePlan;
import com.dalong.dataguard.core.PhoneIdentity;
import com.dalong.dataguard.core.SmsBalance;
import com.dalong.dataguard.core.WebMargin;
import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Runs only the allowlisted balance query, with the login the official app itself holds. */
final class OfficialClient {
  static final String UNICOM =
      "https://mxx.client.10010.com/servicequerybusiness/operationservice/queryOcsPackageFlowLeftContentRevisedInJune";
  static final Path PROFILE = GuardStore.DIR.resolve("official-profiles.json");
  static final AtomicBoolean running = new AtomicBoolean();

  static JSONArray load() throws Exception {
    return Files.exists(PROFILE) ? new JSONArray(Platform.read(PROFILE)) : new JSONArray();
  }

  static JSONArray profiles() {
    JSONArray safe = new JSONArray();
    try {
      JSONArray list = load();
      for (int i = 0; i < list.length(); i++) {
        JSONObject p = list.getJSONObject(i);
        safe.put(
            new JSONObject()
                .put("id", p.getString("id"))
                .put("label", p.optString("label"))
                .put("enabled", p.optBoolean("enabled")));
      }
    } catch (Exception ignored) {
    }
    return safe;
  }

  /** The balance page's fixed form; identity comes only from the app's login cookies. */
  static final String UNICOM_FORM =
      "duanlianjieabc=&channelCode=&serviceType=&saleChannel=&externalSources=&contactCode="
          + "&ticket=&ticketPhone=&ticketChannel=&mobile1=&language=chinese";

  static JSONObject unicomProfile(String cookie, String agent, int user) throws Exception {
    return new JSONObject()
        .put("id", "unicom")
        .put("label", "中国联通余量")
        .put("enabled", true)
        .put("url", UNICOM)
        .put("method", "POST")
        .put("user", user)
        .put(
            "headers",
            new JSONObject()
                .put("Cookie", cookie)
                .put("User-Agent", agent)
                .put("Content-Type", "application/x-www-form-urlencoded")
                .put("Accept", "application/json, text/plain, */*")
                .put("Accept-Language", "zh-CN,zh;q=0.9")
                .put("Origin", "https://imgxx.client.10010.com")
                .put("Referer", "https://imgxx.client.10010.com/")
                .put("X-Requested-With", UnicomSession.PACKAGE))
        .put("body", UNICOM_FORM);
  }

  /** Each number keeps its own connection, so two Unicom cards never overwrite each other. */
  static void saveProfile(JSONObject profile) throws Exception {
    JSONArray old = load(), next = new JSONArray();
    for (int i = 0; i < old.length(); i++) {
      JSONObject p = old.getJSONObject(i);
      if (!p.optString("accountKey").equals(profile.optString("accountKey"))
          && !p.optString("accountKey").isEmpty()) next.put(p);
    }
    next.put(profile);
    Files.write(PROFILE, next.toString().getBytes(StandardCharsets.UTF_8));
    android.system.Os.chmod(PROFILE.toString(), 0600);
  }

  static void unicomSession(GuardDaemon daemon, JSONObject request) throws Exception {
    String cookie = request.getString("cookie");
    if (cookie.length() > 16000 || !cookie.contains("ecs_token="))
      throw new IllegalArgumentException("联通登录信息不完整");
    JSONObject profile =
        unicomProfile(cookie, request.optString("userAgent"), request.optInt("sourceUser"));
    if (!running.compareAndSet(false, true)) return;
    new Thread(
            () -> {
              try {
                JSONObject result = query(profile);
                synchronized (daemon) {
                  String bound = boundIdentity(daemon, result, "unicom");
                  if (bound.isEmpty()) {
                    // Another account's login is neither applied nor retained.
                    daemon.store.event(
                        "query", "联通账号未关联", "联通 App 登录的号码与本机 SIM 不一致，未保存连接。", "");
                    return;
                  }
                  saveProfile(profile.put("accountKey", result.getString("accountKey")));
                  // Page navigation repeats the login; only a changed balance is worth a line.
                  JSONObject previous = byCard(daemon).optJSONObject(bound);
                  boolean changed =
                      previous == null
                          || !result
                              .getJSONObject("aggregates")
                              .toString()
                              .equals(previous.getJSONObject("aggregates").toString());
                  remember(daemon, "unicom", result);
                  daemon.store.data.put("officialError", "");
                  queryState(
                      daemon,
                      bound,
                      "updated",
                      updatedText(daemon, bound, result),
                      System.currentTimeMillis());
                  if (changed) logUpdated(daemon, bound, result);
                }
              } catch (Throwable e) {
                try {
                  synchronized (daemon) {
                    daemon.store.event(
                        "query", "联通连接未更新", "原因：" + GuardDaemon.safeMessage(e), "");
                  }
                } catch (Exception ignored) {
                }
              } finally {
                running.set(false);
              }
            },
            "official-session")
        .start();
  }

  static boolean hasProfile(GuardDaemon daemon, String identity) throws Exception {
    JSONArray list = load();
    JSONObject accounts = daemon.store.data.optJSONObject("officialAccounts");
    for (int i = 0; i < list.length() && accounts != null; i++) {
      JSONObject snapshot =
          accounts.optJSONObject("unicom:" + list.getJSONObject(i).optString("accountKey"));
      if (snapshot != null && identity.equals(boundIdentity(daemon, snapshot, "unicom")))
        return true;
    }
    return false;
  }

  /** Without a live connection the app is opened once; its login then arrives by itself. */
  static void refreshOrOpen(GuardDaemon daemon, JSONObject card) throws Exception {
    String identity = card.getString("identity");
    if (hasProfile(daemon, identity)) refresh(daemon, identity);
    else openUnicom(daemon, identity, 0);
  }

  static void openUnicom(GuardDaemon daemon, String identity, int user) throws Exception {
    queryState(
        daemon,
        identity,
        "waiting",
        "已打开中国联通，请使用本卡号码登录并进入余量查询",
        System.currentTimeMillis());
    daemon.store.save();
    daemon.platform.openPackage(UnicomSession.PACKAGE, user);
  }

  static void scheduled(GuardDaemon daemon) {
    try {
      boolean due;
      synchronized (daemon) {
        due =
            System.currentTimeMillis() - daemon.store.data.optLong("officialAttemptAt")
                >= GuardDaemon.officialMinutes(daemon.store.config()) * 60000L;
      }
      if (due) refresh(daemon);
    } catch (Exception ignored) {
    }
    try {
      HiddenPage.scheduled(daemon);
    } catch (Exception ignored) {
    }
  }

  static void refresh(GuardDaemon daemon) throws Exception {
    refresh(daemon, "");
  }

  static void refresh(GuardDaemon daemon, String expectedIdentity) throws Exception {
    JSONArray profiles = load(), list = new JSONArray();
    synchronized (daemon) {
      JSONObject accounts = daemon.store.data.optJSONObject("officialAccounts");
      for (int i = 0; i < profiles.length(); i++) {
        JSONObject profile = profiles.getJSONObject(i);
        JSONObject snapshot =
            accounts == null
                ? null
                : accounts.optJSONObject("unicom:" + profile.optString("accountKey"));
        String bound = snapshot == null ? "" : boundIdentity(daemon, snapshot, "unicom");
        if (profile.optBoolean("enabled")
            && !bound.isEmpty()
            && (expectedIdentity.isEmpty() || expectedIdentity.equals(bound))) list.put(profile);
      }
    }
    if (list.length() == 0) throw new IllegalArgumentException("请在官方 App 使用本卡号码登录；当前没有已关联的查询账号");
    if (!running.compareAndSet(false, true)) throw new IllegalArgumentException("官方查询进行中");
    if (!expectedIdentity.isEmpty())
      synchronized (daemon) {
        queryState(
            daemon, expectedIdentity, "waiting", "正在查询本卡官方余量", System.currentTimeMillis());
      }
    new Thread(
            () -> {
              try {
                synchronized (daemon) {
                  // Counted before the checks: an unusable route must not retry and log every minute.
                  daemon.store.data.put("officialAttemptAt", System.currentTimeMillis());
                  daemon.store.save();
                  if (!daemon.queryRouteUsable()
                      || !daemon.fault.isEmpty()
                      || !daemon.store.data.optBoolean("seeded"))
                    throw new SecurityException("当前数据卡保护未就绪，暂停查询");
                }
                for (int i = 0; i < list.length(); i++) {
                  JSONObject p = list.getJSONObject(i);
                  if (!p.optBoolean("enabled")) continue;
                  JSONObject result;
                  try {
                    result = query(p);
                  } catch (IllegalArgumentException | IOException first) {
                    // Just after boot or a network change the service can answer once with a busy
                    // page; only a second refusal is taken as an expired login.
                    Thread.sleep(3000);
                    result = query(p);
                  }
                  synchronized (daemon) {
                    String bound = boundIdentity(daemon, result, p.getString("id"));
                    if (bound.isEmpty()
                        || (!expectedIdentity.isEmpty() && !expectedIdentity.equals(bound)))
                      throw new SecurityException("官方登录号码未与本卡对应，未应用查询结果");
                    remember(daemon, p.getString("id"), result);
                    daemon.store.data.put("officialError", "");
                    queryState(
                        daemon,
                        bound,
                        "updated",
                        updatedText(daemon, bound, result),
                        System.currentTimeMillis());
                    logUpdated(daemon, bound, result);
                  }
                }
              } catch (Throwable e) {
                try {
                  synchronized (daemon) {
                    daemon.store.data.put("officialError", GuardDaemon.safeMessage(e));
                    if (!expectedIdentity.isEmpty())
                      queryState(
                          daemon,
                          expectedIdentity,
                          "error",
                          "官方查询未完成：" + GuardDaemon.safeMessage(e),
                          System.currentTimeMillis());
                    daemon.store.event("官方查询未应用：" + GuardDaemon.safeMessage(e));
                    // An expired login needs the app once; user-started updates can do that now.
                    if (!expectedIdentity.isEmpty()
                        && GuardDaemon.safeMessage(e).contains("登录"))
                      openUnicom(daemon, expectedIdentity, list.getJSONObject(0).optInt("user"));
                  }
                } catch (Exception ignored) {
                }
              } finally {
                running.set(false);
              }
            },
            "official-query")
        .start();
  }

  static JSONObject query(JSONObject p) throws Exception {
    String endpoint = p.getString("url");
    if (!UNICOM.equals(endpoint)) throw new SecurityException("请求不在已验证的余量查询白名单");
    if (!"POST".equals(p.getString("method"))) throw new SecurityException("查询方法不匹配");
    HttpsURLConnection c = (HttpsURLConnection) new URL(endpoint).openConnection();
    c.setConnectTimeout(8000);
    c.setReadTimeout(8000);
    c.setInstanceFollowRedirects(false);
    c.setRequestMethod("POST");
    JSONObject headers = p.getJSONObject("headers");
    Set<String> allowed =
        Set.of(
            "cookie",
            "user-agent",
            "content-type",
            "origin",
            "referer",
            "x-requested-with",
            "accept",
            "accept-language");
    for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
      String key = it.next();
      if (allowed.contains(key.toLowerCase(Locale.ROOT)))
        c.setRequestProperty(key, headers.getString(key));
    }
    c.setRequestProperty("Accept-Encoding", "identity");
    byte[] body = p.optString("body").getBytes(StandardCharsets.UTF_8);
    c.setDoOutput(true);
    c.setFixedLengthStreamingMode(body.length);
    try {
      try (OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
      if (c.getResponseCode() != 200)
        throw new IllegalArgumentException("官方查询 HTTP " + c.getResponseCode() + "；需在官方 App 刷新登录态");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = c.getInputStream()) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > 2_000_000) throw new IOException("官方响应超出限制");
          out.write(buf, 0, n);
        }
      }
      JSONObject parsed;
      try {
        parsed =
            parseUnicom(
                new JSONObject(out.toString(StandardCharsets.UTF_8)), System.currentTimeMillis());
      } catch (JSONException notABalance) {
        // An expired login answers with an ordinary page instead of the balance document.
        throw new IllegalArgumentException("联通登录态失效或查询返回异常");
      }
      // The response only carries an encrypted number; the login cookie names the account.
      String number = PhoneIdentity.fromCookie(headers.optString("Cookie"), "c_mobile");
      if (!number.isEmpty())
        parsed.put("accountKey", Platform.hash(number)).put("plainNumberAccount", true);
      Path privateResponse = GuardStore.DIR.resolve("official-unicom-response.json");
      Files.write(privateResponse, out.toByteArray());
      android.system.Os.chmod(privateResponse.toString(), 0600);
      return parsed;
    } finally {
      c.disconnect();
    }
  }

  static long mib(String n) {
    if (!n.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("官方流量单位／数值无法识别");
    return new BigDecimal(n)
        .multiply(BigDecimal.valueOf(1048576))
        .setScale(0, RoundingMode.CEILING)
        .longValueExact();
  }

  static void mobileSnapshot(GuardDaemon daemon, JSONObject request) throws Exception {
    long at = request.getLong("fetchedAt"), now = System.currentTimeMillis();
    if (!"mobile-margin-query".equals(request.optString("page"))
        || at > now + 300000
        || now - at > 300000) throw new IllegalArgumentException("移动官方页面快照已过期");
    JSONObject parsed = parseMobile(request.getJSONObject("snapshot"), at);
    parsed.put("sourceUser", request.optInt("sourceUser"));
    String identity = boundIdentity(daemon, parsed, "mobile");
    JSONObject previous = byCard(daemon).optJSONObject(identity);
    JSONObject queries = daemon.store.data.optJSONObject("officialQueries");
    boolean requested =
        queries != null
            && queries.optJSONObject(identity) != null
            && "waiting".equals(queries.getJSONObject(identity).optString("state"));
    if (!identity.isEmpty()) {
      JSONObject cardConfig = daemon.store.config().getJSONObject("cards").getJSONObject(identity);
      int sourceUser = request.optInt("sourceUser");
      if (cardConfig.has("officialUser") && sourceUser != cardConfig.optInt("officialUser"))
        throw new SecurityException("官方登录账号与本卡对应的应用不一致，未应用余量");
      if (sourceUser == 0 || sourceUser == 999) cardConfig.put("officialUser", sourceUser);
      JSONObject route = request.optJSONObject("route");
      if (route != null
          && Platform.MOBILE_APP.equals(route.optString("package"))
          && containsMarginRoute(route.optJSONObject("extras"))) {
        JSONObject routes = daemon.store.data.optJSONObject("mobileRoutes");
        if (routes == null) routes = new JSONObject();
        routes.put(identity, route);
        daemon.store.data.put("mobileRoutes", routes);
      }
      queryState(daemon, identity, "updated", updatedText(daemon, identity, parsed), at);
      daemon.store.data.put("officialError", "");
    }
    remember(daemon, "mobile", parsed);
    if (requested
        || previous == null
        || !parsed
            .getJSONObject("aggregates")
            .toString()
            .equals(previous.getJSONObject("aggregates").toString())
        || !parsed
            .getJSONArray("packages")
            .toString()
            .equals(previous.getJSONArray("packages").toString())) {
      if (identity.isEmpty()) daemon.store.event("已获取移动官方结果，登录号码尚未与本机 SIM 对应，未应用");
      else logUpdated(daemon, identity, parsed);
    }
    if (!identity.isEmpty()) syncOutside(daemon, identity, parsed);
  }

  /** Figures read from the web service hall by the app; applied only to the card they name. */
  static void mobileWeb(GuardDaemon daemon, JSONObject r) throws Exception {
    JSONObject card = daemon.findCardByIdentity(r.getString("identity"));
    if (!"mobile".equals(card.optString("carrierCode")))
      throw new IllegalArgumentException("网上营业厅查询只用于中国移动号码");
    String masked = card.optString("numberMasked");
    if (masked.isEmpty()) masked = daemon.cardConfig(card).optString("numberMasked");
    if (!WebMargin.sameNumber(r.optString("telephone"), masked))
      throw new SecurityException("网上营业厅登录号码与卡 " + card.optInt("slot") + " 不一致，未应用余量");
    for (int i = 0; i < daemon.cards.length(); i++) {
      JSONObject other = daemon.cards.getJSONObject(i);
      String m = other.optString("numberMasked");
      if (m.isEmpty()) m = daemon.cardConfig(other).optString("numberMasked");
      if (other != card && "mobile".equals(other.optString("carrierCode")) && masked.equals(m))
        throw new SecurityException("两张卡的脱敏号码相同，无法区分网上营业厅账号");
    }
    JSONObject flow = r.getJSONObject("flow");
    JSONObject general = flow.getJSONObject("planRemian"),
        other = flow.optJSONObject("otherRemian"),
        outside = flow.optJSONObject("outUsedInfo");
    long now = System.currentTimeMillis();
    SmsSync.apply(
        daemon,
        card.getString("identity"),
        new SmsBalance.Result(
            web(general),
            other == null ? null : web(other),
            outside == null
                ? 0
                : Math.max(0, WebMargin.bytes(outside.optString("usedNum"), outside.optString("unit"))),
            WebMargin.cutoff(r.optString("oprTime"), now),
            List.of()),
        "web");
    // From now on this card's unattended updates go through the web service hall first.
    daemon
        .store
        .data
        .put("webCard", new JSONObject().put("identity", card.getString("identity")).put("at", now));
  }

  static void mobileWebLost(GuardDaemon daemon, JSONObject r) throws Exception {
    JSONObject card = daemon.store.data.optJSONObject("webCard");
    if (card == null || !card.optString("identity").equals(r.optString("identity"))) return;
    daemon.store.data.remove("webCard");
    queryState(
        daemon,
        card.optString("identity"),
        "error",
        slotLabel(daemon, card.optString("identity")) + "网上营业厅登录已失效，请在校准页重新登录",
        System.currentTimeMillis());
    daemon.store.event(
        "query",
        "网上营业厅登录已失效",
        "后台查询改用官方 App 隐藏页或短信。需要时在校准 → 网上营业厅重新登录。",
        "");
  }

  static SmsBalance.Usage web(JSONObject row) {
    return WebMargin.usage(
        row.optString("usedNum"),
        row.optString("remainNum"),
        row.optString("sumNum"),
        row.optString("unit"));
  }

  /** Paid traffic beyond the plan, normalized from the carrier's separate list. */
  static JSONArray outside(JSONObject snapshot) throws Exception {
    JSONArray raw = snapshot.optJSONArray("outsidePlan"), rows = new JSONArray();
    if (raw == null) return rows;
    for (int i = 0; i < raw.length(); i++) {
      JSONObject row = raw.optJSONObject(i);
      if (row == null) continue;
      long used = -1, fen = OutsidePlan.fen(row.optString("charging"));
      try {
        if ("03".equals(row.optString("unit"))) used = mib(row.optString("usageAmount"));
      } catch (IllegalArgumentException unreadable) {
        // Keep the row: a charge with an unreadable amount must still be visible.
      }
      if (used == 0 && fen == 0) continue;
      rows.put(
          new JSONObject()
              .put("name", row.optString("outPlanTitle", "套外流量"))
              .put("used", used)
              .put("fen", fen));
    }
    return rows;
  }

  static String outsideSummary(JSONArray rows) throws Exception {
    StringJoiner text = new StringJoiner("；");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      text.add(
          row.optString("name")
              + " 已用 "
              + amount(row.optLong("used", -1))
              + " · 费用 "
              + OutsidePlan.yuan(row.optLong("fen", -1)));
    }
    return text.toString();
  }

  /** Alerts once per changed amount; a cycle without paid traffic clears the card's alert. */
  static void syncOutside(GuardDaemon daemon, String identity, JSONObject parsed) throws Exception {
    JSONArray rows = outside(parsed);
    JSONObject alerts = daemon.store.data.optJSONObject("outsideAlerts");
    if (alerts == null) alerts = new JSONObject();
    if (rows.length() == 0) {
      alerts.remove(identity);
      daemon.store.data.put("outsideAlerts", alerts);
      return;
    }
    long used = 0, fen = 0;
    for (int i = 0; i < rows.length(); i++) {
      used += Math.max(0, rows.getJSONObject(i).optLong("used"));
      fen += Math.max(0, rows.getJSONObject(i).optLong("fen"));
    }
    JSONObject previous = alerts.optJSONObject(identity);
    // App and SMS round differently; only real growth, or a new charge, is news.
    if (previous != null
        && used < previous.optLong("used") + 52428800L
        && fen <= previous.optLong("fen")) return;
    String label = slotLabel(daemon, identity), text = outsideSummary(rows);
    int slot = daemon.findCardByIdentity(identity).getInt("slot");
    alerts.put(
        identity,
        new JSONObject()
            .put("id", Platform.hash(identity + used + fen))
            .put("slot", slot)
            .put("used", used)
            .put("fen", fen)
            .put("text", label + text));
    daemon.store.data.put("outsideAlerts", alerts);
    daemon.store.event(
        "query",
        label + "产生套外流量",
        text + "\n这部分已超出套餐，费用以运营商账单为准。\n处理：总览查看套外用量；如不需要，可在官方 App 关闭自动加油包。",
        "");
  }

  static String amount(long n) {
    if (n < 0) return "未提供";
    return n >= 1073741824L
        ? String.format(Locale.ROOT, "%.2f GiB", n / 1073741824d)
        : String.format(Locale.ROOT, "%.2f MiB", n / 1048576d);
  }

  /** The same figures as the balance header, so the log can be compared with the official page. */
  static String balanceSummary(JSONObject parsed) throws Exception {
    JSONObject general = parsed.getJSONObject("aggregates").optJSONObject("1");
    List<OfficialTotals.Package> directed = new ArrayList<>();
    JSONArray rows = parsed.optJSONArray("packages");
    long now = System.currentTimeMillis();
    if (rows != null)
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        if ("2".equals(row.optString("type")))
          directed.add(
              new OfficialTotals.Package(
                  row.optString("id", "row-" + i),
                  row.optLong("total", -1),
                  row.optLong("used", -1),
                  row.optLong("remaining", -1),
                  row.optBoolean("expired")
                      || OfficialTotals.expired(row.optString("expires"), now)));
      }
    OfficialTotals.Summary s = OfficialTotals.sum(directed);
    return "通用剩余 "
        + amount(general == null ? -1 : general.optLong("remaining", -1))
        + " · 定向剩余 "
        + (s.count() == 0
            ? "无有效套餐"
            : amount(s.unknownRemaining() == s.count() ? -1 : s.remaining())
                + (s.unknownRemaining() > 0 ? "（另有 " + s.unknownRemaining() + " 个包未提供）" : ""));
  }

  static String updatedText(GuardDaemon daemon, String identity, JSONObject parsed)
      throws Exception {
    // A system toast shows two lines; the log entry keeps the note on unreported packages.
    return slotLabel(daemon, identity)
        + "官方余量已更新 · "
        + balanceSummary(parsed).replaceAll("（.*）", "");
  }

  static String slotLabel(GuardDaemon daemon, String identity) throws Exception {
    for (int i = 0; i < daemon.cards.length(); i++) {
      JSONObject card = daemon.cards.getJSONObject(i);
      if (identity.equals(card.optString("identity"))) return "卡 " + card.getInt("slot") + " ";
    }
    return "本卡";
  }

  static void logUpdated(GuardDaemon daemon, String identity, JSONObject parsed) throws Exception {
    JSONObject general = parsed.getJSONObject("aggregates").optJSONObject("1");
    daemon.store.event(
        "query",
        slotLabel(daemon, identity) + "官方余量已更新",
        balanceSummary(parsed)
            + (general == null ? "" : "\n通用已用 " + amount(general.optLong("used", -1)))
            + "\n来源：运营商官方余量，计费以运营商账单为准。",
        "");
  }

  static boolean containsMarginRoute(JSONObject values) {
    if (values == null) return false;
    for (Iterator<String> it = values.keys(); it.hasNext(); ) {
      Object value = values.opt(it.next());
      if (value instanceof JSONObject nested && containsMarginRoute(nested)) return true;
      if (value instanceof String s) {
        try {
          String decoded = URLDecoder.decode(s, StandardCharsets.UTF_8);
          URI uri = URI.create(decoded);
          if ("https".equals(uri.getScheme())
              && "wx.10086.cn".equals(uri.getHost())
              && "/cmcc-service/app-pages/main/marginQuery".equals(uri.getPath())) return true;
        } catch (Exception ignored) {
        }
      }
    }
    return false;
  }

  static void expireQueries(GuardDaemon daemon) throws Exception {
    JSONObject queries = daemon.store.data.optJSONObject("officialQueries");
    if (queries == null) return;
    long now = System.currentTimeMillis();
    for (Iterator<String> it = queries.keys(); it.hasNext(); ) {
      JSONObject q = queries.getJSONObject(it.next());
      if ("waiting".equals(q.optString("state")) && now - q.optLong("at") > 90000) {
        q.put("state", "error").put("message", "未收到完整余量，请确认本卡官方应用已登录，并进入余量查询后重试").put("at", now);
        daemon.store.event("官方余量更新未完成，请确认本卡登录及余量查询页面");
      }
    }
  }

  static void queryState(GuardDaemon daemon, String identity, String state, String message, long at)
      throws Exception {
    JSONObject all = daemon.store.data.optJSONObject("officialQueries");
    if (all == null) all = new JSONObject();
    all.put(identity, new JSONObject().put("state", state).put("message", message).put("at", at));
    daemon.store.data.put("officialQueries", all);
  }

  /** The balance page this card last opened; routes learned in any other app are ignored. */
  static JSONObject mobileRoute(JSONObject routes, String identity) {
    JSONObject route = routes == null ? null : routes.optJSONObject(identity);
    return route != null && Platform.MOBILE_APP.equals(route.optString("package")) ? route : null;
  }

  /** A card signed in on the web service hall is read there first, then through the app. */
  static void openMobile(GuardDaemon daemon, JSONObject card, boolean skipWeb) throws Exception {
    String id = card.getString("identity");
    JSONObject webCard = daemon.store.data.optJSONObject("webCard");
    if (skipWeb || webCard == null || !id.equals(webCard.optString("identity"))) {
      appOrSms(daemon, card);
      return;
    }
    queryState(daemon, id, "waiting", "正在通过网上营业厅读取本卡余量", System.currentTimeMillis());
    daemon.store.save();
    new Thread(
            () -> {
              boolean done = false;
              try {
                done = HiddenPage.web(daemon, id);
              } catch (Exception unsupported) {
                // The official app route below still applies.
              }
              if (!done)
                synchronized (daemon) {
                  try {
                    appOrSms(daemon, daemon.findCardByIdentity(id));
                  } catch (Exception e) {
                    try {
                      queryState(
                          daemon,
                          id,
                          "error",
                          "未能读取官方余量：" + GuardDaemon.safeMessage(e),
                          System.currentTimeMillis());
                    } catch (Exception ignored) {
                    }
                  }
                }
            },
            "mobile-web")
        .start();
  }

  /** Outside Jiangsu the Jiangsu app cannot sign in; the SMS query is the remaining route. */
  static void appOrSms(GuardDaemon daemon, JSONObject card) throws Exception {
    if (daemon.jiangsuApp(card)) openMobileApp(daemon, card, false);
    else if (daemon.cardConfig(card).optBoolean("smsSync"))
      SmsSync.refresh(daemon, card.getString("identity"));
    else throw new IllegalArgumentException("本卡不是江苏移动号码，请登录网上营业厅，或发短信查询");
  }

  static void openMobileApp(GuardDaemon daemon, JSONObject card, boolean visible) throws Exception {
    String id = card.getString("identity");
    if (!daemon.jiangsuApp(card)) throw new IllegalArgumentException("中国移动江苏 App 只支持江苏移动号码");
    JSONObject c = daemon.cardConfig(card);
    int user = c.optInt("officialUser", 0);
    JSONObject route = mobileRoute(daemon.store.data.optJSONObject("mobileRoutes"), id);
    if (route != null && !visible) {
      queryState(daemon, id, "waiting", "正在后台读取本卡官方余量", System.currentTimeMillis());
      daemon.store.save();
      HiddenPage.refresh(daemon, id, user, route);
      return;
    }
    queryState(
        daemon,
        id,
        "waiting",
        route != null
            ? "已打开中国移动江苏 App 余量查询，页面加载后自动读取"
            : "已打开中国移动江苏 App，请点击流量进入余量查询",
        System.currentTimeMillis());
    daemon.store.save();
    try {
      daemon.platform.openMobile(user, route);
    } catch (Exception e) {
      queryState(daemon, id, "error", "未能打开本卡官方应用，请从桌面进入余量查询", System.currentTimeMillis());
      daemon.store.save();
      throw e;
    }
  }

  static String boundIdentity(GuardDaemon daemon, JSONObject snapshot, String carrier)
      throws Exception {
    boolean plain =
        "mobile-20085-v1".equals(snapshot.optString("schema"))
            || snapshot.optBoolean("plainNumberAccount");
    String match = "";
    for (int i = 0; i < daemon.cards.length(); i++) {
      JSONObject c = daemon.cards.getJSONObject(i), config = daemon.cardConfig(c);
      String key = c.optString("numberKey");
      if (key.isEmpty()) key = config.optString("numberKey");
      if (PhoneIdentity.matches(
          c.optString("carrierCode"), key, carrier, snapshot.optString("accountKey"), plain)) {
        if (!match.isEmpty()) return "";
        match = c.getString("identity");
      }
    }
    return match;
  }

  static JSONObject associated(GuardDaemon daemon) throws Exception {
    JSONObject source = daemon.store.data.optJSONObject("officialResults"), out = new JSONObject();
    if (source == null) return out;
    for (Iterator<String> it = source.keys(); it.hasNext(); ) {
      String carrier = it.next();
      JSONObject copy = new JSONObject(source.getJSONObject(carrier).toString());
      String id = boundIdentity(daemon, copy, carrier);
      copy.put("boundIdentity", id).put("accountIdentityVerified", !id.isEmpty());
      out.put(carrier, copy);
    }
    return out;
  }

  static void remember(GuardDaemon daemon, String carrier, JSONObject result) throws Exception {
    JSONObject all = daemon.store.data.optJSONObject("officialResults"),
        accounts = daemon.store.data.optJSONObject("officialAccounts");
    if (all == null) all = new JSONObject();
    if (accounts == null) accounts = new JSONObject();
    for (Iterator<String> it = all.keys(); it.hasNext(); ) {
      String type = it.next();
      JSONObject old = all.getJSONObject(type);
      accounts.put(type + ":" + old.optString("accountKey"), old);
    }
    accounts.put(carrier + ":" + result.getString("accountKey"), result);
    all.put(carrier, result);
    daemon
        .store
        .data
        .put("officialAccounts", accounts)
        .put("officialResults", all)
        .put("official", result);
    syncPackages(daemon);
  }

  static JSONObject evidence(
      GuardDaemon d, JSONObject card, JSONObject pack, JSONObject row, long at) throws Exception {
    long cycle =
        com.dalong.dataguard.core.UsageMath.carrierCycleStart(
            at, d.cardConfig(card).optInt("cycleDay", 1));
    JSONObject previous = pack.optJSONObject("officialBalance");
    if (previous != null
        && previous.optLong("cycleStart", -1) == cycle
        && previous.optBoolean("expired") == row.optBoolean("expired")
        && previous.optLong("used", -1) == row.optLong("used", -1)
        && previous.optLong("total", -1) == row.optLong("total", -1)
        && previous.optLong("remaining", -1) == row.optLong("remaining", -1))
      return new JSONObject(previous.toString()).put("at", at);
    return new JSONObject()
        .put("used", row.optLong("used", -1))
        .put("remaining", row.optLong("remaining", -1))
        .put("total", row.optLong("total", -1))
        .put("at", at)
        .put("cycleStart", cycle)
        .put("referenceAt", DirectedPackages.reference(d, card, pack))
        .put("expired", row.optBoolean("expired"));
  }

  static void syncPackages(GuardDaemon daemon) throws Exception {
    JSONObject all = byCard(daemon);
    for (int i = 0; i < daemon.cards.length(); i++) {
      JSONObject card = daemon.cards.getJSONObject(i),
          snapshot = all.optJSONObject(card.getString("identity"));
      if (snapshot == null) continue;
      JSONObject c = daemon.cardConfig(card);
      long at = snapshot.optLong("fetchedAt");
      JSONObject general = snapshot.getJSONObject("aggregates").optJSONObject("1");
      if (general != null) daemon.syncGeneralCalibration(card, general, at);
      if (c.optLong("officialSyncedAt") == at) continue;
      if (general != null) {
        c.put("officialGeneral", new JSONObject(general.toString()).put("at", at));
        if (general.optLong("total", -1) >= 0)
          c.put("quota", general.getLong("total")).put("officialQuotaManaged", true);
      }
      JSONArray catalog = new JSONArray(),
          rows = snapshot.optJSONArray("packages"),
          active = c.getJSONArray("packages");
      if (rows != null)
        for (int j = 0; j < rows.length(); j++) {
          JSONObject row = rows.getJSONObject(j);
          if (!"2".equals(row.optString("type"))) continue;
          String key = row.getString("id");
          JSONObject p =
              new JSONObject()
                  .put("id", "official-" + Platform.hash(key))
                  .put("officialPackageId", key)
                  .put("name", row.getString("name"))
                  .put("quota", row.optLong("total", -1))
                  .put("apps", new JSONArray())
                  .put("rules", new JSONArray())
                  .put("priority", 100)
                  .put("requireBoth", false)
                  .put("notifyWhenEmpty", true)
                  .put("stopWhenEmpty", false)
                  .put("source", "本卡官方套餐")
                  .put("expires", row.optString("expires"));
          p.put("officialBalance", evidence(daemon, card, p, row, at));
          catalog.put(p);
          for (int k = 0; k < active.length(); k++) {
            JSONObject selected = active.getJSONObject(k);
            if (key.equals(selected.optString("officialPackageId")))
              selected
                  .put("quota", row.optLong("total", -1))
                  .put("expires", row.optString("expires"))
                  .put("officialBalance", evidence(daemon, card, selected, row, at));
          }
        }
      c.put("officialCatalog", catalog).put("officialSyncedAt", at);
    }
  }

  static JSONObject byCard(GuardDaemon daemon) throws Exception {
    JSONObject out = new JSONObject(),
        accounts = daemon.store.data.optJSONObject("officialAccounts");
    List<JSONObject> values = new ArrayList<>();
    if (accounts != null)
      for (Iterator<String> it = accounts.keys(); it.hasNext(); )
        values.add(accounts.getJSONObject(it.next()));
    JSONObject latest = daemon.store.data.optJSONObject("officialResults");
    if (latest != null)
      for (Iterator<String> it = latest.keys(); it.hasNext(); )
        values.add(latest.getJSONObject(it.next()));
    for (JSONObject value : values) {
      String carrier =
          value.optString("schema").startsWith("mobile-")
              ? "mobile"
              : value.optString("schema").startsWith("unicom-") ? "unicom" : "";
      String id = boundIdentity(daemon, value, carrier);
      if (id.isEmpty()) continue;
      JSONObject previous = out.optJSONObject(id);
      if (previous == null || previous.optLong("fetchedAt") <= value.optLong("fetchedAt"))
        out.put(
            id,
            new JSONObject(value.toString())
                .put("boundIdentity", id)
                .put("accountIdentityVerified", true)
                .put("outside", outside(value)));
    }
    return out;
  }

  static long mobileValue(JSONObject row, String field) throws Exception {
    if (!"03".equals(row.getString("unit"))) throw new IllegalArgumentException("移动流量单位变化，暂停解析");
    String value = row.getString(field);
    return "N".equals(value) ? -1 : mib(value);
  }

  static JSONObject parseMobile(JSONObject raw, long fetched) throws Exception {
    String account = raw.getString("account");
    if (!account.matches("1[0-9]{10}")) throw new IllegalArgumentException("移动账号身份缺失，不能应用快照");
    JSONObject flow = raw.getJSONObject("flow"), aggregates = new JSONObject();
    JSONArray groups = flow.getJSONArray("planRemianFlowRes"), packages = new JSONArray();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < groups.length(); i++) {
      JSONObject g = groups.getJSONObject(i);
      String type = g.getString("flowtype"),
          mapped =
              switch (type) {
                case "0" -> "1";
                case "2" -> "2";
                default -> "mobile-" + type;
              };
      if (aggregates.has(mapped)) throw new IllegalArgumentException("移动汇总类型重复，暂停解析");
      aggregates.put(
          mapped,
          new JSONObject()
              .put("used", mobileValue(g, "flowUsdNum"))
              .put("remaining", mobileValue(g, "flowRemainNum"))
              .put("total", mobileValue(g, "flowSumNum")));
      JSONArray plans = g.getJSONArray("planRemianFlowInfoRes");
      for (int j = 0; j < plans.length(); j++) {
        JSONObject plan = plans.getJSONObject(j);
        JSONArray details = plan.getJSONArray("flowInfoRes");
        for (int k = 0; k < details.length(); k++) {
          JSONObject d = details.getJSONObject(k);
          String id =
              plan.getString("planID")
                  + ":"
                  + d.getString("flowInstanceId")
                  + ":"
                  + d.getString("flowID");
          if (!seen.add(id)) continue;
          packages.put(
              new JSONObject()
                  .put("id", id)
                  .put("name", plan.getString("planName") + " · " + d.getString("flowName"))
                  .put("type", mapped)
                  .put("used", mobileValue(d, "usedRes"))
                  .put("remaining", mobileValue(d, "remainRes"))
                  .put("total", mobileValue(d, "totalRes"))
                  .put("expires", d.optString("flowExpireDate"))
                  .put("termMode", plan.optString("isMultiTerm"))
                  .put("expired", "1".equals(plan.optString("expireFlag"))));
        }
      }
    }
    if (!aggregates.has("1") || packages.length() == 0)
      throw new IllegalArgumentException("移动余量结构不完整");
    return new JSONObject()
        .put("carrier", "中国移动")
        .put("schema", "mobile-20085-v1")
        .put("accountKey", Platform.hash(account))
        .put("accountIdentityVerified", false)
        .put("fetchedAt", fetched)
        .put("statisticalTimeVerified", false)
        .put("sourceMode", "official-page")
        .put("aggregates", aggregates)
        .put("packages", packages)
        .put("outsidePlan", flow.optJSONArray("outPlanInfoRes"))
        .put(
            "summary",
            "已从登录的官方余量页读取通用和定向套餐；N 保留为未知／不限量，套餐外单列。需打开官方余量页更新，独立后台查询尚未接通；账号及计费截至时间未核实，不自动校准");
  }

  static JSONObject parseUnicom(JSONObject raw, long fetched) throws Exception {
    if (!"0000".equals(raw.optString("code"))) throw new IllegalArgumentException("联通登录态失效或查询返回异常");
    if (!Set.of("GB", "MB").contains(raw.optString("canuseFlowAllUnit")))
      throw new IllegalArgumentException("联通流量单位变化，暂停校准");
    String mobile = raw.optString("mobile");
    if (mobile.isBlank() || mobile.length() > 1024) throw new IllegalArgumentException("查询账号身份缺失");
    long asOf =
        LocalDateTime.parse(
                raw.getString("time"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    if (asOf > fetched + 300000 || fetched - asOf > 86400000)
      throw new IllegalArgumentException("官方统计时间异常或已过期");
    JSONObject aggregates = new JSONObject();
    JSONArray sums = raw.getJSONArray("flowSumList");
    for (int i = 0; i < sums.length(); i++) {
      JSONObject s = sums.getJSONObject(i);
      if (!"3".equals(s.optString("elemtype"))) continue;
      String type = s.optString("flowtype");
      long used = mib(s.getString("xusedvalue")), left = mib(s.getString("xcanusevalue"));
      aggregates.put(
          type,
          new JSONObject()
              .put("used", used)
              .put("remaining", left)
              .put("total", Math.addExact(used, left)));
    }
    if (!aggregates.has("1")) throw new IllegalArgumentException("官方响应缺少通用流量");
    Set<String> shared = new HashSet<>();
    JSONObject pool = raw.optJSONObject("shareData");
    if (pool != null) {
      JSONArray a = pool.optJSONArray("details");
      if (a != null)
        for (int i = 0; i < a.length(); i++) shared.add(packageKey(a.getJSONObject(i)));
    }
    JSONArray packages = new JSONArray();
    Set<String> seen = new HashSet<>();
    JSONArray resources = raw.getJSONArray("resources");
    for (int i = 0; i < resources.length(); i++) {
      JSONObject row = resources.getJSONObject(i);
      if (!"flow".equals(row.optString("type"))) continue;
      JSONArray details = row.getJSONArray("details");
      for (int j = 0; j < details.length(); j++) {
        JSONObject d = details.getJSONObject(j);
        if (!"3".equals(d.optString("elemType"))) continue;
        String key = packageKey(d);
        if (!seen.add(key)) continue;
        packages.put(
            new JSONObject()
                .put("id", key)
                .put("name", d.optString("feePolicyName", d.optString("addUpItemName")))
                .put("type", d.optString("flowType", "unknown"))
                .put("used", mib(d.getString("use")))
                .put("remaining", mib(d.getString("remain")))
                .put("total", mib(d.getString("total")))
                .put("shared", shared.contains(key))
                .put("expires", d.optString("endDate1")));
      }
    }
    String normalized = PhoneIdentity.normalize(mobile);
    return new JSONObject()
        .put("carrier", "中国联通")
        .put("schema", "unicom-flow-v1")
        .put("accountKey", Platform.hash(normalized.isEmpty() ? mobile : normalized))
        .put("plainNumberAccount", !normalized.isEmpty())
        .put("accountIdentityVerified", false)
        .put("fetchedAt", fetched)
        .put("queryAt", asOf)
        .put("statisticalTimeVerified", false)
        .put("aggregates", aggregates)
        .put("packages", packages)
        .put("summary", "通用、专属与共享流量分别解析；共享成员不重复累加。账号未绑定实体卡，服务端时间尚未证实为计费截至时间，暂不自动校准");
  }

  static String packageKey(JSONObject p) {
    return p.optString("feePolicyId")
        + ":"
        + p.optString("addupItemCode")
        + ":"
        + p.optString("flowType");
  }
}
