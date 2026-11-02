package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class OutsidePlanTest {
  @Test
  public void parsesOfficialChargeWithoutRounding() {
    assertEquals(2700, OutsidePlan.fen("27.00"));
    assertEquals(0, OutsidePlan.fen("0"));
    assertEquals(1, OutsidePlan.fen("0.001"));
    assertEquals("27.00 元", OutsidePlan.yuan(2700));
    assertEquals("0.05 元", OutsidePlan.yuan(5));
  }

  @Test
  public void unreadableChargeStaysUnknownNotFree() {
    for (String text : new String[] {null, "", "N", "-1", "27元", "99999999999999999999999"})
      assertEquals(text, -1, OutsidePlan.fen(text));
    assertEquals("未提供", OutsidePlan.yuan(-1));
  }
}
