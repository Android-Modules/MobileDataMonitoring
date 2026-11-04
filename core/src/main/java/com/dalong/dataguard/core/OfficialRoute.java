package com.dalong.dataguard.core;

/** Mobile official balance: the web hall stays first, then a text, then the Jiangsu app. */
public final class OfficialRoute {
  /** A live web login that misses once is read again, and only once. */
  public static final int WEB_RETRY = 1;

  public enum Step {
    SMS,
    APP,
    NONE
  }

  private OfficialRoute() {}

  /**
   * What to do after the web hall was tried, or when this card has no web login.
   *
   * @param webSessionRemains the saved web login is still the route to keep
   * @param smsDue a query text is allowed now
   * @param appReady a Jiangsu app page can be opened
   */
  /** Reads to perform while the login is still valid: the scheduled one, plus a single retry. */
  public static int webReads(boolean loggedIn) {
    return loggedIn ? 1 + WEB_RETRY : 0;
  }

  public static Step afterWeb(boolean webSessionRemains, boolean smsDue, boolean appReady) {
    if (webSessionRemains) return smsDue ? Step.SMS : Step.NONE;
    if (smsDue) return Step.SMS;
    return appReady ? Step.APP : Step.NONE;
  }
}
