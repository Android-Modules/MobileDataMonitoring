package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class DirectedLimitTest {
  @Test
  public void manualUsedStartsAtSaveAndAddsOnlyLaterPackageTraffic() {
    var r = DirectedLimit.manual(50, 400, 20, 400);
    assertEquals(20, r.used());
    assertEquals(30, r.remaining());
    assertFalse(r.official());
    r = DirectedLimit.manual(50, 410, 20, 400);
    assertEquals(30, r.used());
    assertEquals(20, r.remaining());
    assertTrue(DirectedLimit.manual(50, 430, 20, 400).exhausted());
  }

  @Test
  public void manualCounterRollbackAndUnknownQuotaCannotInventBalance() {
    assertEquals(-1, DirectedLimit.manual(50, 399, 20, 400).remaining());
    assertEquals(-1, DirectedLimit.manual(-1, 410, 20, 400).remaining());
    assertFalse(DirectedLimit.manual(-1, 410, 20, 400).exhausted());
    var r = DirectedLimit.manual(50, Long.MAX_VALUE, Long.MAX_VALUE, 0);
    assertEquals(Long.MAX_VALUE, r.used());
    assertEquals(0, r.remaining());
    assertTrue(r.exhausted());
  }

  @Test
  public void newUseSessionWarnsAgainButContinuousTrafficDoesNot() {
    long start = 1000000;
    assertTrue(DirectedLimit.warningDue(start, 0, 0));
    assertFalse(DirectedLimit.warningDue(start + 1000, start, start));
    assertFalse(DirectedLimit.warningDue(start + 60000, start + 59000, start));
    assertFalse(DirectedLimit.warningDue(start + 29999, start, start));
    assertTrue(DirectedLimit.warningDue(start + 30000, start, start));
    assertFalse(DirectedLimit.warningDue(start - 1, start, start));
  }

  @Test
  public void warningDeliveryWindowSurvivesPollingWithoutReplayingOldWarnings() {
    assertTrue(DirectedLimit.warningVisible(1000000, 1000000));
    assertTrue(DirectedLimit.warningVisible(1029999, 1000000));
    assertFalse(DirectedLimit.warningVisible(1030000, 1000000));
    assertFalse(DirectedLimit.warningVisible(999999, 1000000));
    assertFalse(DirectedLimit.warningVisible(1000000, 0));
  }

  @Test
  public void unknownAndUnlimitedNeverMeanEmpty() {
    assertFalse(DirectedLimit.evaluate(-1, 300, false, -1, -1, 0).exhausted());
  }

  @Test
  public void explicitZeroQuotaIsEmptyRatherThanUnlimited() {
    var r = DirectedLimit.evaluate(0, 0, false, -1, -1, 0);
    assertTrue(r.exhausted());
    assertEquals(0, r.remaining());
  }

  @Test
  public void unknownRemainingStillPreservesOfficialUsed() {
    var r = DirectedLimit.evaluate(-1, 800, true, 50, -1, 800);
    assertTrue(r.official());
    assertEquals(50, r.used());
    assertEquals(-1, r.remaining());
    assertFalse(r.exhausted());
    r = DirectedLimit.evaluate(-1, 820, true, 50, -1, 800);
    assertFalse(r.official());
    assertEquals(70, r.used());
    assertEquals(-1, r.remaining());
    assertFalse(r.exhausted());
  }

  @Test
  public void officialEmptyWorksWithoutAppAssignment() {
    var r = DirectedLimit.evaluate(-1, 0, true, 500, 0, 0);
    assertTrue(r.exhausted());
    assertTrue(r.official());
    assertEquals(500, r.used());
  }

  @Test
  public void officialBaselineDoesNotCountHistoryTwice() {
    var r = DirectedLimit.evaluate(1000, 800, true, 300, 700, 800);
    assertEquals(300, r.used());
    assertEquals(700, r.remaining());
    r = DirectedLimit.evaluate(1000, 1000, true, 300, 700, 800);
    assertEquals(500, r.used());
    assertEquals(500, r.remaining());
    assertFalse(r.official());
  }

  @Test
  public void lateOrRolloverSnapshotDoesNotApplyOldOfficialEmpty() {
    var r = DirectedLimit.evaluate(1000, 50, false, 1000, 0, 500);
    assertFalse(r.exhausted());
    assertEquals(950, r.remaining());
  }

  @Test
  public void emptyDirectedPackageAllowsGeneralDataByDefault() {
    assertEquals(
        DirectedLimit.Action.WARN, DirectedLimit.action(true, true, true, true, false, false));
    assertEquals(
        DirectedLimit.Action.WARN, DirectedLimit.action(true, false, true, false, true, false));
  }

  @Test
  public void unrelatedAppsAndNonEmptyPackagesAreNeverBlocked() {
    for (boolean available : new boolean[] {false, true})
      for (boolean confirm : new boolean[] {false, true}) {
        assertEquals(
            DirectedLimit.Action.NONE,
            DirectedLimit.action(true, true, false, available, confirm, false));
        assertEquals(
            DirectedLimit.Action.NONE,
            DirectedLimit.action(false, true, true, available, confirm, false));
      }
  }

  @Test
  public void explicitConfirmationAllowsTheEpisodeButCannotBypassGeneralProtection() {
    assertEquals(
        DirectedLimit.Action.CONFIRM, DirectedLimit.action(true, true, true, true, true, false));
    assertEquals(
        DirectedLimit.Action.WARN, DirectedLimit.action(true, true, true, true, true, true));
    assertEquals(
        DirectedLimit.Action.STOP, DirectedLimit.action(true, true, true, false, true, true));
    assertEquals(
        DirectedLimit.Action.STOP, DirectedLimit.action(true, true, true, false, false, false));
  }

  @Test
  public void confirmationExpiresAndClockRollbackCannotExtendIt() {
    long at = 1000000;
    assertTrue(DirectedLimit.confirmationCurrent(at, at));
    assertTrue(DirectedLimit.confirmationCurrent(at + 300000, at));
    assertFalse(DirectedLimit.confirmationCurrent(at + 300001, at));
    assertFalse(DirectedLimit.confirmationCurrent(at - 1, at));
    assertFalse(DirectedLimit.confirmationCurrent(at, 0));
  }

  @Test
  public void resetCountersAndLargeValuesCannotWrap() {
    var r = DirectedLimit.evaluate(1000, 10, true, 200, 800, 900);
    assertEquals(800, r.remaining());
    r = DirectedLimit.evaluate(Long.MAX_VALUE, Long.MAX_VALUE, true, 100, 1, 0);
    assertEquals(Long.MAX_VALUE, r.used());
    assertTrue(r.exhausted());
  }
}
