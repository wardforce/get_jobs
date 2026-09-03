package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.lagou.Lagou;
import com.getjobs.worker.lagou.LagouConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class LagouJobService extends InterruptibleJobPlatformService {
    private static final String PLATFORM = "lagou";
    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<Lagou> lagouProvider;
    private final ConfigService configService;

    @Override
    protected void doExecuteDelivery(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (!playwrightManager.hasPage(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "拉勾页面未初始化"));
                return;
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录拉勾"));
                return;
            }
            playwrightManager.pauseLagouMonitoring();
            LagouConfig config = configService.getLagouConfig();
            if (!Lagou.isConfigValid(config)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先配置拉勾搜索关键词"));
                return;
            }
            int delivered = playwrightManager.withDeliveryPage(PLATFORM, page -> {
                Lagou lagou = lagouProvider.getObject();
                lagou.setPage(page);
                lagou.setConfig(config);
                lagou.setShouldStopCallback(this::shouldStop);
                lagou.setProgressCallback((message, current, total) -> progressCallback.accept(
                        current == null ? JobProgressMessage.info(PLATFORM, message)
                                : JobProgressMessage.progress(PLATFORM, message, current, total)));
                lagou.prepare();
                return lagou.execute();
            });
            progressCallback.accept(shouldStop()
                    ? JobProgressMessage.warning(PLATFORM, "拉勾投递任务已停止")
                    : JobProgressMessage.success(PLATFORM, String.format("投递任务完成，共投递%d个职位", delivered)));
        } catch (Exception e) {
            log.error("拉勾投递任务执行失败", e);
            progressCallback.accept(shouldStop() ? JobProgressMessage.warning(PLATFORM, "拉勾投递任务已停止")
                    : JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            playwrightManager.resumeLagouMonitoring();
        }
    }

    @Override public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM); status.put("isRunning", isRunning());
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.putAll(playwrightManager.getLagouPageStatus());
        return status;
    }

    @Override public String getPlatformName() { return PLATFORM; }
}
