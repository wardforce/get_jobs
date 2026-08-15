package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LagouAccessSliderTest {
    @Test
    void dragsVisibleSliderToTrackEnd() {
        Page page = mock(Page.class);
        Locator challengeQuery = mock(Locator.class);
        Locator challenge = mock(Locator.class);
        Locator trackQuery = mock(Locator.class);
        Locator sliderQuery = mock(Locator.class);
        Locator track = mock(Locator.class);
        Locator slider = mock(Locator.class);
        Mouse mouse = mock(Mouse.class);

        when(page.locator("#aliyunCaptcha-sliding-wrapper, #waf_nc_block"))
                .thenReturn(challengeQuery);
        when(challengeQuery.first()).thenReturn(challenge);
        when(challenge.isVisible()).thenReturn(false, true, true);
        when(page.locator("#aliyunCaptcha-sliding-body, .nc_scale")).thenReturn(trackQuery);
        when(page.locator("#aliyunCaptcha-sliding-slider, .nc_scale .btn_slide, .nc_scale .nc_iconfont"))
                .thenReturn(sliderQuery);
        when(trackQuery.first()).thenReturn(track);
        when(sliderQuery.first()).thenReturn(slider);
        when(track.isVisible()).thenReturn(true);
        when(slider.isVisible()).thenReturn(true);
        when(track.boundingBox()).thenReturn(box(100, 200, 500, 50));
        when(slider.boundingBox()).thenReturn(box(100, 200, 50, 50));
        when(page.mouse()).thenReturn(mouse);

        assertTrue(PlaywrightManager.dragLagouAccessSlider(page));
        verify(page).waitForTimeout(250);
        verify(mouse).down();
        verify(mouse).move(eq(575.0), eq(225.0));
        verify(mouse).up();
    }

    @Test
    void usesGaussianVariationForVerticalJitterAndStepTiming() {
        Page page = mock(Page.class);
        Locator challengeQuery = mock(Locator.class);
        Locator challenge = mock(Locator.class);
        Locator trackQuery = mock(Locator.class);
        Locator sliderQuery = mock(Locator.class);
        Locator track = mock(Locator.class);
        Locator slider = mock(Locator.class);
        Mouse mouse = mock(Mouse.class);
        RandomGenerator random = mock(RandomGenerator.class);

        when(page.locator("#aliyunCaptcha-sliding-wrapper, #waf_nc_block"))
                .thenReturn(challengeQuery);
        when(challengeQuery.first()).thenReturn(challenge);
        when(challenge.isVisible()).thenReturn(true);
        when(page.locator("#aliyunCaptcha-sliding-body, .nc_scale")).thenReturn(trackQuery);
        when(page.locator("#aliyunCaptcha-sliding-slider, .nc_scale .btn_slide, .nc_scale .nc_iconfont"))
                .thenReturn(sliderQuery);
        when(trackQuery.first()).thenReturn(track);
        when(sliderQuery.first()).thenReturn(slider);
        when(track.isVisible()).thenReturn(true);
        when(slider.isVisible()).thenReturn(true);
        when(track.boundingBox()).thenReturn(box(100, 200, 500, 50));
        when(slider.boundingBox()).thenReturn(box(100, 200, 50, 50));
        when(page.mouse()).thenReturn(mouse);

        double[] gaussianValues = {-1.4, 0.7, -0.3, 1.2, -0.8, 0.2, 1.6, -1.1, 0.5};
        AtomicInteger gaussianIndex = new AtomicInteger();
        when(random.nextGaussian()).thenAnswer(invocation ->
                gaussianValues[Math.floorMod(gaussianIndex.getAndIncrement(), gaussianValues.length)]);

        assertTrue(PlaywrightManager.dragLagouAccessSlider(page, random));

        verify(random, atLeast(20)).nextGaussian();
        ArgumentCaptor<Double> xCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> yCaptor = ArgumentCaptor.forClass(Double.class);
        verify(mouse, atLeast(10)).move(xCaptor.capture(), yCaptor.capture());
        List<Double> yValues = yCaptor.getAllValues();
        assertTrue(yValues.stream().anyMatch(value -> value > 225.2));
        assertTrue(yValues.stream().anyMatch(value -> value < 224.8));

        ArgumentCaptor<Double> delayCaptor = ArgumentCaptor.forClass(Double.class);
        verify(page, atLeast(10)).waitForTimeout(delayCaptor.capture());
        long distinctStepDelays = delayCaptor.getAllValues().stream()
                .filter(value -> value >= 6 && value <= 60)
                .map(Math::round)
                .distinct()
                .count();
        assertTrue(distinctStepDelays >= 5);
    }

    @Test
    void clicksCenterRetryBeforeReloadWhenNextSlidePasses() throws Exception {
        VerificationScenario scenario = new VerificationScenario(2);

        scenario.runNextAttempt();
        scenario.runNextAttempt();

        verify(scenario.retryButton).click();
        verify(scenario.page, never()).reload(any(Page.ReloadOptions.class));
        assertEquals(2, scenario.slideAttempts.get());
    }

    @Test
    void refreshesAfterTwoInlineRetriesAndKeepsTryingUntilPassed() throws Exception {
        VerificationScenario scenario = new VerificationScenario(4);

        scenario.runNextAttempt();
        scenario.runNextAttempt();
        scenario.runNextAttempt();
        scenario.runNextAttempt();

        verify(scenario.retryButton, org.mockito.Mockito.times(2)).click();
        verify(scenario.page).reload(any(Page.ReloadOptions.class));
        assertEquals(4, scenario.slideAttempts.get());
    }

    @Test
    void refreshesWhenCenterRetryClickFailsAndKeepsTrying() throws Exception {
        VerificationScenario scenario = new VerificationScenario(2);
        doThrow(new RuntimeException("retry control detached"))
                .when(scenario.retryButton).click();

        assertDoesNotThrow(scenario::runNextAttempt);
        assertDoesNotThrow(scenario::runNextAttempt);

        verify(scenario.page).reload(any(Page.ReloadOptions.class));
        assertEquals(2, scenario.slideAttempts.get());
    }

    @Test
    void opensLagouWhenLegacySessionCleanupFails() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        CookieService cookieService = mock(CookieService.class);

        when(context.cookies()).thenReturn(List.of());
        doThrow(new RuntimeException("cookie table unavailable"))
                .when(cookieService).clearCookieByPlatform(eq("lagou"), any(String.class));

        setField(manager, "lagouPage", page);
        setField(manager, "context", context);
        setField(manager, "persistentBrowserContext", true);
        setField(manager, "cookieService", cookieService);

        Method setup = PlaywrightManager.class.getDeclaredMethod("setupLagouPlatform");
        setup.setAccessible(true);
        setup.invoke(manager);

        verify(page).navigate(any(String.class), any(Page.NavigateOptions.class));
    }

    @Test
    void increasesVerificationRetryBackoffAndCapsAtTenMinutes() {
        assertEquals(30_000L, PlaywrightManager.lagouRetryBackoffMs(1));
        assertEquals(60_000L, PlaywrightManager.lagouRetryBackoffMs(2));
        assertEquals(120_000L, PlaywrightManager.lagouRetryBackoffMs(3));
        assertEquals(300_000L, PlaywrightManager.lagouRetryBackoffMs(4));
        assertEquals(600_000L, PlaywrightManager.lagouRetryBackoffMs(5));
        assertEquals(600_000L, PlaywrightManager.lagouRetryBackoffMs(20));
    }

    private static final class VerificationScenario {
        private final Page page = mock(Page.class);
        private final Locator retryButton = mock(Locator.class);
        private final AtomicInteger slideAttempts = new AtomicInteger();
        private final AtomicBoolean challengeVisible = new AtomicBoolean(true);
        private final PlaywrightManager manager = new PlaywrightManager();
        private final int passOnAttempt;

        private VerificationScenario(int passOnAttempt) throws Exception {
            this.passOnAttempt = passOnAttempt;
            Locator challengeQuery = mock(Locator.class);
            Locator challenge = mock(Locator.class);
            Locator trackQuery = mock(Locator.class);
            Locator sliderQuery = mock(Locator.class);
            Locator track = mock(Locator.class);
            Locator slider = mock(Locator.class);
            Locator retryQuery = mock(Locator.class);
            Locator empty = mock(Locator.class);
            Mouse mouse = mock(Mouse.class);

            when(challengeQuery.first()).thenReturn(challenge);
            when(challenge.isVisible()).thenAnswer(invocation -> challengeVisible.get());
            when(trackQuery.first()).thenReturn(track);
            when(sliderQuery.first()).thenReturn(slider);
            when(track.isVisible()).thenReturn(true);
            when(slider.isVisible()).thenReturn(true);
            when(track.boundingBox()).thenReturn(box(100, 200, 500, 50));
            when(slider.boundingBox()).thenReturn(box(100, 200, 50, 50));
            when(retryQuery.first()).thenReturn(retryButton);
            when(retryButton.isVisible()).thenReturn(true);
            when(empty.first()).thenReturn(empty);
            when(empty.isVisible()).thenReturn(false);
            when(empty.textContent()).thenReturn("");
            when(page.mouse()).thenReturn(mouse);

            when(page.locator(any(String.class))).thenAnswer(invocation -> {
                String selector = invocation.getArgument(0);
                if ("#aliyunCaptcha-sliding-wrapper, #waf_nc_block".equals(selector)) {
                    return challengeQuery;
                }
                if ("#aliyunCaptcha-sliding-body, .nc_scale".equals(selector)) {
                    return trackQuery;
                }
                if (selector.contains(".nc_scale .btn_slide")) {
                    return sliderQuery;
                }
                if ("#aliyunCaptcha-sliding-slider".equals(selector)) {
                    return slider;
                }
                if (selector.contains("验证失败") || selector.contains("请刷新")
                        || selector.toLowerCase().contains("refresh")) {
                    return retryQuery;
                }
                return empty;
            });

            doAnswer(invocation -> {
                if (slideAttempts.incrementAndGet() >= this.passOnAttempt) {
                    challengeVisible.set(false);
                }
                return null;
            }).when(mouse).up();

            setField(manager, "lagouPage", page);
        }

        private void runNextAttempt() throws Exception {
            setField(manager, "lagouNextVerificationAttemptAtMs", 0L);
            Method method = PlaywrightManager.class.getDeclaredMethod("handleLagouAccessVerification");
            method.setAccessible(true);
            method.invoke(manager);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static BoundingBox box(double x, double y, double width, double height) {
        BoundingBox box = new BoundingBox();
        box.x = x;
        box.y = y;
        box.width = width;
        box.height = height;
        return box;
    }
}
