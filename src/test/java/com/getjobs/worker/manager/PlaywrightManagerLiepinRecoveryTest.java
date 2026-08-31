package com.getjobs.worker.manager;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
