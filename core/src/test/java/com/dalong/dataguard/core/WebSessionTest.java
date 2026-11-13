package com.dalong.dataguard.core;

import static org.junit.Assert.assertEquals;

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
}