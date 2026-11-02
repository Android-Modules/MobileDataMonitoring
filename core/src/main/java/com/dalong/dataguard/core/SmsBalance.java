package com.dalong.dataguard.core;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Reads the carrier's balance reply; anything without the expected totals is not a balance. */
public final class SmsBalance {
  public record Usage(long total, long used, long remaining) {}

  public record Item(String name, boolean directed, long used, long remaining) {}

  public record Result(Usage general, Usage directed, long outside, long cutoffAt, List<Item> items) {}

  static final String AMOUNT = "([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|KB)";
  static final Pattern GENERAL =
      Pattern.compile("国内通用流量共" + AMOUNT + "[,，]已使用" + AMOUNT + "[,，]剩余" + AMOUNT);
  static final Pattern DIRECTED =
      Pattern.compile("国内其他流量共" + AMOUNT + "[,，]已使用" + AMOUNT + "[,，]剩余" + AMOUNT);
  static final Pattern OUTSIDE = Pattern.compile("套餐外流量" + AMOUNT);
  static final Pattern CUTOFF = Pattern.compile("截至([0-9]{1,2})月([0-9]{1,2})日([0-9]{1,2})时([0-9]{1,2})分");
  static final Pattern ITEM =
      Pattern.compile("-[0-9]+、(.+?)[:：](.*?)已使用" + AMOUNT + "[,，]剩余" + AMOUNT);

  private SmsBalance() {}

  static long bytes(String value, String unit) {
    double scale =
        switch (unit) {
          case "GB" -> 1073741824d;
          case "MB" -> 1048576d;
          default -> 1024d;
        };
    return Math.round(Double.parseDouble(value) * scale);
  }

  static Usage usage(Matcher m) {
    return new Usage(
        bytes(m.group(1), m.group(2)), bytes(m.group(3), m.group(4)), bytes(m.group(5), m.group(6)));
  }

  /** The reply has no year; a cutoff ahead of now belongs to the previous year's last days. */
  static long cutoff(String body, long now) {
    Matcher m = CUTOFF.matcher(body);
    if (!m.find()) return now;
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    try {
      int year = Instant.ofEpochMilli(now).atZone(zone).getYear();
      long at =
          LocalDateTime.of(
                  year,
                  Integer.parseInt(m.group(1)),
                  Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(3)),
                  Integer.parseInt(m.group(4)))
              .atZone(zone)
              .toInstant()
              .toEpochMilli();
      return at > now + 300000 ? now : at;
    } catch (DateTimeException invalid) {
      return now;
    }
  }

  public static Result parseMobile(String body, long now) {
    if (body == null) return null;
    Matcher general = GENERAL.matcher(body);
    if (!general.find()) return null;
    Matcher directed = DIRECTED.matcher(body), outside = OUTSIDE.matcher(body);
    List<Item> items = new ArrayList<>();
    for (String line : body.split("\n")) {
      Matcher item = ITEM.matcher(line);
      if (item.find())
        items.add(
            new Item(
                item.group(1).trim(),
                item.group(2).contains("定向"),
                bytes(item.group(3), item.group(4)),
                bytes(item.group(5), item.group(6))));
    }
    return new Result(
        usage(general),
        directed.find() ? usage(directed) : null,
        outside.find() ? bytes(outside.group(1), outside.group(2)) : 0,
        cutoff(body, now),
        items);
  }
}
