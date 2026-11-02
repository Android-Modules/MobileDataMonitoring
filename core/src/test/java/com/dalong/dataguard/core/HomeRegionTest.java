package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class HomeRegionTest {
  @Test
  public void readsProvinceFromGeocoderText() {
    assertEquals("江苏", HomeRegion.fromDescription("江苏省南京市"));
    assertEquals("北京", HomeRegion.fromDescription("北京市"));
    assertEquals("内蒙古", HomeRegion.fromDescription("内蒙古呼和浩特市"));
    assertEquals("", HomeRegion.fromDescription("中国"));
    assertEquals("", HomeRegion.fromDescription(null));
  }

  @Test
  public void readsProvinceOnlyFromChinaMobileIccid() {
    assertEquals("江苏", HomeRegion.fromMobileIccid("89860200101234567890"));
    assertEquals("北京", HomeRegion.fromMobileIccid("89860000011234567890"));
    assertEquals("重庆", HomeRegion.fromMobileIccid("8986070031123456789F"));
    assertEquals("", HomeRegion.fromMobileIccid("89860124801234567890"));
    assertEquals("", HomeRegion.fromMobileIccid("89860200991234567890"));
    assertEquals("", HomeRegion.fromMobileIccid(""));
  }

  @Test
  public void jiangsuAppOnlyForJiangsuMobileNumbers() {
    assertTrue(HomeRegion.jiangsuApp("mobile", "江苏", false));
    assertFalse(HomeRegion.jiangsuApp("mobile", "浙江", true));
    assertFalse(HomeRegion.jiangsuApp("unicom", "江苏", true));
    assertFalse(HomeRegion.jiangsuApp("mobile", "", false));
    assertTrue(HomeRegion.jiangsuApp("mobile", "", true));
  }
}
