package com.dalong.dataguard;

import com.dalong.dataguard.core.SmsBalance;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Mobile's own balance SMS: works without login or screen, and for a card carrying no data. */
final class SmsSync {
  static final String NUMBER = "10086";
  static final String TEXT = "CXLL";
  static final long WAIT_MS = 90000;
  static final AtomicBoolean running = new AtomicBoolean();

  private SmsSync() {}

  static void refresh(GuardDaemon daemon, String identity) throws Exception {
    JSONObject card = daemon.findCardByIdentity(identity);
    if (!"mobile".equals(card.optString("carrierCode")))
      throw new IllegalArgumentException("短信查询目前只支持中国移动");
    if (!running.compareAndSet(false, true)) throw new IllegalArgumentException("短信查询进行中");
    int sub = card.getInt("subId");
    OfficialClient.queryState(
        daemon, identity, "waiting", "已发送查询短信，等待 10086 回复", System.currentTimeMillis());
    new Thread(
            () -> {
              try {
                long since = System.currentTimeMillis();
                daemon.platform.sendSms(sub, NUMBER, TEXT);
                long deadline = since + WAIT_MS;
                SmsBalance.Result result = null;
                while (result == null && System.currentTimeMillis() < deadline) {
                  Thread.sleep(3000);
                  result =
                      SmsBalance.parseMobile(
                          daemon.platform.smsFrom(NUMBER, sub, since), System.currentTimeMillis());
                }
                synchronized (daemon) {
                  if (result == null)
                    OfficialClient.queryState(
                        daemon, identity, "error", "未收到可识别的 10086 回复", System.currentTimeMillis());
                  else apply(daemon, identity, result);
                }
              } catch (Throwable e) {
                try {
                  synchronized (daemon) {
                    OfficialClient.queryState(
                        daemon,
                        identity,
                        "error",
                        "短信查询未完成：" + GuardDaemon.safeMessage(e),
                        System.currentTimeMillis());
                  }
                } catch (Exception ignored) {
                }
              } finally {
                running.set(false);
              }
            },
            "sms-query")
        .start();
  }

  static JSONObject aggregate(SmsBalance.Usage usage) throws Exception {
    return new JSONObject()
        .put("total", usage.total())
        .put("used", usage.used())
        .put("remaining", usage.remaining());
  }

  /** Lays the SMS figures over the last full report, keeping package ids bound to its entries. */
  static void apply(GuardDaemon daemon, String identity, SmsBalance.Result r) throws Exception {
    JSONObject card = daemon.findCardByIdentity(identity), c = daemon.cardConfig(card);
    JSONObject previous = OfficialClient.byCard(daemon).optJSONObject(identity);
    String account = card.optString("numberKey");
    if (account.isEmpty()) account = c.optString("numberKey");
    if (previous == null && account.isEmpty())
      throw new IllegalArgumentException("请先关联本卡号码");
    JSONObject out =
        previous == null
            ? new JSONObject()
                .put("carrier", "中国移动（短信）")
                .put("schema", "mobile-sms-v1")
                .put("accountKey", account)
                .put("aggregates", new JSONObject())
                .put("packages", new JSONArray())
            : new JSONObject(previous.toString());
    long now = System.currentTimeMillis();
    JSONObject groups = out.getJSONObject("aggregates");
    groups.put("1", aggregate(r.general()));
    if (r.directed() != null && !groups.has("2")) groups.put("2", aggregate(r.directed()));
    JSONArray packages = out.getJSONArray("packages");
    for (SmsBalance.Item item : r.items()) {
      JSONObject match = null;
      for (int i = 0; i < packages.length() && match == null; i++) {
        JSONObject p = packages.getJSONObject(i);
        if (p.optString("name").startsWith(item.name())) match = p;
      }
      if (match == null) {
        match =
            new JSONObject()
                .put("id", "sms:" + Platform.hash(item.name()))
                .put("name", item.name())
                .put("type", item.directed() ? "2" : "1")
                .put("total", item.used() + item.remaining());
        packages.put(match);
      }
      match.put("used", item.used());
      if (match.optLong("total", -1) >= 0) match.put("remaining", item.remaining());
    }
    JSONArray outside = out.optJSONArray("outsidePlan");
    if (r.outside() <= 0) out.remove("outsidePlan");
    else {
      JSONObject row = outside == null ? null : outside.optJSONObject(0);
      if (row == null) {
        row = new JSONObject().put("outPlanTitle", "套餐外流量").put("unit", "03").put("charging", "");
        out.put("outsidePlan", new JSONArray().put(row));
      }
      row.put("usageAmount", String.valueOf(r.outside() / 1048576d));
    }
    out.put("plainNumberAccount", true)
        .put("fetchedAt", now)
        .put("queryAt", r.cutoffAt())
        .put("statisticalTimeVerified", true)
        .put("sourceMode", "sms");
    out.remove("boundIdentity");
    OfficialClient.remember(daemon, "mobile", out);
    OfficialClient.queryState(
        daemon, identity, "updated", OfficialClient.updatedText(daemon, identity, out), now);
    OfficialClient.logUpdated(daemon, identity, out);
    OfficialClient.syncOutside(daemon, identity, out);
  }
}
