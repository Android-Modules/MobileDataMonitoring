package com.dalong.dataguard;

import com.dalong.dataguard.core.LoginLife;
import java.time.ZoneId;
import java.util.Iterator;
import org.json.*;

/** Per card, how long each saved carrier login has held. Kept by the root worker only. */
final class Logins {
  static final String WEB = "web", UNICOM = "unicom", APP = "app";

  private Logins() {}

  /** Names the login as a whole, e.g. "联通 App 登录", for reading straight into a sentence. */
  static String label(String kind) {
    return switch (kind) {
      case WEB -> "网上营业厅登录";
      case UNICOM -> "联通 App 登录";
      default -> "江苏移动 App 登录";
    };
  }

  static LoginLife.State read(JSONObject o) {
    return o == null
        ? null
        : new LoginLife.State(
            o.optLong("loginAt"), o.optLong("okAt"), o.optLong("lostAt"), o.optLong("life"));
  }

  static LoginLife.State get(GuardDaemon d, String identity, String kind) {
    JSONObject all = d.store.data.optJSONObject("logins");
    JSONObject card = all == null ? null : all.optJSONObject(identity);
    return read(card == null ? null : card.optJSONObject(kind));
  }

  static void put(GuardDaemon d, String identity, String kind, LoginLife.State s) throws Exception {
    JSONObject all = d.store.data.optJSONObject("logins");
    if (all == null) d.store.data.put("logins", all = new JSONObject());
    JSONObject card = all.optJSONObject(identity);
    if (card == null) all.put(identity, card = new JSONObject());
    if (s == null) card.remove(kind);
    else
      card.put(
          kind,
          new JSONObject()
              .put("loginAt", s.loginAt())
              .put("okAt", s.okAt())
              .put("lostAt", s.lostAt())
              .put("life", s.life()));
    if (card.length() == 0) all.remove(identity);
    d.store.save();
  }

  /** {@code login} is when the login in use began, or 0 for the one already known. */
  static void ok(GuardDaemon d, String identity, String kind, long login) throws Exception {
    synchronized (d) {
      put(d, identity, kind, LoginLife.ok(get(d, identity, kind), System.currentTimeMillis(), login));
    }
  }

  static void lost(GuardDaemon d, String identity, String kind) throws Exception {
    synchronized (d) {
      put(d, identity, kind, LoginLife.lost(get(d, identity, kind), System.currentTimeMillis()));
    }
  }

  /** Clears a logout the app no longer believes, without moving the last real confirmation. */
  static void restore(GuardDaemon d, String identity, String kind) throws Exception {
    synchronized (d) {
      LoginLife.State before = get(d, identity, kind);
      LoginLife.State now = LoginLife.restored(before);
      if (now != before) put(d, identity, kind, now);
    }
  }

  static void forget(GuardDaemon d, String identity, String kind) throws Exception {
    synchronized (d) {
      if (get(d, identity, kind) != null) put(d, identity, kind, null);
    }
  }

  /** The logins of the cards now inserted, worded for the notification and the query page. */
  static JSONArray all(GuardDaemon d) throws Exception {
    JSONArray out = new JSONArray();
    JSONObject all = d.store.data.optJSONObject("logins");
    if (all == null) return out;
    long now = System.currentTimeMillis();
    ZoneId zone = ZoneId.systemDefault();
    for (int i = 0; i < d.cards.length(); i++) {
      JSONObject card = d.cards.getJSONObject(i);
      JSONObject kinds = all.optJSONObject(card.getString("identity"));
      if (kinds == null) continue;
      for (Iterator<String> it = kinds.keys(); it.hasNext(); ) {
        String kind = it.next();
        LoginLife.State s = read(kinds.optJSONObject(kind));
        // Jiangsu app expiry is quiet unless this card asked to be told. Web and Unicom always show.
        boolean notify = !APP.equals(kind) || d.cardConfig(card).optBoolean("notifyAppLogin");
        out.put(
            new JSONObject()
                .put("slot", card.getInt("slot"))
                .put("identity", card.getString("identity"))
                .put("kind", kind)
                .put("label", label(kind))
                .put("notify", notify)
                .put("loginAt", s.loginAt())
                .put("lostAt", s.lostAt())
                .put("expectedAt", LoginLife.expectedEnd(s))
                .put("near", LoginLife.nearEnd(s, now))
                .put("text", LoginLife.describe(s, now, zone)));
      }
    }
    return out;
  }
}
