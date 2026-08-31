package com.getjobs.worker.zhilian;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.ZhilianJobService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhilianAuthenticationExpiredTest {

    @Test
    void loginModalStopsSubmissionImmediately() throws Exception {
        Page page = mock(Page.class);
        Locator loginModal = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(loginModal);
        when(loginModal.count()).thenReturn(1);
        when(loginModal.first()).thenReturn(loginModal);
        when(loginModal.isVisible()).thenReturn(true);

        ZhiLian zhilian = new ZhiLian(mock(ZhilianService.class));
        zhilian.setPage(page);
        Method check = ZhiLian.class.getDeclaredMethod("ensureZhilianSession");
        check.setAccessible(true);

        InvocationTargetException error =
                assertThrows(InvocationTargetException.class, () -> check.invoke(zhilian));
        assertInstanceOf(ZhilianAuthenticationExpiredException.class, error.getCause());
    }

    @Test
    void serviceMarksLoginExpiredAndDoesNotEmitSuccess() {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        ConfigService configService = mock(ConfigService.class);
        ObjectProvider<ZhiLian> zhilianProvider = mock(ObjectProvider.class);
        when(manager.hasPage("zhilian")).thenReturn(true);
        when(manager.isLoggedIn("zhilian")).thenReturn(true);
        when(configService.getZhilianConfig()).thenReturn(new ZhilianConfig());
        doThrow(new ZhilianAuthenticationExpiredException("expired"))
                .when(manager).withDeliveryPage(eq("zhilian"), any());

        ZhilianJobService service = new ZhilianJobService(manager, zhilianProvider, configService);
        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        verify(manager).setLoginStatus("zhilian", false);
        JobProgressMessage expired = messages.stream()
                .filter(message -> "ZHILIAN_COOKIE_EXPIRED".equals(message.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("error", expired.getType());
        assertEquals(0, messages.stream().filter(message -> "success".equals(message.getType())).count());
    }

    @Test
    void managerChangesLoggedInStateWhenLoginModalAppears() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator loginModal = mock(Locator.class);
        Locator empty = mock(Locator.class);
        when(loginModal.count()).thenReturn(1);
        when(loginModal.first()).thenReturn(loginModal);
        when(loginModal.isVisible()).thenReturn(true);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        when(page.locator(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("zppp-panel") ? loginModal : empty);
        setField(manager, "zhilianPage", page);
        manager.setLoginStatus("zhilian", true);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertFalse(manager.isLoggedIn("zhilian"));
    }

    @Test
    void transientPageWithoutLoginMarkersDoesNotBecomeLoggedIn() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        setField(manager, "zhilianPage", page);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertFalse(manager.isLoggedIn("zhilian"));
        assertEquals("UNKNOWN", manager.getZhilianSessionStatus().get("loginState"));
    }

    @Test
    void transientUnknownStateDoesNotClearConfirmedLogin() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        setField(manager, "zhilianPage", page);
        manager.setLoginStatus("zhilian", true);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertTrue(manager.isLoggedIn("zhilian"));
        assertEquals("LOGGED_IN", manager.getZhilianSessionStatus().get("loginState"));
    }

    @Test
    void explicitLoggedInUrlStillConfirmsLogin() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        when(page.url()).thenReturn("https://i.zhaopin.com/");
        when(page.locator(anyString())).thenReturn(empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        setField(manager, "zhilianPage", page);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertTrue(manager.isLoggedIn("zhilian"));
        assertEquals("LOGGED_IN", manager.getZhilianSessionStatus().get("loginState"));
    }

    @Test
    void currentZhilianHomeLoginMarkerConfirmsLogin() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        Locator loggedInMarker = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhaopin.com/");
        when(page.locator(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("c-login__top__name")
                        ? loggedInMarker : empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        when(loggedInMarker.count()).thenReturn(1);
        when(loggedInMarker.first()).thenReturn(loggedInMarker);
        when(loggedInMarker.nth(0)).thenReturn(loggedInMarker);
        when(loggedInMarker.isVisible()).thenReturn(true);
        setField(manager, "zhilianPage", page);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertTrue(manager.isLoggedIn("zhilian"));
        assertEquals("LOGGED_IN", manager.getZhilianSessionStatus().get("loginState"));
    }

    @Test
    void visibleZhilianLoginMarkerAfterHiddenMatchConfirmsLogin() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        Locator markers = mock(Locator.class);
        Locator hiddenMarker = mock(Locator.class);
        Locator visibleMarker = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhaopin.com/");
        when(page.locator(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("c-login__top__name")
                        ? markers : empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        when(markers.count()).thenReturn(2);
        when(markers.nth(0)).thenReturn(hiddenMarker);
        when(markers.nth(1)).thenReturn(visibleMarker);
        when(hiddenMarker.isVisible()).thenReturn(false);
        when(visibleMarker.isVisible()).thenReturn(true);
        setField(manager, "zhilianPage", page);

        Method check = PlaywrightManager.class.getDeclaredMethod("checkZhilianLoginStatus", Page.class);
        check.setAccessible(true);
        check.invoke(manager, page);

        assertTrue(manager.isLoggedIn("zhilian"));
        assertEquals("LOGGED_IN", manager.getZhilianSessionStatus().get("loginState"));
    }

    @Test
    void loginTriggerReturnsAfterOpeningLoginEntry() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhaopin.com/");
        when(page.locator(anyString())).thenReturn(empty);
        when(empty.count()).thenReturn(0);
        when(empty.first()).thenReturn(empty);
        setField(manager, "zhilianPage", page);

        Method trigger = PlaywrightManager.class.getDeclaredMethod("triggerZhilianLoginInternal");
        trigger.setAccessible(true);
        trigger.invoke(manager);

        verify(page, never()).waitForURL(anyString(), any());
        verify(page, never()).waitForSelector(anyString(), any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
