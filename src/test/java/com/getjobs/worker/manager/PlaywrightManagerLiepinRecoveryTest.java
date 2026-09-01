package com.getjobs.worker.manager;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class PlaywrightManagerLiepinRecoveryTest {
    @Test
    void rejectsBlankPageAsLiepinPageReady() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("about:blank");
        when(page.isClosed()).thenReturn(false);

        PlaywrightManager manager = new PlaywrightManager();

        assertFalse(manager.isLiepinPageReady(page));
    }

    @Test
    void acceptsRenderedLiepinPageAsReady() {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        when(page.url()).thenReturn("https://www.liepin.com/");
        when(page.isClosed()).thenReturn(false);
        when(page.locator("body")).thenReturn(body);
        when(body.count()).thenReturn(1);
        when(body.innerText()).thenReturn("登录/注册 搜索职位");

        PlaywrightManager manager = new PlaywrightManager();

        assertTrue(manager.isLiepinPageReady(page));
    }

    @Test
    void rejectsLiepinPageWithEmptyBody() {
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        when(page.url()).thenReturn("https://www.liepin.com/");
        when(page.isClosed()).thenReturn(false);
        when(page.locator("body")).thenReturn(body);
        when(body.count()).thenReturn(1);
        when(body.innerText()).thenReturn("  ");

        PlaywrightManager manager = new PlaywrightManager();

        assertFalse(manager.isLiepinPageReady(page));
    }

    @Test
    void keepsConfirmedLoginWhenLiepinPageIsStillRendering() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.liepin.com/");
        when(page.isClosed()).thenReturn(false);
        Locator body = mock(Locator.class);
        when(page.locator("body")).thenReturn(body);
        when(body.count()).thenReturn(1);
        when(body.innerText()).thenReturn("  ");
        manager.setLoginStatus("liepin", true);

        var check = PlaywrightManager.class.getDeclaredMethod("checkLiepinLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertTrue(manager.isLoggedIn("liepin"));
        assertEquals("UNKNOWN", manager.getLiepinSessionStatus().get("loginState"));
    }

    @Test
    void detectsExplicitLiepinLoginEntryAsLoggedOut() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        Locator loginEntry = mock(Locator.class);
        when(page.url()).thenReturn("https://www.liepin.com/");
        when(page.isClosed()).thenReturn(false);
        when(page.locator("body")).thenReturn(body);
        when(page.locator(anyString())).thenAnswer(invocation ->
                "body".equals(invocation.<String>getArgument(0)) ? body : loginEntry);
        when(body.count()).thenReturn(1);
        when(body.innerText()).thenReturn("猎聘首页");
        when(loginEntry.first()).thenReturn(loginEntry);
        when(loginEntry.isVisible()).thenReturn(true);

        var detect = PlaywrightManager.class.getDeclaredMethod("detectLiepinLoginState", Page.class);
        detect.setAccessible(true);

        assertEquals("LOGGED_OUT", detect.invoke(manager, page).toString());
    }
}
