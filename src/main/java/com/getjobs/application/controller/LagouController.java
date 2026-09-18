package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.LagouConfigEntity;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.LagouService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.lagou.Lagou;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.LagouJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api/lagou")
public class LagouController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;
    private final LagouService lagouService;
    private final LagouJobService lagouJobService;
    private final List<SseEmitter> progressEmitters = new CopyOnWriteArrayList<>();

    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> loginStatus() {
        try { playwrightManager.refreshLagouLoginStatus(); } catch (Exception ignored) { }
        Map<String, Object> result = new HashMap<>(playwrightManager.getLagouSessionStatus());
        result.put("success", true);
        result.put("isLoggedIn", "LOGGED_IN".equals(result.get("loginState")));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        try {
            playwrightManager.triggerLagouLogin();
            return ResponseEntity.ok(Map.of("success", true, "message", "已打开拉勾登录页，请完成验证或登录"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "打开拉勾登录页失败: " + e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        playwrightManager.setLoginStatus("lagou", false);
        playwrightManager.clearLagouCookies();
        cookieService.clearCookieByPlatform("lagou", "manual logout");
        return ResponseEntity.ok(Map.of("success", true, "message", "拉勾已退出登录"));
    }

    @PostMapping("/save-cookie")
    public ResponseEntity<Map<String, Object>> saveCookie() {
        playwrightManager.saveLagouCookiesToDb("manual save");
        return ResponseEntity.ok(Map.of("success", true, "message", "已保存拉勾 Cookie"));
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        LagouConfigEntity config = lagouService.getFirstConfig();
        if (config == null) config = new LagouConfigEntity();
        if (config.getCity() == null || config.getCity().isBlank()) config.setCity("全国");
        if (config.getResumeType() == null || config.getResumeType().isBlank()) config.setResumeType("ONLINE");
        if (config.getMaxCount() == null || config.getMaxCount() <= 0) config.setMaxCount(30);
        result.put("config", config);
        result.put("options", Map.of("city", lagouService.getOptionsByType("city")));
        return result;
    }

    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody LagouConfigEntity config) {
        try {
            validateConfig(config);
            return ResponseEntity.ok(lagouService.updateConfig(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("保存拉勾配置失败", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "保存拉勾配置失败: " + e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        try {
            try { playwrightManager.refreshLagouLoginStatus(); } catch (Exception ignored) { }
            String loginState = String.valueOf(playwrightManager.getLagouSessionStatus().get("loginState"));
            if ("UNKNOWN".equals(loginState)) {
                return ResponseEntity.status(409).body(Map.of("success", false, "message", "拉勾页面正在重新连接，请稍后再试", "status", "unknown"));
            }
            if (!"LOGGED_IN".equals(loginState)) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请先登录拉勾", "status", "not_logged_in"));
            }
            boolean started = lagouJobService.startDelivery(this::sendProgress);
            if (!started) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "拉勾任务已在运行中，请等待当前任务完成", "status", "running"));
            return ResponseEntity.ok(Map.of("success", true, "message", "拉勾任务启动成功", "status", "started"));
        } catch (Exception e) {
            log.error("启动拉勾任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "启动拉勾任务失败: " + e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        if (!lagouJobService.isRunning()) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "没有正在运行的拉勾任务"));
        lagouJobService.stopDelivery();
        return ResponseEntity.ok(Map.of("success", true, "message", "拉勾任务停止请求已发送"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            Map<String, Object> result = new HashMap<>(lagouJobService.getStatus());
            result.put("success", true);
            result.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "获取拉勾状态失败: " + e.getMessage()));
        }
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        progressEmitters.add(emitter);
        Runnable remove = () -> progressEmitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到拉勾投递进度推送")));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        for (SseEmitter emitter : progressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                progressEmitters.remove(emitter);
            }
        }
    }

    @GetMapping("/stats")
    public LagouService.StatsResponse stats(@RequestParam(required = false) String statuses,
                                            @RequestParam(required = false) String location,
                                            @RequestParam(required = false) String experience,
                                            @RequestParam(required = false) String degree,
                                            @RequestParam(required = false) Double minK,
                                            @RequestParam(required = false) Double maxK,
                                            @RequestParam(required = false) String keyword) {
        return lagouService.getLagouStats(parseStatuses(statuses), location, experience, degree, minK, maxK, keyword);
    }

    @GetMapping("/list")
    public LagouService.PagedResult list(@RequestParam(required = false) String statuses,
                                         @RequestParam(required = false) String location,
                                         @RequestParam(required = false) String experience,
                                         @RequestParam(required = false) String degree,
                                         @RequestParam(required = false) Double minK,
                                         @RequestParam(required = false) Double maxK,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "20") Integer size) {
        return lagouService.listLagouJobs(parseStatuses(statuses), location, experience, degree, minK, maxK, keyword, page, size);
    }

    private void sendProgress(JobProgressMessage message) {
        for (SseEmitter emitter : progressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(OBJECT_MAPPER.writeValueAsString(message)));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException || e instanceof ClientAbortException) {
                    try { emitter.complete(); } catch (Exception ignored) { }
                }
                progressEmitters.remove(emitter);
            }
        }
        log.info("[lagou] {}", message.getMessage());
    }

    private void validateConfig(LagouConfigEntity config) throws IOException {
        if (config == null) throw new IllegalArgumentException("配置不能为空");
        List<String> keywords = Lagou.parseKeywords(config.getKeywords());
        if (keywords.isEmpty()) throw new IllegalArgumentException("至少配置一个搜索关键词");
        config.setKeywords(OBJECT_MAPPER.writeValueAsString(keywords));
        if (config.getCity() == null || config.getCity().isBlank()) config.setCity("全国");
        config.setCity(config.getCity().trim());
        if (config.getResumeType() == null || config.getResumeType().isBlank()) config.setResumeType("ONLINE");
        if (!"ONLINE".equalsIgnoreCase(config.getResumeType()) && !"ATTACHMENT".equalsIgnoreCase(config.getResumeType())) throw new IllegalArgumentException("resumeType 只能是 ONLINE 或 ATTACHMENT");
        config.setResumeType(config.getResumeType().toUpperCase());
        if ("ATTACHMENT".equals(config.getResumeType()) && (config.getResumeName() == null || config.getResumeName().isBlank())) throw new IllegalArgumentException("附件简历模式必须填写简历名称");
        if (config.getResumeName() != null) config.setResumeName(config.getResumeName().trim());
        if (config.getMaxCount() == null || config.getMaxCount() <= 0) config.setMaxCount(30);
    }

    private static List<String> parseStatuses(String statuses) {
        if (statuses == null || statuses.isBlank()) return null;
        return Arrays.stream(statuses.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
