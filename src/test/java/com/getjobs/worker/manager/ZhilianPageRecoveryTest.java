package com.getjobs.worker.manager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhilianPageRecoveryTest {

    @Test
    void rebindsClosedReferenceToLiveTopLevelPageAndPreservesPopup() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        BrowserContext context = mock(BrowserContext.class);
        Page stale = page("https://www.zhaopin.com/", true);
        Page main = page("https://www.zhaopin.com/sou/jl489/kwJava", false);
        Page popup = page("https://www.zhaopin.com/job/detail", false);
        when(popup.opener()).thenReturn(main);
        when(context.pages()).thenReturn(List.of(main, popup));
        setField(manager, "context", context);
        setField(manager, "zhilianPage", stale);

        Page rebound = invokeResolve(manager, false);

        assertSame(main, rebound);
        assertSame(main, getField(manager, "zhilianPage"));
        assertTrue("CONNECTED".equals(manager.getZhilianSessionStatus().get("pageState")));
        verify(context, never()).newPage();
        verify(popup, never()).close();
    }

    @Test
    void passiveRecoveryDoesNotCreatePageOrClearConfirmedLogin() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        BrowserContext context = mock(BrowserContext.class);
        Page stale = page("https://www.zhaopin.com/", true);
        when(context.pages()).thenReturn(List.of());
        setField(manager, "context", context);
        setField(manager, "zhilianPage", stale);
        manager.setLoginStatus("zhilian", true);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, stale);

        assertTrue(manager.isLoggedIn("zhilian"));
        assertTrue("MISSING".equals(manager.getZhilianSessionStatus().get("pageState")));
        verify(context, never()).newPage();
    }

    private static Page invokeResolve(PlaywrightManager manager, boolean createIfMissing) throws Exception {
        Method resolve = PlaywrightManager.class.getDeclaredMethod("resolveLiveZhilianPage", boolean.class);
        resolve.setAccessible(true);
        return (Page) resolve.invoke(manager, createIfMissing);
    }

    private static Page page(String url, boolean closed) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);
        when(page.isClosed()).thenReturn(closed);
        return page;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
