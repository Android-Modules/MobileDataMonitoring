package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** Limits follow the actual data card and a per-SIM permission, never a fixed slot number. */
public class CardPolicyTest {
  static final long NONE = PolicyEngine.NO_LIMIT;

  static PolicyEngine.Decision decide(
      boolean forbidden, int slot, boolean enabled, String mode, int fixed, long left, int other) {
    return PolicyEngine.decide(
        new PolicyEngine.Input(
            forbidden, true, slot, enabled, mode, fixed, left, 500, 0, NONE, other, false));
  }

  @Test
  public void eitherSlotMayBeTheOnlyDataCard() {
    for (int slot : new int[] {1, 2})
      assertEquals(
          PolicyEngine.Action.NONE, decide(false, slot, true, "stop", 0, 9999, 0).action());
  }

  @Test
  public void forbiddenCardStopsOnCellularButFollowsTheWifiPause() {
    assertEquals(PolicyEngine.Action.STOP, decide(true, 2, false, "stop", 0, 9999, 0).action());
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    true, true, 1, false, "stop", 0, 9999, 500, 0, NONE, 0, true))
            .action());
  }

  @Test
  public void automaticModeSwitchesToAnyPermittedCardWithHeadroom() {
    for (int[] pair : new int[][] {{1, 2}, {2, 1}}) {
      PolicyEngine.Decision d = decide(false, pair[0], true, "auto", 0, 500, pair[1]);
      assertEquals(PolicyEngine.Action.SWITCH, d.action());
      assertEquals(pair[1], d.targetSlot());
    }
    assertEquals(PolicyEngine.Action.STOP, decide(false, 2, true, "auto", 0, 500, 0).action());
    assertTrue(PolicyEngine.eligibleBackup(true, 501, 500));
    assertFalse(PolicyEngine.eligibleBackup(true, 500, 500));
    assertFalse(PolicyEngine.eligibleBackup(true, -1, 500));
  }

  @Test
  public void fixedModeFollowsTheChosenSlotWithoutTheGeneralLimit() {
    PolicyEngine.Decision d = decide(false, 2, false, "fixed", 1, 9999, 0);
    assertEquals(PolicyEngine.Action.SWITCH, d.action());
    assertEquals(1, d.targetSlot());
    assertEquals(PolicyEngine.Action.NONE, decide(false, 1, false, "fixed", 1, 9999, 0).action());
    assertEquals(PolicyEngine.Action.NONE, decide(false, 2, false, "fixed", 0, 9999, 0).action());
  }

  @Test
  public void cellularInterfacesCoverCommonModems() {
    for (String name : new String[] {"rmnet_data0", "rmnet0", "ccmni0", "seth_lte0", "sipa_eth0"})
      assertTrue(name, UsageMath.cellularInterface(name));
    for (String name : new String[] {"wlan0", "lo", "v4-rmnet_data0", "ccmni-lan", "dummy0"})
      assertFalse(name, UsageMath.cellularInterface(name));
  }

  @Test
  public void unknownBalanceNeverCutsTheConnection() {
    assertEquals(PolicyEngine.Action.NONE, decide(false, 2, true, "stop", 0, -1, 0).action());
    assertEquals(PolicyEngine.Action.NONE, decide(false, 1, true, "auto", 0, -1, 2).action());
    assertEquals(PolicyEngine.Action.STOP, decide(false, 2, true, "stop", 0, 500, 0).action());
  }

  @Test
  public void aLowButSufficientBalanceStaysOnline() {
    long mb = 1_000_000;
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false, true, 1, true, "stop", 0, 333 * mb, 200 * mb, 0, NONE, 0, false))
            .action());
  }

  @Test
  public void unreadableCardIdentityKeepsEverydayDataButStopsInADebugSession() {
    var daily =
        new PolicyEngine.Input(false, false, 0, true, "stop", 0, -1, 500, 0, NONE, 0, false, false);
    assertEquals(PolicyEngine.Action.NONE, PolicyEngine.decide(daily).action());
    var debugging =
        new PolicyEngine.Input(false, false, 0, true, "stop", 0, -1, 500, 0, NONE, 0, false, true);
    assertEquals(PolicyEngine.Action.STOP, PolicyEngine.decide(debugging).action());
    // A barred card carrying data still stops, even while its identity cannot be confirmed.
    var barred =
        new PolicyEngine.Input(true, false, 1, true, "stop", 0, -1, 500, 0, NONE, 0, false, false);
    assertEquals(PolicyEngine.Action.STOP, PolicyEngine.decide(barred).action());
  }
}
