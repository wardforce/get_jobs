package com.getjobs.worker.zhilian;

import com.getjobs.application.service.ZhilianService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhilianPopupLifecycleTest {

    @Test
    void closesCurrentSuccessPopupBeforeClickingNextJob() throws Exception {
        DeliveryScenario scenario = new DeliveryScenario(true);
        doAnswer(invocation -> {
            assertTrue(scenario.firstPopupClosed.get(),
                    "第二个岗位被点击时，第一个投递成功弹窗仍未关闭");
            return null;
        }).when(scenario.secondApply).click();

        assertDoesNotThrow(scenario::deliverCurrentPage);
    }

    @Test
    void abortsDeliveryWhenSuccessPopupCannotBeConfirmedClosed() throws Exception {
        DeliveryScenario scenario = new DeliveryScenario(false);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                scenario::deliverCurrentPage);

        assertEquals("ZhilianPopupCloseException", error.getCause().getClass().getSimpleName());
        verify(scenario.secondApply, never()).click();
    }

    private static final class DeliveryScenario {
        private final Page page = mock(Page.class);
        private final Locator secondApply = mock(Locator.class);
        private final AtomicBoolean firstPopupClosed = new AtomicBoolean(false);
        private final Method deliverCurrentPage;
        private final ZhiLian zhilian;

        private DeliveryScenario(boolean firstPopupCloseCompletes) throws Exception {
            ZhilianService service = mock(ZhilianService.class);
            BrowserContext context = mock(BrowserContext.class);
            Locator cards = mock(Locator.class);
            Locator firstCard = mock(Locator.class);
            Locator secondCard = mock(Locator.class);
            Locator firstTitle = mock(Locator.class);
            Locator secondTitle = mock(Locator.class);
            Locator firstApply = mock(Locator.class);
            Locator empty = mock(Locator.class);
            Locator loginModal = mock(Locator.class);
            Locator limitModal = mock(Locator.class);
            Page firstPopup = mock(Page.class);
            Page secondPopup = mock(Page.class);

            when(page.context()).thenReturn(context);
            when(cards.count()).thenReturn(2);
            when(cards.nth(0)).thenReturn(firstCard);
            when(cards.nth(1)).thenReturn(secondCard);
            when(empty.count()).thenReturn(0);
            when(loginModal.count()).thenReturn(0);
            when(limitModal.count()).thenReturn(0);
            when(firstApply.count()).thenReturn(1);
            when(secondApply.count()).thenReturn(1);

            configureCard(firstCard, firstTitle, firstApply, "JOB-1", empty);
            configureCard(secondCard, secondTitle, secondApply, "JOB-2", empty);

            when(page.locator(any(String.class))).thenAnswer(invocation -> {
                String selector = invocation.getArgument(0);
                if ("div.joblist-box__item".equals(selector)) {
                    return cards;
                }
                if (selector.contains("zppp-panel-login")) {
                    return loginModal;
                }
                if ("//div[@class='a-job-apply-workflow']".equals(selector)) {
                    return limitModal;
                }
                return empty;
            });

            doAnswer(invocation -> {
                if (firstPopupCloseCompletes) {
                    firstPopupClosed.set(true);
                }
                return null;
            }).when(firstPopup).close();
            when(firstPopup.isClosed()).thenAnswer(invocation -> firstPopupClosed.get());

            AtomicBoolean secondPopupClosed = new AtomicBoolean(false);
            doAnswer(invocation -> {
                secondPopupClosed.set(true);
                return null;
            }).when(secondPopup).close();
            when(secondPopup.isClosed()).thenAnswer(invocation -> secondPopupClosed.get());

            AtomicInteger popupIndex = new AtomicInteger();
            when(page.waitForPopup(any(Page.WaitForPopupOptions.class), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        Runnable click = invocation.getArgument(1);
                        click.run();
                        return popupIndex.getAndIncrement() == 0 ? firstPopup : secondPopup;
                    });

            zhilian = new ZhiLian(service);
            zhilian.setPage(page);
            deliverCurrentPage = ZhiLian.class.getDeclaredMethod("deliverCurrentPage", String.class);
            deliverCurrentPage.setAccessible(true);
        }

        private Object deliverCurrentPage() throws Exception {
            return deliverCurrentPage.invoke(zhilian, "Java");
        }
    }

    private static void configureCard(
            Locator card,
            Locator title,
            Locator apply,
            String jobId,
            Locator empty) {
        when(title.count()).thenReturn(1);
        when(title.textContent()).thenReturn("职位-" + jobId);
        when(title.getAttribute("href")).thenReturn("https://www.zhaopin.com/jobdetail/" + jobId + ".htm");
        when(card.locator(any(String.class))).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if ("a.jobinfo__name".equals(selector)) {
                return title;
            }
            if ("button.collect-and-apply__btn".equals(selector)) {
                return apply;
            }
            return empty;
        });
    }
}
