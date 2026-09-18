package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.lagou.Lagou;
import com.getjobs.worker.lagou.LagouConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.DeliveryLimit;
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
            try { playwrightManager.refreshLagouLoginStatus(); } catch (Exception ignored) { }
            Map<String, Object> sessionStatus = playwrightManager.getLagouSessionStatus();
            if ("UNKNOWN".equals(sessionStatus.get("loginState"))) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "拉勾页面正在重新连接，请稍后再试"));
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
            Lagou.Result result = playwrightManager.withDeliveryPage(PLATFORM, page -> {
                Lagou lagou = lagouProvider.getObject();
                lagou.setPage(page);
                lagou.setConfig(config);
                lagou.setShouldStopCallback(this::shouldStop);
                lagou.setProgressCallback((message, current, total) -> {
                    if (current != null && total != null) {
                        progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                    } else {
                        progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                    }
                });
                lagou.prepare();
                return lagou.execute();
            });
            progressCallback.accept(switch (result.state()) {
                case ERROR, PENDING -> JobProgressMessage.error(PLATFORM, result.summary());
                case STOPPED, LIMITED -> JobProgressMessage.warning(PLATFORM, result.summary());
                case COMPLETED -> result.delivered() > 0 && result.failed() == 0
                        ? JobProgressMessage.success(PLATFORM, result.summary())
                        : JobProgressMessage.warning(PLATFORM, result.summary());
            });
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
        try { playwrightManager.refreshLagouLoginStatus(); } catch (Exception ignored) { }
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("maxDeliveryAttempts", DeliveryLimit.configuredMax());
        try { status.putAll(playwrightManager.getLagouSessionStatus()); }
        catch (Exception e) { status.put("pageState", "MISSING"); status.put("pageMessage", e.getMessage()); }
        return status;
    }

    @Override public String getPlatformName() { return PLATFORM; }
}
