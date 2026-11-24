package com.dalong.dataguard.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebSessionTest {
  @Test
  public void aPageThatSaysAlreadySignedInIsNotALogout() {
    assertEquals(WebSession.IN, WebSession.judge("您已成功登录，正在进入首页"));
    assertEquals(WebSession.IN, WebSession.judge("已成功登录 验证码"));
  }

  @Test
  public void onlyTheLoginFormEndsTheSession() {
    assertEquals(WebSession.OUT, WebSession.judge("请输入短信验证码"));
    assertEquals(WebSession.WAIT, WebSession.judge(""));
    assertEquals(WebSession.WAIT, WebSession.judge(null));
    assertEquals(WebSession.WAIT, WebSession.judge("加载中"));
  }

  @Test
  public void extractsNamedCookiesAndSetCookieValues() {
    String header = "grayscale=gray; d.sid=s123; shareToken=t456; uuid=u789";
    assertEquals("s123", WebSession.cookie(header, "d.sid"));
    assertEquals("t456", WebSession.cookie(header, "shareToken"));
    assertEquals("u789", WebSession.cookie(header, "uuid"));
    assertEquals("", WebSession.cookie(header, "missing"));

    String setCookie =
        "shareToken=freshTokenValue; path=/; expires=Tue, 24 Nov 2026 02:00:00 GMT; httponly";
    assertEquals("freshTokenValue", WebSession.setCookieValue(setCookie, "shareToken"));
    assertEquals("", WebSession.setCookieValue(setCookie, "d.sid"));
  }

  @Test
  public void verifiesSignedInBindPageAndMatchesCardNumber() {
    String html = "<main class=\"main account-login\"><h2>156****1056</h2><p>您已成功登录</p></main>";
    assertEquals("156****1056", WebSession.maskedNumber(html));
    assertTrue(WebSession.signedInPage(html, "156****1056"));
    assertTrue(WebSession.signedInPage(html, " 156****1056 "));
    assertTrue(WebSession.signedInPage(html, ""));
    assertFalse(WebSession.signedInPage(html, "138****8225"));
    assertFalse(WebSession.signedInPage("<p>请输入短信验证码</p>", "156****1056"));
  }

  @Test
  public void validatesUnifiedAuthSsoRedirectLocation() {
    assertTrue(
        WebSession.validSsoRedirect(
            "https://wx.10086.cn/website/spa/complaint/list?artifact=0123456789abcdef0123456789abcdef&backUrl=https%3A%2F%2Fwx.10086.cn%2Fwebsite%2Fspa%2Fmain%2FnewHome&type=00"));
    assertFalse(
        WebSession.validSsoRedirect(
            "https://wx.10086.cn/website/spa/complaint/list?artifact=-1&backUrl=https%3A%2F%2Fwx.10086.cn%2Fwebsite%2Fspa%2Fmain%2FnewHome&type=00"));
    assertFalse(WebSession.validSsoRedirect("https://example.com/callback?artifact=0123456789abcdef0123456789abcdef"));
  }
}