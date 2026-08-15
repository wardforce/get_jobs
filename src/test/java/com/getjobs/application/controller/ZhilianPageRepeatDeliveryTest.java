package com.getjobs.application.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianPageRepeatDeliveryTest {

    @Test
    void pageSynchronizesDeliveryStateAfterTaskFinishes() throws IOException {
        String pageSource = Files.readString(Path.of("front/app/zhilian/page.tsx"));

        assertTrue(pageSource.contains("/api/zhilian/status"),
                "智联页面必须查询后端任务状态，才能在一轮投递结束后恢复开始按钮");
        assertTrue(pageSource.contains("setInterval(syncDeliveryStatus"),
                "智联页面必须持续同步任务状态，不能只在页面加载时查询一次");
        assertTrue(pageSource.contains("setIsDelivering(Boolean(data.isRunning))"),
                "智联页面必须用后端 isRunning 状态同步投递按钮");
        assertTrue(pageSource.indexOf("{isDelivering ? (") < pageSource.indexOf(": checkingLogin ? ("),
                "运行中的任务必须优先显示并允许停止");
    }
}
