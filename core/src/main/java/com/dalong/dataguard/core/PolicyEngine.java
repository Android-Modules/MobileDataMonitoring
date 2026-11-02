package com.dalong.dataguard.core;

public final class PolicyEngine {
  public enum Action {
    NONE,
    STOP,
    SWITCH
  }

  public record Decision(Action action, int targetSlot, String reason) {}

  /** Bounds traffic inside a debug session; everyday use is never counted against it. */
  public static final long DEBUG_CEILING = 4_500_000_000L;

  public static final long NO_LIMIT = Long.MAX_VALUE;

  /** The cumulative limit is the user's own setting; off means no cumulative stop at all. */
  public static long cumulativeLimit(boolean enabled, long configured) {
    return enabled ? configured : NO_LIMIT;
  }

  public static long cumulativeLimit(boolean enabled, long configured, boolean wifiPaused) {
    return cumulativeLimit(enabled && !wifiPaused, configured);
  }

  public static boolean debugExhausted(long sessionUntil, long now, long debugUsed) {
    return now < sessionUntil && debugUsed >= DEBUG_CEILING;
  }

  /** Only a permitted card with verified headroom may receive traffic from an exhausted one. */
  public static boolean eligibleBackup(boolean permitted, long remaining, long reserve) {
    return permitted && remaining > reserve;
  }

  public record Input(
      boolean activeForbidden,
      boolean identityKnown,
      int activeSlot,
      boolean enabled,
      String mode,
      int fixedSlot,
      long remaining,
      long reserve,
      long budgetUsed,
      long budgetLimit,
      int eligibleOtherSlot,
      boolean wifiPaused,
      boolean failClosed) {
    public Input(
        boolean activeForbidden,
        boolean identityKnown,
        int activeSlot,
        boolean enabled,
        String mode,
        int fixedSlot,
        long remaining,
        long reserve,
        long budgetUsed,
        long budgetLimit,
        int eligibleOtherSlot,
        boolean wifiPaused) {
      this(
          activeForbidden,
          identityKnown,
          activeSlot,
          enabled,
          mode,
          fixedSlot,
          remaining,
          reserve,
          budgetUsed,
          budgetLimit,
          eligibleOtherSlot,
          wifiPaused,
          true);
    }

    public Input(
        boolean activeForbidden,
        boolean identityKnown,
        int activeSlot,
        boolean enabled,
        String mode,
        int fixedSlot,
        long remaining,
        long reserve,
        long budgetUsed,
        long budgetLimit,
        int eligibleOtherSlot) {
      this(
          activeForbidden,
          identityKnown,
          activeSlot,
          enabled,
          mode,
          fixedSlot,
          remaining,
          reserve,
          budgetUsed,
          budgetLimit,
          eligibleOtherSlot,
          false,
          true);
    }
  }

  public static Decision decide(Input i) {
    if (i.activeForbidden && !i.wifiPaused) return stop("当前数据卡已设置禁止使用移动数据");
    // A data card the system briefly cannot name (a call, a handover) is not a reason to cut
    // everyday use; only a debug session treats it as one.
    if (!i.identityKnown)
      return i.failClosed ? stop("卡身份或统计不可用") : new Decision(Action.NONE, 0, "卡身份暂不可读，保持现状");
    if (i.budgetUsed >= i.budgetLimit) return stop("本机累计用量已达到独立上限");
    if (i.wifiPaused) return new Decision(Action.NONE, 0, "Wi-Fi 联网中，额度限制已暂停");
    if ("fixed".equals(i.mode) && i.fixedSlot > 0 && i.activeSlot != i.fixedSlot)
      return new Decision(Action.SWITCH, i.fixedSlot, "固定使用指定卡");
    if (!i.enabled) return none();
    // An unknown balance is not an exhausted one: a limit can only act on a known figure.
    if (i.remaining < 0) return none();
    if (i.remaining <= i.reserve) {
      if ("auto".equals(i.mode) && i.eligibleOtherSlot > 0)
        return new Decision(Action.SWITCH, i.eligibleOtherSlot, "当前卡达到保护线");
      return stop("达到保护线；无可用备用卡");
    }
    return none();
  }

  private static Decision none() {
    return new Decision(Action.NONE, 0, "监控中");
  }

  private static Decision stop(String reason) {
    return new Decision(Action.STOP, 0, reason);
  }
}
