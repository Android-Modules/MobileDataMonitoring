package com.dalong.dataguard.core;

/** When to fold system per-app stats. Idle radios are not polled. */
public final class StatsRefresh {
  /** A second poll inside this window is skipped, including during a speed test. */
  public static final long MIN_GAP_MS = 8_000;
  /** Moderate use is folded by this age, so a visible page can follow within 20 seconds. */
  public static final long ACTIVE_GAP_MS = 10_000;
  /** Full-speed use polls at the minimum gap instead of waiting out the active gap. */
  public static final long BURST_BYTES = 1_500_000;
  /** Less than this since the last poll is radio noise, not use. */
  public static final long NOISE_BYTES = 256 * 1024;
  /** A debug session re-reads history on this heartbeat even when nothing was sent. */
  public static final long STRICT_READ_MS = 60_000;

  public enum Mode {
    SKIP,
    /** Read history only. Does not ask the system to fold kernel counters. */
    READ,
    /** Fold kernel counters, then read history, so per-app totals move. */
    POLL
  }

  private StatsRefresh() {}

  /**
   * @param pending cellular bytes since the last poll
   * @param sincePoll milliseconds since that poll
   * @param idleEvery configured quiet interval; active use never waits longer than {@link #ACTIVE_GAP_MS}
   * @param strict debug session, which must notice a broken history read
   * @param sinceRead milliseconds since a successful history read
   */
  public static Mode mode(
      long pending, long sincePoll, long idleEvery, boolean strict, long sinceRead) {
    if (pending < 0 || sincePoll < 0 || idleEvery < 1 || sinceRead < 0)
      throw new IllegalArgumentException("统计刷新参数非法");
    if (pending >= NOISE_BYTES
        && sincePoll >= MIN_GAP_MS
        && (pending >= BURST_BYTES
            || sincePoll >= Math.min(ACTIVE_GAP_MS, idleEvery)
            || sincePoll >= idleEvery)) return Mode.POLL;
    if (strict && sinceRead >= STRICT_READ_MS && sincePoll >= MIN_GAP_MS) return Mode.READ;
    return Mode.SKIP;
  }
}
