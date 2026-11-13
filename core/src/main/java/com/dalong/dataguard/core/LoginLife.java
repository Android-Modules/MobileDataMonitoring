package com.dalong.dataguard.core;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * How long a saved carrier login has lasted. Neither carrier states an expiry, so the only basis
 * is what was seen: when the login began, when it last worked, and how long the previous one held.
 */
public final class LoginLife {
  static final long HOUR = 3600000L, DAY = 86400000L;
  static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  /**
   * @param loginAt when the login in use began; 0 when it predates tracking
   * @param okAt when it last answered a query
   * @param lostAt when it was found signed out; 0 while it works
   * @param life how long the previous login lasted; 0 until one has ended
   */
  public record State(long loginAt, long okAt, long lostAt, long life) {}

  private LoginLife() {}

  /**
   * A query just worked. {@code login} is when the login in use began, or 0 for the one already
   * known. Only a login that replaces a lost one teaches how long logins last.
   */
  public static State ok(State s, long now, long login) {
    if (s == null) return new State(Math.max(0, login), now, 0, 0);
    long life = s.life;
    boolean replaced = login > 0 && login != s.loginAt;
    if (replaced && s.lostAt > 0 && s.loginAt > 0 && s.okAt > s.loginAt) life = s.okAt - s.loginAt;
    return new State(login > 0 ? login : s.loginAt, now, 0, life);
  }

  public static State lost(State s, long now) {
    if (s == null) return new State(0, 0, now, 0);
    return s.lostAt > 0 ? s : new State(s.loginAt, s.okAt, now, s.life);
  }

  /** When this login should end if it lasts as long as the previous one; -1 without a basis. */
  public static long expectedEnd(State s) {
    return s.lostAt == 0 && s.loginAt > 0 && s.life > 0 ? s.loginAt + s.life : -1;
  }

  /** Close enough to the expected end to sign in again ahead of time. */
  public static boolean nearEnd(State s, long now) {
    long end = expectedEnd(s);
    return end > 0 && now >= end - Math.min(DAY, s.life / 4);
  }

  public static String span(long ms) {
    if (ms < HOUR) return "不到 1 小时";
    if (ms < 2 * DAY) return ms / HOUR + " 小时";
    return ms / DAY + " 天";
  }

  public static String describe(State s, long now, ZoneId zone) {
    if (s.lostAt > 0)
      return "已失效，需重新登录（" + DAY_TIME.format(Instant.ofEpochMilli(s.lostAt).atZone(zone)) + " 发现）";
    StringJoiner text = new StringJoiner(" · ");
    text.add("有效");
    if (s.loginAt > 0) {
      long age = Math.max(0, now - s.loginAt);
      text.add(age < HOUR ? "1 小时内登录" : "已登录 " + span(age));
    }
    if (s.okAt > 0) {
      long ago = Math.max(0, now - s.okAt);
      text.add(ago < HOUR ? "1 小时内确认可用" : span(ago) + "前确认可用");
    }
    long end = expectedEnd(s);
    if (end > 0)
      text.add(
          now < end
              ? "预计 " + DAY_TIME.format(Instant.ofEpochMilli(end).atZone(zone)) + " 前后到期"
              : "已超过上次维持的 " + span(s.life) + "，随时可能失效");
    return text.toString();
  }
}
