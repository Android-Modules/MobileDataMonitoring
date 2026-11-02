package com.dalong.dataguard.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finite package balances stay visible even when another package has an unknown allowance. */
public final class OfficialTotals {
  /** Chinese carrier dates have no offset; unknown or long-term text stays unknown. */
  public static boolean expired(String expires, long now) {
    if (expires == null) return false;
    String value = expires.trim().replace('T', ' ');
    java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
    try {
      if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        return now
            >= java.time.LocalDate.parse(value)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
      if (value.matches("[0-9]{8}"))
        return now
            >= java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
      var format =
          java.time.format.DateTimeFormatter.ofPattern(
                  value.matches("[0-9]{14}") ? "uuuuMMddHHmmss" : "uuuu-MM-dd HH:mm:ss")
              .withResolverStyle(java.time.format.ResolverStyle.STRICT);
      long end =
          java.time.LocalDateTime.parse(value, format).atZone(zone).toInstant().toEpochMilli();
      return now > end;
    } catch (java.time.DateTimeException ignored) {
      return false;
    }
  }

  public record Package(String id, long total, long used, long remaining, boolean expired) {}

  public record Summary(
      long total,
      long used,
      long remaining,
      int count,
      int unknownTotal,
      int unknownUsed,
      int unknownRemaining) {}

  public static Summary sum(List<Package> packages) {
    long total = 0, used = 0, remaining = 0;
    int count = 0, unknownTotal = 0, unknownUsed = 0, unknownRemaining = 0;
    Set<String> seen = new HashSet<>();
    for (Package p : packages) {
      if (p.expired() || !seen.add(p.id())) continue;
      count++;
      if (p.total() < 0) unknownTotal++;
      else total = DirectedLimit.add(total, p.total());
      if (p.used() < 0) unknownUsed++;
      else used = DirectedLimit.add(used, p.used());
      if (p.remaining() < 0) unknownRemaining++;
      else remaining = DirectedLimit.add(remaining, p.remaining());
    }
    return new Summary(total, used, remaining, count, unknownTotal, unknownUsed, unknownRemaining);
  }
}
