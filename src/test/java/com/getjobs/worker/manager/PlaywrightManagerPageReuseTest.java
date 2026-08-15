package com.getjobs.worker.manager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightManagerPageReuseTest {
    @Test
    void findsTopLevelPlatformPageForAnIsolatedDeliveryConnection() {
        BrowserContext context = mock(BrowserContext.class);
        Page topLevel = page("https://we.51job.com/pc/search");
        Page popup = page("https://we.51job.com/pc/job/detail");
        when(popup.opener()).thenReturn(topLevel);
        when(context.pages()).thenReturn(List.of(popup, topLevel));

        PlaywrightManager manager = new PlaywrightManager();

        assertSame(topLevel, manager.findPlatformPage(context, "51job"));
    }


    @Test
    void reusesRestoredPlatformPagesAndClosesDuplicatesInsteadOfOpeningAnotherGroup() {
        BrowserContext context = mock(BrowserContext.class);
        Page blank = page("about:blank");
        Page boss = page("https://www.zhipin.com/web/geek/job");
        Page duplicateBoss = page("https://www.zhipin.com/web/geek/recommend");
        Page bossPopup = page("https://www.zhipin.com/web/geek/job_detail");
        when(bossPopup.opener()).thenReturn(boss);
        Page liepin = page("https://www.liepin.com/zhaopin/");
        Page job51 = page("https://we.51job.com/pc/search");
        Page zhilian = page("https://www.zhaopin.com/");
        Page duplicateZhilian = page("https://www.zhaopin.com/sou/jl489/kwJava");
        Page lagou = page("https://www.lagou.com/wn/jobs");

        when(context.pages()).thenReturn(List.of(
                blank,
                boss,
                duplicateBoss,
                bossPopup,
                liepin,
                job51,
                zhilian,
                duplicateZhilian,
                lagou));

        PlaywrightManager manager = new PlaywrightManager();
        manager.initializePlatformPages(context);

        assertTrue(manager.hasPage("boss"));
        assertTrue(manager.hasPage("liepin"));
        assertTrue(manager.hasPage("51job"));
        assertTrue(manager.hasPage("zhilian"));
        assertTrue(manager.hasPage("lagou"));
        verify(context, never()).newPage();
        verify(blank).close();
        verify(duplicateBoss).close();
        verify(bossPopup, never()).close();
        verify(duplicateZhilian).close();
    }

    @Test
    void createsOnlyMissingPagesAndReusesTheChromeBlankTab() {
        BrowserContext context = mock(BrowserContext.class);
        Page boss = page("https://www.zhipin.com/");
        Page blank = page("about:blank");
        Page job51 = page("about:blank");
        Page zhilian = page("https://www.zhaopin.com/");
        Page lagou = page("about:blank");
        when(context.pages()).thenReturn(List.of(boss, blank));
        when(context.newPage()).thenReturn(job51, zhilian, lagou);

        PlaywrightManager manager = new PlaywrightManager();
        manager.initializePlatformPages(context);

        verify(context, times(3)).newPage();
        verify(blank, never()).close();
        assertTrue(manager.hasPage("boss"));
        assertTrue(manager.hasPage("liepin"));
        assertTrue(manager.hasPage("51job"));
        assertTrue(manager.hasPage("zhilian"));
        assertTrue(manager.hasPage("lagou"));
    }

    private static Page page(String url) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);
        when(page.isClosed()).thenReturn(false);
        return page;
    }
}
