package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class WifiPolicyTest {
  @Test
  public void onlyValidatedDefaultWifiPausesLimits() {
    for (int bits = 0; bits < 32; bits++) {
      boolean enabled = (bits & 1) != 0,
          wifi = (bits & 2) != 0,
          cellular = (bits & 4) != 0,
          internet = (bits & 8) != 0,
          validated = (bits & 16) != 0;
      assertEquals(
          bits == 27, WifiPolicy.pausesLimits(enabled, wifi, cellular, internet, validated));
    }
  }

  @Test
  public void pausedWifiIgnoresQuotaAndDoesNotSwitchCards() {
    for (int slot : new int[] {1, 2})
      for (String mode : new String[] {"stop", "auto", "fixed"})
        for (long remaining : new long[] {-1, 0, 500})
          assertEquals(
              PolicyEngine.Action.NONE,
              PolicyEngine.decide(
                      new PolicyEngine.Input(
                          false,
                          true,
                          slot,
                          true,
                          mode,
                          2,
                          remaining,
                          500,
                          1_500_000_000,
                          PolicyEngine.NO_LIMIT,
                          2,
                          true))
                  .action());
  }

  @Test
  public void disconnectRestoresThresholdAndCardRestriction() {
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false, true, 2, true, "stop", 2, 500, 500, 0, 4_500_000_000L, 0, false))
            .action());
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    true, true, 1, false, "fixed", 2, 9999, 500, 0, 4_500_000_000L, 0, false))
            .action());
    assertEquals(
        PolicyEngine.Action.NONE,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false, true, 2, true, "stop", 2, 501, 500, 0, 4_500_000_000L, 0, false))
            .action());
  }

  @Test
  public void wifiDoesNotDisableIdentityOrDeviceCeiling() {
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false, false, 2, false, "stop", 2, 9999, 500, 0, 1000, 0, true))
            .action());
    for (int slot : new int[] {1, 2})
      assertEquals(
          PolicyEngine.Action.STOP,
          PolicyEngine.decide(
                  new PolicyEngine.Input(
                      false,
                      true,
                      slot,
                      false,
                      "stop",
                      2,
                      9999,
                      500,
                      4_500_000_000L,
                      4_500_000_000L,
                      0,
                      true))
              .action());
  }

  @Test
  public void userCumulativeLimitReturnsAfterWifiWithoutResettingUse() {
    long used = 1_500_000_000, saved = 1_000_000_000;
    assertEquals(PolicyEngine.NO_LIMIT, PolicyEngine.cumulativeLimit(true, saved, true));
    assertEquals(saved, PolicyEngine.cumulativeLimit(true, saved, false));
    assertEquals(
        PolicyEngine.Action.STOP,
        PolicyEngine.decide(
                new PolicyEngine.Input(
                    false,
                    true,
                    2,
                    false,
                    "stop",
                    2,
                    9999,
                    0,
                    used,
                    PolicyEngine.cumulativeLimit(true, saved, false),
                    0,
                    false))
            .action());
  }
}
