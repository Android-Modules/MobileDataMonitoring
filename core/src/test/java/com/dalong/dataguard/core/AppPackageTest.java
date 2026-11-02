package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppPackageTest {
  @Test
  public void acceptsOnlyPackageShapedNames() {
    assertTrue(AppPackage.valid("com.tencent.mm"));
    assertTrue(AppPackage.valid("com.sinovatech.unicom.ui"));
    for (String name : new String[] {null, "", "微信", "wechat", "com..mm", "com.1a", "com.mm."})
      assertFalse(name, AppPackage.valid(name));
  }

  @Test
  public void pastedAddressesBecomeRules() {
    assertEquals("a.com/x", AppPackage.ruleLine("  https://a.com/x "));
    assertEquals("a.com", AppPackage.ruleLine("HTTP://a.com"));
    assertEquals("a.com/x", AppPackage.ruleLine("a.com/x"));
  }
}
