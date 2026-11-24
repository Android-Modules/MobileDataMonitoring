package com.dalong.dataguard.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the web hall's login page is showing, and helpers for its session cookies. The address alone
 * is not enough: a live login is sent through that page and only says it is already signed in.
 */
public final class WebSession {
  public static final String IN = "in", OUT = "out", WAIT = "wait";
  static final Pattern MASKED_PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d\\*{4}\\d{4})(?!\\d)");

  private WebSession() {}

  /** @param text visible text of the login page; empty while it has not rendered */
  public static String judge(String text) {
    if (text == null || text.isEmpty()) return WAIT;
    if (text.contains("已成功登录")) return IN;
    if (text.contains("验证码")) return OUT;
    return WAIT;
  }

  /** Extracts one named cookie value from a {@code Cookie} request header, or empty when absent. */
  public static String cookie(String cookieHeader, String name) {
    if (cookieHeader == null || cookieHeader.isEmpty() || name == null || name.isEmpty()) return "";
    for (String part : cookieHeader.split(";")) {
      String trimmed = part.trim();
      int eq = trimmed.indexOf('=');
      if (eq <= 0) continue;
      if (name.equals(trimmed.substring(0, eq).trim())) {
        return trimmed.substring(eq + 1).trim();
      }
    }
    return "";
  }

  /** Extracts the value from a single {@code Set-Cookie} response header if it sets {@code name}. */
  public static String setCookieValue(String setCookieHeader, String name) {
    if (setCookieHeader == null || setCookieHeader.isEmpty() || name == null || name.isEmpty())
      return "";
    int semi = setCookieHeader.indexOf(';');
    String first = (semi >= 0 ? setCookieHeader.substring(0, semi) : setCookieHeader).trim();
    int eq = first.indexOf('=');
    if (eq <= 0) return "";
    if (!name.equals(first.substring(0, eq).trim())) return "";
    return first.substring(eq + 1).trim();
  }

  /** First masked 11-digit number (such as {@code 156****1056}) shown on the signed-in bind page. */
  public static String maskedNumber(String html) {
    if (html == null || html.isEmpty()) return "";
    Matcher m = MASKED_PHONE.matcher(html);
    return m.find() ? m.group(1) : "";
  }

  /**
   * True when the bind page confirms an active login for the expected card number. If the page
   * shows a masked number and we know the card's number, they must match.
   */
  public static boolean signedInPage(String html, String expectedNumber) {
    if (!IN.equals(judge(html))) return false;
    String shown = maskedNumber(html);
    if (shown.isEmpty() || expectedNumber == null || expectedNumber.isEmpty()) return true;
    return WebMargin.sameNumber(shown, expectedNumber);
  }

  /**
   * True when an {@code SSOCheck.action} redirect location points back to {@code wx.10086.cn} with
   * a real SSO artifact (not an error placeholder like {@code -1}).
   */
  public static boolean validSsoRedirect(String location) {
    if (location == null || location.isEmpty()) return false;
    try {
      URI uri = URI.create(location);
      if (!"https".equals(uri.getScheme()) || !"wx.10086.cn".equals(uri.getHost())) return false;
      String query = uri.getRawQuery();
      if (query == null || query.isEmpty()) return false;
      for (String pair : query.split("&")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) continue;
        String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
        if ("artifact".equalsIgnoreCase(k)) {
          String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
          return v.length() >= 16 && !"-1".equals(v);
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }
}
