package com.dalong.dataguard.core;

/** Official balances and local estimates stay distinguishable; unknown is never zero. */
public final class DirectedLimit {
  public record Result(long used, long remaining, boolean exhausted, boolean official) {}

  /** A manual baseline changes this package only; counter rollback cannot grant more allowance. */
  public static Result manual(long quota, long local, long usedAtSave, long referenceAt) {
    if (usedAtSave < 0 || referenceAt < 0 || local < referenceAt)
      return new Result(-1, -1, false, false);
    long used = add(usedAtSave, local - referenceAt);
    return new Result(
        used, quota < 0 ? -1 : Math.max(0, quota - used), quota >= 0 && used >= quota, false);
  }

  public static Result evaluate(
      long quota,
      long local,
      boolean currentOfficial,
      long officialUsed,
      long officialRemaining,
      long referenceAt) {
    local = Math.max(0, local);
    if (currentOfficial && (officialRemaining >= 0 || officialUsed >= 0)) {
      long delta = Math.max(0, local - Math.max(0, referenceAt));
      long used = officialUsed < 0 ? -1 : add(officialUsed, delta);
      if (officialRemaining < 0) return new Result(used, -1, false, delta == 0);
      return new Result(
          used, Math.max(0, officialRemaining - delta), officialRemaining <= delta, delta == 0);
    }
    return new Result(
        local, quota < 0 ? -1 : Math.max(0, quota - local), quota >= 0 && local >= quota, false);
  }

  static long add(long a, long b) {
    return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
  }

  public enum Action {
    NONE,
    WARN,
    CONFIRM,
    STOP
  }

  public static boolean confirmationCurrent(long now, long at) {
    return at > 0 && now >= at && now - at <= 300000;
  }

  /** Brief request gaps belong to the same use session; a later session warns again. */
  public static boolean warningDue(long now, long lastAffected, long lastWarning) {
    if (lastWarning <= 0) return true;
    if (now < lastWarning || now < lastAffected) return false;
    return now - lastAffected >= 30000 && now - lastWarning >= 30000;
  }

  public static boolean warningVisible(long now, long warningAt) {
    return warningAt > 0 && now >= warningAt && now - warningAt < 30000;
  }

  /** An empty directed package must never become a SIM-wide ban on general data. */
  public static Action action(
      boolean exhausted,
      boolean enabled,
      boolean affectedApp,
      boolean generalAvailable,
      boolean confirm,
      boolean accepted) {
    if (!exhausted || !affectedApp) return Action.NONE;
    if (!enabled) return Action.WARN;
    if (!generalAvailable) return Action.STOP;
    if (confirm && !accepted) return Action.CONFIRM;
    return Action.WARN;
  }
}
