package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.DeliveryLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 猎聘任务服务
 * 负责猎聘平台的自动投递任务管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobService extends InterruptibleJobPlatformService {

    private static final String PLATFORM = "liepin";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;

    @Override
    protected void doExecuteDelivery(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (!playwrightManager.hasPage(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录猎聘"));
                return;
            }

            // 暂停后台登录监控，避免并发访问冲突
            playwrightManager.pauseLiepinMonitoring();

            // 加载配置（统一通过 ConfigService 从专表读取）
            LiepinConfig config = configService.getLiepinConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建并执行 Bean
            Liepin.ProgressCallback cb = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            int deliveredCount = playwrightManager.withDeliveryPage(PLATFORM, page -> {
                Liepin liepin = liepinProvider.getObject();
                liepin.setPage(page);
                liepin.setConfig(config);
                liepin.setProgressCallback(cb);
                liepin.setShouldStopCallback(this::shouldStop);
                liepin.prepare();
                return liepin.execute();
            });

            if (shouldStop()) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM, "猎聘投递任务已停止"));
            } else {
                progressCallback.accept(JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
            }
        } catch (Exception e) {
            if (shouldStop()) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM, "猎聘投递任务已停止"));
            } else {
                log.error("猎聘投递任务执行失败", e);
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            }
        } finally {
            try {
                playwrightManager.resumeLiepinMonitoring();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 获取任务状态
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning());
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.putAll(playwrightManager.getLiepinSessionStatus());
        status.put("maxDeliveryAttempts", DeliveryLimit.configuredMax());
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

}
