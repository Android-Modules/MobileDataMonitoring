package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class LoginLifeTest {
  static final long H = 3600000L, D = 86400000L, T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
  static final ZoneId CN = ZoneId.of("Asia/Shanghai");

  @Test
  public void aLoginThatPredatesTrackingHasNoAgeOrEstimate() {
    var s = LoginLife.ok(null, T0, 0);
    assertEquals(0, s.loginAt());
    assertEquals(-1, LoginLife.expectedEnd(s));
    assertEquals("有效 · 1 小时内确认可用", LoginLife.describe(s, T0 + 10 * 60000, CN));
  }

  @Test
  public void aLostLoginReplacedByANewOneTeachesItsLifetime() {
    var s = LoginLife.ok(null, T0, T0);
    s = LoginLife.ok(s, T0 + 6 * D, 0);
    s = LoginLife.lost(s, T0 + 7 * D);
    assertEquals(0, s.life());
    assertTrue(LoginLife.describe(s, T0 + 7 * D, CN).startsWith("已失效"));
    long relogin = T0 + 8 * D;
    s = LoginLife.ok(s, relogin, relogin);
    assertEquals(6 * D, s.life());
    assertEquals(0, s.lostAt());
    assertEquals(relogin + 6 * D, LoginLife.expectedEnd(s));
    assertFalse(LoginLife.nearEnd(s, relogin + 4 * D));
    assertTrue(LoginLife.nearEnd(s, relogin + 5 * D + H));
    assertEquals(
        "有效 · 已登录 2 天 · 1 小时内确认可用 · 预计 11-15 08:00 前后到期",
        LoginLife.describe(LoginLife.ok(s, relogin + 2 * D, 0), relogin + 2 * D, CN));
    assertTrue(LoginLife.describe(s, relogin + 7 * D, CN).endsWith("已超过上次维持的 6 天，随时可能失效"));
    assertTrue(LoginLife.describe(s, relogin + 60000, CN).startsWith("有效 · 1 小时内登录 · "));
  }

  @Test
  public void aFalseAlarmWithTheSameLoginLeavesTheLifetimeAlone() {
    var s = LoginLife.ok(null, T0 + D, T0);
    s = LoginLife.lost(s, T0 + 2 * D);
    s = LoginLife.ok(s, T0 + 2 * D + H, 0);
    assertEquals(T0, s.loginAt());
    assertEquals(0, s.life());
    assertEquals(0, s.lostAt());
  }

  @Test
  public void aVoluntaryRefreshIsNotAnEnding() {
    var s = LoginLife.ok(null, T0, T0);
    s = LoginLife.ok(s, T0 + D, T0 + D);
    assertEquals(0, s.life());
    assertEquals(T0 + D, s.loginAt());
  }

  @Test
  public void theFirstDetectionTimeIsKept() {
    var s = LoginLife.lost(LoginLife.ok(null, T0, T0), T0 + D);
    assertEquals(T0 + D, LoginLife.lost(s, T0 + 2 * D).lostAt());
  }

  @Test
  public void spansReadNaturally() {
    assertEquals("不到 1 小时", LoginLife.span(59 * 60000));
    assertEquals("47 小时", LoginLife.span(47 * H));
    assertEquals("2 天", LoginLife.span(2 * D));
  }
}
