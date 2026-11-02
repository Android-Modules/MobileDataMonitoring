package com.dalong.dataguard.core;

import java.time.*;
import java.util.*;

/** Pure accounting: classifiers never add bytes to the cellular total. */
public final class UsageMath {
  private UsageMath() {}

  /** Kernel increments bridge delayed history samples; a refresh never refunds protected usage. */
  public static long conservativeUsed(
      long previous, long previousCycle, long raw, long usageCycle, long currentCycle, long delta) {
    if (delta < 0) throw new IllegalArgumentException("计数增量不可为负");
    if (raw < 0 || usageCycle != currentCycle) return -1;
    long carried = previousCycle == currentCycle && previous >= 0 ? previous : 0;
    return Math.max(raw, Math.addExact(carried, delta));
  }

  public static long corrected(long rawNow, long officialUsed, long rawAtOfficialTime) {
    if (rawNow < rawAtOfficialTime || officialUsed < 0)
      throw new IllegalArgumentException("计数回退，不能应用校准");
    return Math.addExact(officialUsed, rawNow - rawAtOfficialTime);
  }

  /** A previous-cycle sample cannot establish a usable balance in the new cycle. */
  public static long remaining(
      long quota,
      long raw,
      long usageCycle,
      long currentCycle,
      long officialUsed,
      long rawAtOfficialTime,
      long officialCycle) {
    if (quota < 0 || raw < 0 || usageCycle != currentCycle) return -1;
    long used = raw;
    if (officialCycle == currentCycle && officialUsed >= 0 && rawAtOfficialTime >= 0) {
      // A regressed history sample cannot revoke a known official baseline.
      if (raw < rawAtOfficialTime) return -1;
      // Raw is ALL cellular traffic, including directed traffic. Only the increment
      // after the official general baseline can be charged to general allowance.
      used = corrected(raw, officialUsed, rawAtOfficialTime);
    }
    return used >= quota ? 0 : quota - used;
  }

  /** An explicit official remaining balance also caps a custom local allowance. */
  public static long generalRemaining(
      long quota,
      long raw,
      long usageCycle,
      long currentCycle,
      long officialUsed,
      long rawAtOfficialTime,
      long officialCycle,
      long officialRemaining) {
    long left =
        remaining(
            quota, raw, usageCycle, currentCycle, officialUsed, rawAtOfficialTime, officialCycle);
    if (left < 0) return -1;
    if (officialCycle == currentCycle
        && officialUsed >= 0
        && rawAtOfficialTime >= 0
        && officialRemaining >= 0) {
      long delta = raw - rawAtOfficialTime;
      return Math.min(left, delta >= officialRemaining ? 0 : officialRemaining - delta);
    }
    return left;
  }

  /** Cellular data interfaces by modem vendor: Qualcomm/Exynos, MediaTek, Unisoc. */
  public static boolean cellularInterface(String name) {
    return name.matches("rmnet.*|ccmni[0-9]+|seth_.*|sipa_.*");
  }

  /** Domestic carrier billing dates do not move when the device changes time zone. */
  public static long carrierCycleStart(long now, int day) {
    return cycleStart(now, day, ZoneId.of("Asia/Shanghai"));
  }

  public static long cycleStart(long now, int day, ZoneId zone) {
    if (day < 1 || day > 31) throw new IllegalArgumentException("账期日应为 1–31");
    ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
    LocalDate d = t.toLocalDate().withDayOfMonth(Math.min(day, t.toLocalDate().lengthOfMonth()));
    if (d.isAfter(t.toLocalDate())) {
      LocalDate prev = t.toLocalDate().minusMonths(1);
      d = prev.withDayOfMonth(Math.min(day, prev.lengthOfMonth()));
    }
    return d.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  /** Disappearing interfaces do not subtract usage; resets add the new generation. */
  public static long counterDelta(Map<String, Long> previous, Map<String, Long> current) {
    long delta = 0;
    for (var e : current.entrySet()) {
      long n = e.getValue();
      if (n < 0) throw new IllegalArgumentException("计数不可读");
      Long p = previous.get(e.getKey());
      delta = Math.addExact(delta, p == null || n < p ? n : n - p);
    }
    return delta;
  }
}
