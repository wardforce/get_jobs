package com.getjobs.application.controller;

import com.getjobs.application.service.CookieService;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api/lagou")
public class LagouController {

    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;

    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> loginStatus() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "isLoggedIn", playwrightManager.isLoggedIn("lagou")));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.triggerLagouLogin();
            response.put("success", true);
            response.put("message", "已打开拉勾登录页，请完成验证或登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "打开拉勾登录页失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
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
}
