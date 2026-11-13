package com.dalong.dataguard.core;

/** Mobile official balance: the web hall first, then the Jiangsu app, then a text. */
public final class OfficialRoute {
  /** A live web login that misses once is read again, and only once. */
  public static final int WEB_RETRY = 1;

  public enum Step {
    APP,
    SMS,
    NONE
  }

  private OfficialRoute() {}

  /** Reads while this card still has a web login: the scheduled one, plus a single retry. */
  public static int webReads(boolean loggedIn) {
    return loggedIn ? 1 + WEB_RETRY : 0;
  }

  /**
   * What to do after the web hall did not return a balance. A text never jumps ahead of a Jiangsu
   * app that can still be read, whether or not the web login itself is still good.
   *
   * @param appReady a Jiangsu app page can be opened now
   * @param smsDue a query text is allowed now
   */
  public static Step next(boolean appReady, boolean smsDue) {
    if (appReady) return Step.APP;
    if (smsDue) return Step.SMS;
    return Step.NONE;
  }
}
