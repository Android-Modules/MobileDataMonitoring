package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class OfficialTotalsTest {
  @org.junit.Test
  public void carrierExpiryUsesChinaTimeAndRecognizesBothTimestampFormats() {
    long end =
        java.time.ZonedDateTime.of(2026, 11, 1, 23, 59, 59, 0, java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    assertFalse(OfficialTotals.expired("2026-11-01 23:59:59", end));
    assertTrue(OfficialTotals.expired("2026-11-01 23:59:59", end + 1));
    assertTrue(OfficialTotals.expired("20261101235959", end + 1));
    assertTrue(OfficialTotals.expired("2026-11-01T23:59:59", end + 1));
  }

  @org.junit.Test
  public void dateOnlyIncludesTheWholeDayAndUnknownDoesNotInventAnExpiry() {
    long midnight =
        java.time.LocalDate.of(2026, 11, 2)
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    assertFalse(OfficialTotals.expired("2026-11-01", midnight - 1));
    assertTrue(OfficialTotals.expired("2026-11-01", midnight));
    assertTrue(OfficialTotals.expired("20261101", midnight));
    for (String text : new String[] {"", "长期有效", "N", "2026-02-30 23:59:59"})
      assertFalse(OfficialTotals.expired(text, midnight));
  }

  @Test
  public void unknownCloudDoesNotHideFiniteBalances() {
    var s =
        OfficialTotals.sum(
            List.of(
                new OfficialTotals.Package("cloud", -1, 0, -1, false),
                new OfficialTotals.Package("migu", 50, 50, 0, false),
                new OfficialTotals.Package("computer", 30, 1, 29, false),
                new OfficialTotals.Package("multi", 70, 0, 70, false)));
    assertEquals(150, s.total());
    assertEquals(51, s.used());
    assertEquals(99, s.remaining());
    assertEquals(4, s.count());
    assertEquals(1, s.unknownTotal());
    assertEquals(1, s.unknownRemaining());
    assertEquals(0, s.unknownUsed());
  }

  @Test
  public void zeroRemainingIsKnownAndExhaustedPackageStaysInTotal() {
    var s = OfficialTotals.sum(List.of(new OfficialTotals.Package("migu", 50, 50, 0, false)));
    assertEquals(50, s.total());
    assertEquals(0, s.remaining());
    assertEquals(0, s.unknownRemaining());
  }

  @Test
  public void duplicateAndExpiredAllowancesAreNotAvailableTwice() {
    var p = new OfficialTotals.Package("current", 30, 10, 20, false);
    var s =
        OfficialTotals.sum(List.of(p, p, new OfficialTotals.Package("expired", 100, 0, 100, true)));
    assertEquals(30, s.total());
    assertEquals(20, s.remaining());
    assertEquals(1, s.count());
  }

  @Test
  public void unavailableFieldsHaveIndependentCounts() {
    var s =
        OfficialTotals.sum(
            List.of(
                new OfficialTotals.Package("first", -1, -1, 5, false),
                new OfficialTotals.Package("second", 10, 2, -1, false)));
    assertEquals(10, s.total());
    assertEquals(2, s.used());
    assertEquals(5, s.remaining());
    assertEquals(1, s.unknownTotal());
    assertEquals(1, s.unknownUsed());
    assertEquals(1, s.unknownRemaining());
    assertEquals(0, OfficialTotals.sum(List.of()).count());
  }

  @Test
  public void sumsCannotOverflowIntoNegativeBalances() {
    var s =
        OfficialTotals.sum(
            List.of(
                new OfficialTotals.Package(
                    "first", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, false),
                new OfficialTotals.Package("second", 1, 1, 1, false)));
    assertEquals(Long.MAX_VALUE, s.total());
    assertEquals(Long.MAX_VALUE, s.used());
    assertEquals(Long.MAX_VALUE, s.remaining());
  }
}
