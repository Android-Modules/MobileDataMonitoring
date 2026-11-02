package com.dalong.dataguard.core;

import java.util.Locale;
import java.util.Map;

/** SIM identity, never inferred from a phone number or the registered roaming network. */
public final class CarrierIdentity {
  private static final Map<String, String> OPERATORS =
      Map.ofEntries(
          Map.entry("46000", "mobile"),
          Map.entry("46002", "mobile"),
          Map.entry("46004", "mobile"),
          Map.entry("46007", "mobile"),
          Map.entry("46008", "mobile"),
          Map.entry("46020", "mobile"),
          Map.entry("46001", "unicom"),
          Map.entry("46006", "unicom"),
          Map.entry("46009", "unicom"),
          Map.entry("46003", "telecom"),
          Map.entry("46005", "telecom"),
          Map.entry("46011", "telecom"),
          Map.entry("46015", "broadnet"));

  public record Result(String code, String label, String source) {}

  public static Result resolve(int carrierId, String mccMnc, String name) {
    String id =
        switch (carrierId) {
          case 1435 -> "mobile";
          case 1436 -> "unicom";
          case 2237 -> "telecom";
          default -> "";
        };
    String network = OPERATORS.getOrDefault(mccMnc == null ? "" : mccMnc, "");
    if (!id.isEmpty()) {
      if (!network.isEmpty() && !network.equals(id)) return result("unknown", "系统标识不一致");
      return result(id, "SIM Carrier ID");
    }
    // A resolved different carrier can be an MVNO; do not relabel it as its host network.
    if (carrierId > 0) return result("other", "系统识别为其他运营商");
    if (!network.isEmpty()) return result(network, "SIM MCC/MNC");
    String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    String code =
        switch (n) {
          case "中国移动", "chinamobile", "cmcc" -> "mobile";
          case "中国联通", "chinaunicom", "cucc" -> "unicom";
          case "中国电信", "chinatelecom", "ctcc" -> "telecom";
          case "中国广电", "chinabroadnet", "cbn" -> "broadnet";
          default -> "unknown";
        };
    if (!code.equals("unknown")) return result(code, "SIM 系统名称");
    boolean loaded = (mccMnc != null && !mccMnc.isEmpty()) || !n.isEmpty();
    return result(code, loaded ? "系统未提供可识别的运营商" : "等待 SIM 识别");
  }

  private static Result result(String code, String source) {
    return new Result(
        code,
        switch (code) {
          case "mobile" -> "中国移动";
          case "unicom" -> "中国联通";
          case "telecom" -> "中国电信";
          case "broadnet" -> "中国广电";
          case "other" -> "其他运营商";
          default -> "未识别运营商";
        },
        source);
  }

  private CarrierIdentity() {}
}
