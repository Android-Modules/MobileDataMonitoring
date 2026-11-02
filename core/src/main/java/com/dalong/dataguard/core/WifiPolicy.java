package com.dalong.dataguard.core;

/** An enabled radio or a captive/unusable Wi-Fi network must not suspend cellular protection. */
public final class WifiPolicy {
  private WifiPolicy() {}

  public static boolean pausesLimits(
      boolean enabled, boolean wifi, boolean cellular, boolean internet, boolean validated) {
    return enabled && wifi && !cellular && internet && validated;
  }
}
