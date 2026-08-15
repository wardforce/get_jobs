package com.getjobs.application.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Job51PageRepeatDeliveryTest {

    @Test
    void pageSynchronizesDeliveryStateAfterTaskFinishes() throws IOException {
        String pageSource = Files.readString(Path.of("front/app/51job/page.tsx"));

        assertTrue(pageSource.contains("/api/51job/status"));
        assertTrue(pageSource.contains("setInterval(syncDeliveryStatus"));
        assertTrue(pageSource.contains("setIsDelivering(Boolean(data.isRunning))"));
        assertTrue(pageSource.indexOf("{isDelivering ? (") < pageSource.indexOf(": !isLoggedIn ? ("),
                "运行中的任务必须优先于可能过期的登录状态显示");
        assertTrue(pageSource.contains("正在投递，点击停止"));
    }
}
