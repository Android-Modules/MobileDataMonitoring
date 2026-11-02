package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class PhoneIdentityTest {
  @Test
  public void normalizesCountryPrefixWithoutGuessing() {
    assertEquals("13800001234", PhoneIdentity.normalize("+86 138-0000-1234"));
    assertEquals("13800001234", PhoneIdentity.normalize("008613800001234"));
    assertEquals("", PhoneIdentity.normalize("encrypted-account"));
  }

  @Test
  public void masksNumbersAndRejectsMissingDigits() {
    assertEquals("138****1234", PhoneIdentity.masked("13800001234"));
    assertEquals("", PhoneIdentity.masked("138****1234"));
  }

  @Test
  public void requiresCarrierNumberAndPlainAccountEvidence() {
    assertTrue(PhoneIdentity.matches("mobile", "hash-a", "mobile", "hash-a", true));
    assertFalse(PhoneIdentity.matches("mobile", "hash-a", "unicom", "hash-a", true));
    assertFalse(PhoneIdentity.matches("mobile", "hash-a", "mobile", "hash-b", true));
    assertFalse(PhoneIdentity.matches("unicom", "hash-a", "unicom", "hash-a", false));
    assertFalse(PhoneIdentity.matches("mobile", "", "mobile", "", true));
  }

  @Test
  public void cookieNumberMustBeAPlainNumberOfTheNamedField() {
    String cookie = "a=1; c_mobile=13100005206; u_account=13100009999";
    assertEquals("13100005206", PhoneIdentity.fromCookie(cookie, "c_mobile"));
    assertEquals("", PhoneIdentity.fromCookie("c_mobile=yt4abcNog=", "c_mobile"));
    assertEquals("", PhoneIdentity.fromCookie("xc_mobile=13100005206", "c_mobile"));
    assertEquals("", PhoneIdentity.fromCookie(null, "c_mobile"));
  }
}
