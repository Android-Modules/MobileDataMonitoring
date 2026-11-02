package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class WebMarginTest {
  static final long MIB = 1048576L, GIB = 1073741824L;

  @Test
  public void megabytesAndGigabytesConvertAndUnknownStaysUnknown() {
    var general = WebMargin.usage("21471.39", "318", "21789", "03");
    assertEquals(Math.round(21789 * (double) MIB), general.total());
    assertEquals(Math.round(21471.39 * MIB), general.used());
    assertEquals(318 * MIB, general.remaining());
    var total = WebMargin.usage("77.92", "N", "N", "04");
    assertEquals(-1, total.total());
    assertEquals(-1, total.remaining());
    assertEquals(Math.round(77.92 * GIB), total.used());
  }

  @Test(expected = IllegalArgumentException.class)
  public void anUnknownUnitIsRefused() {
    WebMargin.bytes("1", "01");
  }

  @Test(expected = IllegalArgumentException.class)
  public void aMalformedNumberIsRefused() {
    WebMargin.bytes("1e9", "03");
  }

  @Test
  public void statisticsTimeIsBeijingTimeAndNeverAhead() {
    long now = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli();
    assertEquals(
        Instant.parse("2026-11-02T08:45:00Z").toEpochMilli(),
        WebMargin.cutoff("2026-11-02 16:45", now));
    assertEquals(now, WebMargin.cutoff("2026-11-03 16:45", now));
    assertEquals(now, WebMargin.cutoff("昨天", now));
  }

  @Test
  public void onlyAnIdenticalMaskedNumberMatches() {
    assertTrue(WebMargin.sameNumber("156****1056", "156****1056"));
    assertFalse(WebMargin.sameNumber("156****1056", "156****1057"));
    assertFalse(WebMargin.sameNumber("", ""));
    assertFalse(WebMargin.sameNumber("1561056", "1561056"));
    assertFalse(WebMargin.sameNumber(null, "156****1056"));
  }
}
