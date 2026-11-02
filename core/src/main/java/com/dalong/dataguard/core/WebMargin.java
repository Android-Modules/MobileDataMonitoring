package com.dalong.dataguard.core;

import java.math.*;
import java.time.*;
import java.time.format.*;

/** China Mobile's web service hall figures; "N" stays unknown instead of becoming zero. */
public final class WebMargin {
  static final BigDecimal MB = BigDecimal.valueOf(1048576), GB = BigDecimal.valueOf(1073741824);
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private WebMargin() {}

  /** 03 is MB and 04 is GB; any other unit is refused rather than guessed. */
  public static long bytes(String value, String unit) {
    if (value == null || value.isBlank() || value.trim().equals("N")) return -1;
    BigDecimal scale =
        switch (unit == null ? "" : unit) {
          case "03" -> MB;
          case "04" -> GB;
          default -> throw new IllegalArgumentException("网上营业厅流量单位无法识别");
        };
    String n = value.trim();
    if (!n.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("网上营业厅流量数值无法识别");
    return new BigDecimal(n).multiply(scale).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  public static SmsBalance.Usage usage(String used, String remaining, String total, String unit) {
    return new SmsBalance.Usage(bytes(total, unit), bytes(used, unit), bytes(remaining, unit));
  }

  /** The page's own statistics time, in Beijing time; anything unreadable or ahead is now. */
  public static long cutoff(String time, long now) {
    try {
      long at =
          LocalDateTime.parse(time.trim(), TIME)
              .atZone(ZoneId.of("Asia/Shanghai"))
              .toInstant()
              .toEpochMilli();
      return at > now + 300000 ? now : at;
    } catch (RuntimeException unreadable) {
      return now;
    }
  }

  /** The page shows only a masked number; it must equal this card's masked number exactly. */
  public static boolean sameNumber(String pageMasked, String cardMasked) {
    return pageMasked != null
        && cardMasked != null
        && pageMasked.trim().matches("1[0-9]{2}\\*{4}[0-9]{4}")
        && pageMasked.trim().equals(cardMasked.trim());
  }
}
