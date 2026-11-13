package com.dalong.dataguard.core;

/**
 * What the web hall's login page is showing. The address alone is not enough: a live login is sent
 * through that page and only says it is already signed in.
 */
public final class WebSession {
  public static final String IN = "in", OUT = "out", WAIT = "wait";

  private WebSession() {}

  /** @param text visible text of the login page; empty while it has not rendered */
  public static String judge(String text) {
    if (text == null || text.isEmpty()) return WAIT;
    if (text.contains("已成功登录")) return IN;
    if (text.contains("验证码")) return OUT;
    return WAIT;
  }
}
