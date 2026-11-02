package com.dalong.dataguard.core;

/** Home province of a SIM: the number's geocoded region first, a China Mobile ICCID second. */
public final class HomeRegion {
  /** In China Mobile ICCID province-code order, 01 to 31. */
  static final String[] PROVINCES = {
    "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
    "湖北", "湖南", "广东", "广西", "海南", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆", "重庆"
  };

  private HomeRegion() {}

  /** "江苏省南京市" or "北京市" from the platform geocoder; anything else stays unknown. */
  public static String fromDescription(String description) {
    if (description == null) return "";
    String d = description.trim();
    for (String p : PROVINCES) if (d.startsWith(p)) return p;
    return "";
  }

  /** 8986 00/02/04/07 M F SS …: SS is the province only on China Mobile cards. */
  public static String fromMobileIccid(String iccid) {
    if (iccid == null || !iccid.matches("8986(00|02|04|07)[0-9]{4}[0-9A-Fa-f]*")) return "";
    int code = Integer.parseInt(iccid.substring(8, 10));
    return code >= 1 && code <= PROVINCES.length ? PROVINCES[code - 1] : "";
  }

  /** The Jiangsu app only serves Jiangsu numbers; an unknown region counts only once proven. */
  public static boolean jiangsuApp(String carrier, String province, boolean provenByApp) {
    if (!"mobile".equals(carrier)) return false;
    return province == null || province.isEmpty() ? provenByApp : province.equals("江苏");
  }
}
