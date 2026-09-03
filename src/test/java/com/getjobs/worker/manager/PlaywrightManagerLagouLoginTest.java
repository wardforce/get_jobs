package com.getjobs.worker.manager;

import org.junit.jupiter.api.Test;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaywrightManagerLagouLoginTest {
    @Test
    void classifiesResumeNavigationAsLoggedIn() {
        assertEquals(PlaywrightManager.LagouLoginState.LOGGED_IN,
                PlaywrightManager.classifyLagouLoginState("拉勾首页 我的简历 吴振华", true, false));
    }

    @Test
    void classifiesExactLoginEntryAsLoggedOut() {
        assertEquals(PlaywrightManager.LagouLoginState.LOGGED_OUT,
                PlaywrightManager.classifyLagouLoginState("拉勾首页", false, true));
    }

    @Test
    void classifiesEmptyRenderingStateAsUnknown() {
        assertEquals(PlaywrightManager.LagouLoginState.UNKNOWN,
                PlaywrightManager.classifyLagouLoginState("", false, false));
    }

    @Test
    void keepsConfirmedLoginWhenPageIsStillRendering() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        manager.setLoginStatus("lagou", true);
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        Locator signals = mock(Locator.class);
        when(page.isClosed()).thenReturn(false);
        when(page.url()).thenReturn("https://www.lagou.com/wn/jobs");
        when(page.locator("body")).thenReturn(body);
        when(page.locator(anyString())).thenReturn(signals);
        when(body.count()).thenReturn(1);
        when(body.innerText()).thenReturn("  ");
        when(signals.count()).thenReturn(0);
        Field pageField = PlaywrightManager.class.getDeclaredField("lagouPage");
        pageField.setAccessible(true);
        pageField.set(manager, page);

        var check = PlaywrightManager.class.getDeclaredMethod("checkLagouLoginStatus");
        check.setAccessible(true);
        check.invoke(manager);

        org.junit.jupiter.api.Assertions.assertTrue(manager.isLoggedIn("lagou"));
        org.junit.jupiter.api.Assertions.assertEquals("LOGGED_IN", manager.getLagouSessionStatus().get("loginState"));
    }
}
