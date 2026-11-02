package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class CarrierIdentityTest {
  @Test
  public void identifiesThreeCarriersFromSimCodes() {
    assertEquals("mobile", CarrierIdentity.resolve(-1, "46008", "").code());
    assertEquals("unicom", CarrierIdentity.resolve(-1, "46006", "").code());
    assertEquals("telecom", CarrierIdentity.resolve(-1, "46011", "").code());
  }

  @Test
  public void doesNotGuessFromCustomNamesOrHostOfMvno() {
    assertEquals("unknown", CarrierIdentity.resolve(-1, "", "我的移动卡").code());
    assertEquals("other", CarrierIdentity.resolve(9999, "46000", "MVNO").code());
    assertEquals("unknown", CarrierIdentity.resolve(-1, "99999", "Other").code());
  }

  @Test
  public void carrierIdSupportsRoamingAndConflictsStayUnknown() {
    assertEquals("telecom", CarrierIdentity.resolve(2237, "20404", "中国电信").code());
    assertEquals("unknown", CarrierIdentity.resolve(1435, "46001", "").code());
    assertEquals("unicom", CarrierIdentity.resolve(-1, "", "China Unicom").code());
  }
}
