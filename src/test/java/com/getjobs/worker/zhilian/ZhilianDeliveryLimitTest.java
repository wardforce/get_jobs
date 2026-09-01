package com.getjobs.worker.zhilian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianDeliveryLimitTest {
    @Test
    void recognizesCurrentDailyLimitModalText() {
        assertTrue(ZhiLian.isDeliveryLimitMessage("今日投递已超过上限，明天再试吧！"));
        assertTrue(ZhiLian.isDeliveryLimitMessage("今日投递已达上限"));
    }
}
