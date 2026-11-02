package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class SmsBalanceTest {
  static final long GIB = 1073741824L, MIB = 1048576L;
  static final long NOW =
      LocalDateTime.of(2026, 11, 2, 15, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
  static final String REPLY =
      "【流量查询】尊敬的客户，您好！截至11月02日14时43分,您本月\n"
          + "-国内通用流量共21.28GB,已使用20.97GB,剩余0.31GB,套餐外流量6.95GB。\n"
          + "-国内其他流量共50.00GB,已使用50.00GB,剩余0.00GB。\n"
          + "您各项套餐的准实时消费情况如下：\n"
          + "-1、中国移动咪咕快游0元50GB定向流量包（2025版）:流量(国内咪咕游戏定向流量)已使用50.00GB，剩余0.00GB；\n"
          + "-2、移动全家享套餐（宽带升级）109元:可共享流量(国内通用流量)已使用20.00GB，剩余0.00GB；\n"
          + "-3、套外优惠流量(国内通用):流量已使用991.34MB,剩余317.66MB；\n"
          + "【中国移动】";

  @Test
  public void readsTotalsOutsideUsageAndCutoff() {
    var r = SmsBalance.parseMobile(REPLY, NOW);
    assertEquals(Math.round(21.28 * GIB), r.general().total());
    assertEquals(Math.round(20.97 * GIB), r.general().used());
    assertEquals(Math.round(0.31 * GIB), r.general().remaining());
    assertEquals(Math.round(50.00 * GIB), r.directed().total());
    assertEquals(Math.round(6.95 * GIB), r.outside());
    assertEquals(NOW - 17 * 60000, r.cutoffAt());
  }

  @Test
  public void readsEachPackageAndItsKind() {
    var items = SmsBalance.parseMobile(REPLY, NOW).items();
    assertEquals(3, items.size());
    assertEquals("中国移动咪咕快游0元50GB定向流量包（2025版）", items.get(0).name());
    assertTrue(items.get(0).directed());
    assertFalse(items.get(1).directed());
    assertEquals(Math.round(317.66 * MIB), items.get(2).remaining());
  }

  @Test
  public void aReplyWithoutTotalsIsNotABalance() {
    assertNull(SmsBalance.parseMobile("【中国联通】请点击链接跳转到中国联通APP查询", NOW));
    assertNull(SmsBalance.parseMobile("", NOW));
    assertNull(SmsBalance.parseMobile(null, NOW));
  }

  @Test
  public void noOutsideUsageAndUnreadableCutoffAreSafe() {
    var r = SmsBalance.parseMobile("-国内通用流量共10GB,已使用1GB,剩余9GB", NOW);
    assertEquals(0, r.outside());
    assertNull(r.directed());
    assertEquals(NOW, r.cutoffAt());
    assertEquals(NOW, SmsBalance.parseMobile("截至13月40日99时99分-国内通用流量共10GB,已使用1GB,剩余9GB", NOW).cutoffAt());
  }

  @Test
  public void aCutoffInTheFutureIsNotTrusted() {
    assertEquals(NOW, SmsBalance.parseMobile("截至12月30日10时00分-国内通用流量共1GB,已使用0GB,剩余1GB", NOW).cutoffAt());
  }
}
