package com.dalong.dataguard.core;

import java.math.BigDecimal;
import java.util.Locale;

/** Charges are money: unreadable text stays unknown and is never shown as free. */
public final class OutsidePlan {
  private OutsidePlan() {}

  public static long fen(String yuan) {
    if (yuan == null || !yuan.trim().matches("[0-9]+(?:\\.[0-9]+)?")) return -1;
    try {
      return new BigDecimal(yuan.trim())
          .movePointRight(2)
          .setScale(0, java.math.RoundingMode.CEILING)
          .longValueExact();
    } catch (ArithmeticException tooLarge) {
      return -1;
    }
  }

  public static String yuan(long fen) {
    return fen < 0 ? "未提供" : String.format(Locale.ROOT, "%d.%02d 元", fen / 100, fen % 100);
  }
}
