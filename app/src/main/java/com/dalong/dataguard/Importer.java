package com.dalong.dataguard;

import java.net.URI;
import java.util.*;
import org.json.*;

final class Importer {
  static JSONObject importJson(JSONObject input, JSONObject status, int slot) throws Exception {
    if ("HBCS_SPEED_TEST_CONFIG".equals(input.optString("format"))) {
      JSONObject config = new JSONObject(status.getJSONObject("config").toString()), card = null;
      JSONArray cards = status.getJSONArray("cards");
      for (int i = 0; i < cards.length(); i++)
        if (cards.getJSONObject(i).getInt("slot") == slot) card = cards.getJSONObject(i);
      if (card == null) throw new IllegalArgumentException("卡槽不可用");
      JSONArray packs =
          config
              .getJSONObject("cards")
              .getJSONObject(card.getString("identity"))
              .getJSONArray("packages");
      Set<String> existing = new HashSet<>();
      for (int i = 0; i < packs.length(); i++)
        existing.add(packs.getJSONObject(i).optString("sourceKey"));
      JSONArray profiles = input.getJSONArray("profiles");
      for (int i = 0; i < profiles.length(); i++) {
        JSONObject profile = profiles.getJSONObject(i);
        JSONArray urls = profile.optJSONArray("downloadUrls"), rules = new JSONArray();
        if (urls != null)
          for (int j = 0; j < urls.length(); j++) {
            URI u = URI.create(urls.getString(j));
            if (u.getHost() != null && Set.of("http", "https").contains(u.getScheme()))
              rules.put(u.getHost() + u.getPath());
          }
        String key = Platform.hash(profile.optString("name") + rules.toString());
        if (!existing.add(key)) continue;
        packs.put(
            new JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("name", profile.optString("name", "候选定向包"))
                .put("quota", -1)
                .put("userSelected", true)
                .put("sourceKey", key)
                .put("apps", new JSONArray())
                .put("rules", rules)
                .put("priority", 100 + i)
                .put("requireBoth", false)
                .put("shared", false)
                .put("source", "导入规则 · 适用范围待核实"));
      }
      return RootClient.request(new JSONObject().put("action", "config").put("config", config));
    }
    JSONObject snapshot =
        input.has("flowSumList")
            ? OfficialClient.parseUnicom(input, System.currentTimeMillis())
            : new JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("carrier", "官方 JSON 导入")
                .put("summary", "响应已保留在私有存储；需适配器核实单位、包 ID 与账号对应关系");
    // Never put raw response tokens or phone numbers in UI status or the accounting journal.
    java.nio.file.Path privateDir =
        java.nio.file.Path.of("/data/user/0/com.dalong.dataguard/files");
    java.nio.file.Files.createDirectories(privateDir);
    java.nio.file.Files.write(
        privateDir.resolve("official-import.json"),
        input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return RootClient.request(
        new JSONObject().put("action", "officialImport").put("snapshot", snapshot));
  }
}
