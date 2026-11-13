package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class AccountingTest {
  @Test
  public void disabledCumulativeSettingDoesNotApplyItsSavedLimit() {
    long effective = PolicyEngine.cumulativeLimit(false, 1000000000);
    assertEquals(PolicyEngine.NO_LIMIT, effective);
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false, true, 2, false, "stop", 2, 0, 500, 100_000_000_000L, effective, 0))
            .action());
    assertEquals(1000000000, PolicyEngine.cumulativeLimit(true, 1000000000));
  }

  @Test
  public void webHallThenTheJiangsuAppThenSms() {
    assertEquals(2, OfficialRoute.webReads(true));
    assertEquals(0, OfficialRoute.webReads(false));
    assertEquals(OfficialRoute.Step.APP, OfficialRoute.next(true, true));
    assertEquals(OfficialRoute.Step.APP, OfficialRoute.next(true, false));
    assertEquals(OfficialRoute.Step.SMS, OfficialRoute.next(false, true));
    assertEquals(OfficialRoute.Step.NONE, OfficialRoute.next(false, false));
  }

  @Test
  public void activeUseFoldsWithinTwentySecondsAndIdleDoesNotPoll() {
    long idle = 60_000;
    assertEquals(StatsRefresh.Mode.SKIP, StatsRefresh.mode(0, 3_600_000, idle, false, 3_600_000));
    assertEquals(
        StatsRefresh.Mode.SKIP, StatsRefresh.mode(200_000, 120_000, idle, false, 120_000));
    assertEquals(StatsRefresh.Mode.SKIP, StatsRefresh.mode(2_000_000, 7_000, idle, false, 7_000));
    assertEquals(StatsRefresh.Mode.POLL, StatsRefresh.mode(2_000_000, 8_000, idle, false, 8_000));
    assertEquals(StatsRefresh.Mode.SKIP, StatsRefresh.mode(300_000, 9_000, idle, false, 9_000));
    assertEquals(StatsRefresh.Mode.POLL, StatsRefresh.mode(300_000, 10_000, idle, false, 10_000));
    assertEquals(StatsRefresh.Mode.POLL, StatsRefresh.mode(300_000, idle, idle, false, idle));
    assertEquals(StatsRefresh.Mode.READ, StatsRefresh.mode(0, 60_000, idle, true, 60_000));
    assertEquals(StatsRefresh.Mode.SKIP, StatsRefresh.mode(0, 60_000, idle, true, 30_000));
    assertEquals(StatsRefresh.Mode.POLL, StatsRefresh.mode(300_000, 8_000, 5_000, false, 8_000));
  }

  @Test(expected = IllegalArgumentException.class)
  public void statsRefreshRejectsANegativeWait() {
    StatsRefresh.mode(0, -1, 1, false, 0);
  }

  @Test
  public void debugCeilingAppliesOnlyInsideASession() {
    long now = 1000;
    assertTrue(PolicyEngine.debugExhausted(now + 1, now, PolicyEngine.DEBUG_CEILING));
    assertFalse(PolicyEngine.debugExhausted(now + 1, now, PolicyEngine.DEBUG_CEILING - 1));
    assertFalse(PolicyEngine.debugExhausted(now, now, PolicyEngine.DEBUG_CEILING));
    assertFalse(PolicyEngine.debugExhausted(0, now, Long.MAX_VALUE));
  }

  @Test
  public void kernelIncrementsBridgeDelayedHistoryWithoutRefundOrDoubleCounting() {
    long used = UsageMath.conservativeUsed(-1, -1, 100, 20, 20, 0);
    assertEquals(100, used);
    used = UsageMath.conservativeUsed(used, 20, 100, 20, 20, 40);
    assertEquals(140, used);
    used = UsageMath.conservativeUsed(used, 20, 120, 20, 20, 10);
    assertEquals(150, used);
    assertEquals(150, UsageMath.conservativeUsed(used, 20, 150, 20, 20, 0));
    assertEquals(190, UsageMath.conservativeUsed(used, 20, 190, 20, 20, 5));
  }

  @Test
  public void protectedUsageRolloverRequiresCurrentHistoryAndDiscardsOldCycle() {
    assertEquals(-1, UsageMath.conservativeUsed(900, 10, 100, 10, 20, 5));
    assertEquals(-1, UsageMath.conservativeUsed(900, 20, -1, 20, 20, 5));
    assertEquals(30, UsageMath.conservativeUsed(900, 10, 30, 20, 20, 5));
  }

  @Test
  public void generalReserveStopsAtTheBoundaryAndItsSwitchDoesNotControlDirectedLimits() {
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(false, true, 2, true, "stop", 2, 501, 500, 0, 1000, 0))
            .action());
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(false, true, 2, true, "stop", 2, 500, 500, 0, 1000, 0))
            .action());
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(false, true, 2, false, "stop", 2, 0, 500, 0, 1000, 0))
            .action());
    assertEquals(
        DirectedLimit.Action.STOP, DirectedLimit.action(true, true, true, false, false, false));
  }

  @Test(expected = ArithmeticException.class)
  public void protectedCounterOverflowCannotWrapIntoAvailableQuota() {
    UsageMath.conservativeUsed(Long.MAX_VALUE, 20, 100, 20, 20, 1);
  }

  @Test
  public void counterResetAndDisappearanceNeverSubtractBudget() {
    assertEquals(40, UsageMath.counterDelta(Map.of("a", 100L, "b", 80L), Map.of("a", 140L)));
    assertEquals(7, UsageMath.counterDelta(Map.of("a", 100L), Map.of("a", 7L)));
    assertEquals(1000, UsageMath.counterDelta(Map.of(), Map.of("new", 1000L)));
  }

  @Test(expected = ArithmeticException.class)
  public void newBootCountersCannotOverflowIntoABudgetRefund() {
    UsageMath.counterDelta(Map.of(), Map.of("a", Long.MAX_VALUE, "b", 1L));
  }

  @Test
  public void delayedSnapshotIsIdempotentAndIncludesLaterTraffic() {
    assertEquals(700, UsageMath.corrected(500, 600, 400));
    assertEquals(700, UsageMath.corrected(500, 600, 400));
    assertEquals(750, UsageMath.corrected(550, 600, 400));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rolledBackHistoryCannotReduceUsage() {
    UsageMath.corrected(30, 200, 50);
  }

  @Test
  public void endOfMonthCycleClampsAndUsesPreviousMonth() {
    ZoneId z = ZoneId.of("Asia/Shanghai");
    long now = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, z).toInstant().toEpochMilli();
    assertEquals(
        LocalDate.of(2026, 2, 28),
        Instant.ofEpochMilli(UsageMath.cycleStart(now, 31, z)).atZone(z).toLocalDate());
  }

  @Test
  public void invisiblePathAndSuffixDomainsDoNotMatch() {
    RuleMatcher.Rule r = new RuleMatcher.Rule("cloud.139.com", "/download/");
    assertTrue(r.matches("https://cloud.139.com/download/a", true));
    assertFalse(r.matches("https://cloud.139.com/download/a", false));
    assertFalse(r.matches("https://cloud.139.com.evil.test/download/a", true));
  }

  @Test
  public void forbiddenCardIsNeverABackupOrDataCard() {
    assertFalse(PolicyEngine.eligibleBackup(false, 9999, 500));
    var i = new PolicyEngine.Input(false, true, 2, true, "auto", 2, 0, 500, 0, 1000, 0);
    assertEquals(PolicyEngine.Action.STOP, PolicyEngine.decide(i).action());
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(true, true, 1, false, "fixed", 2, 9999, 0, 0, 1000, 0))
            .action());
  }

  @Test
  public void exhaustedBudgetStopsWithoutEnablingQuotaProtection() {
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(false, true, 2, false, "fixed", 2, -1, 0, 1000, 1000, 0))
            .action());
  }

  @Test
  public void oldCycleUsageCannotAuthorizeNetworking() {
    assertEquals(-1, UsageMath.remaining(1000, 100, 10, 20, -1, -1, -1));
    assertEquals(900, UsageMath.remaining(1000, 100, 20, 20, -1, -1, -1));
  }

  @Test
  public void calibrationMustBelongToTheCurrentCycle() {
    assertEquals(900, UsageMath.remaining(1000, 100, 20, 20, 900, 50, 10));
    assertEquals(50, UsageMath.remaining(1000, 100, 20, 20, 900, 50, 20));
    assertEquals(0, UsageMath.remaining(1000, 200, 20, 20, 900, 50, 20));
  }

  @Test
  public void generalCalibrationDoesNotChargeEarlierDirectedTrafficAndRejectsRollback() {
    assertEquals(800, UsageMath.remaining(1000, 500, 20, 20, 100, 400, 20));
    assertEquals(-1, UsageMath.remaining(1000, 100, 20, 20, 900, 200, 20));
    assertEquals(100, UsageMath.remaining(1000, 200, 20, 20, 900, 200, 20));
    assertEquals(50, UsageMath.remaining(1000, 250, 20, 20, 900, 200, 20));
  }

  @Test
  public void generalBaselineSeparatesEarlierDirectedUsageButChargesEveryLaterByte() {
    assertEquals(600, UsageMath.generalRemaining(1000, 5000, 20, 20, 400, 5000, 20, 600));
    assertEquals(550, UsageMath.generalRemaining(1000, 5050, 20, 20, 400, 5000, 20, 600));
    assertEquals(0, UsageMath.generalRemaining(1000, 5600, 20, 20, 400, 5000, 20, 600));
    // A higher custom cap cannot invent carrier balance; a lower cap remains effective.
    assertEquals(550, UsageMath.generalRemaining(2000, 5050, 20, 20, 400, 5000, 20, 600));
    assertEquals(50, UsageMath.generalRemaining(500, 5050, 20, 20, 400, 5000, 20, 600));
    assertEquals(-1, UsageMath.generalRemaining(1000, 4900, 20, 20, 400, 5000, 20, 600));
    // An old-cycle baseline must never authorize the new cycle.
    assertEquals(0, UsageMath.generalRemaining(1000, 5050, 20, 20, 400, 5000, 10, 600));
    assertEquals(-1, UsageMath.generalRemaining(1000, 5050, 10, 20, 400, 5000, 10, 600));
  }

  @Test
  public void directedTrafficSinceBaselineLeavesGeneralAlone() {
    // 100 new bytes, 70 of them claimed by a directed package: general pays only 30.
    assertEquals(570, UsageMath.generalRemaining(1000, 5100, 20, 20, 400, 5000, 20, 600, 70));
    // A claim larger than all cellular traffic cannot refund general allowance.
    assertEquals(600, UsageMath.generalRemaining(1000, 5100, 20, 20, 400, 5000, 20, 600, 500));
    assertEquals(30, UsageMath.generalSince(100, 70));
    assertEquals(100, UsageMath.generalSince(100, -5));
    // Growth is capped by each package's balance at the baseline; unknown balance is uncapped.
    assertEquals(
        40 + 25 + 0,
        UsageMath.directedSince(
            new long[] {100, 10, 0}, new long[] {140, 60, 90}, new long[] {-1, 25, 0}));
    // A history regression claims nothing.
    assertEquals(0, UsageMath.directedSince(new long[] {100}, new long[] {80}, new long[] {-1}));
  }

  @Test
  public void independentCumulativeCapAndCarrierAllowanceAreSeparate() {
    var stopped =
        new PolicyEngine.Input(
            false,
            true,
            2,
            false,
            "stop",
            2,
            60_000_000_000L,
            500_000_000,
            1_006_000_000,
            1_000_000_000,
            0);
    assertEquals(PolicyEngine.Action.STOP, PolicyEngine.decide(stopped).action());
    var raised =
        new PolicyEngine.Input(
            false,
            true,
            2,
            true,
            "stop",
            2,
            60_000_000_000L,
            500_000_000,
            1_006_000_000,
            4_500_000_000L,
            0);
    assertEquals(PolicyEngine.Action.NONE, PolicyEngine.decide(raised).action());
    var boundary =
        new PolicyEngine.Input(
            false,
            true,
            2,
            false,
            "stop",
            2,
            60_000_000_000L,
            500_000_000,
            4_500_000_000L,
            4_500_000_000L,
            0);
    assertEquals(PolicyEngine.Action.STOP, PolicyEngine.decide(boundary).action());
  }

  @Test
  public void regressedCalibrationReadsAsUnknownAndDoesNotCutTheConnection() {
    long remaining = UsageMath.remaining(1000, 100, 20, 20, 1000, 200, 20);
    assertEquals(-1, remaining);
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(false, true, 2, true, "stop", 2, remaining, 50, 0, 1000, 0))
            .action());
    assertEquals(
        DirectedLimit.Action.STOP,
        DirectedLimit.action(true, true, true, remaining > 50, true, true));
  }

  @Test
  public void domesticBillingRolloverIgnoresDeviceTimeZone() {
    TimeZone original = TimeZone.getDefault();
    try {
      long now = Instant.parse("2026-10-31T16:00:01Z").toEpochMilli();
      long expected = Instant.parse("2026-10-31T16:00:00Z").toEpochMilli();
      for (String deviceZone : List.of("America/Los_Angeles", "UTC", "Asia/Shanghai")) {
        TimeZone.setDefault(TimeZone.getTimeZone(deviceZone));
        assertEquals(expected, UsageMath.carrierCycleStart(now, 1));
      }
      assertEquals(
          Instant.parse("2026-10-30T16:00:00Z").toEpochMilli(),
          UsageMath.carrierCycleStart(now, 31));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  public void unknownQuotaOrUnavailableCounterDoesNotMeanZeroUsed() {
    assertEquals(-1, UsageMath.remaining(-1, 100, 20, 20, -1, -1, -1));
    assertEquals(-1, UsageMath.remaining(1000, -1, 20, 20, -1, -1, -1));
  }
}
