package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright管理器
 * Spring管理的单例Bean，在应用启动时自动初始化Playwright实例
 * 支持4个求职平台的共享BrowserContext和登录状态监控
 * 所有平台在同一个浏览器窗口的不同标签页中运行
 */
@Slf4j
@Component
@Lazy
public class PlaywrightManager {

    private final PlaywrightAccessGate gate = new PlaywrightAccessGate();

    // Playwright实例
    private Playwright playwright;

    // 浏览器实例（所有平台共享）
    private Browser browser;

    // 浏览器上下文（所有平台共享，在同一个窗口中打开多个标签页）
    private BrowserContext context;

    // 持久化浏览器上下文会保存完整站点状态（Cookie、LocalStorage、IndexedDB、设备状态）。
    private boolean persistentBrowserContext;
    private boolean connectedOverCdp;
    private Process chromeProcess;

    // Boss直聘页面
    private Page bossPage;

    // 猎聘页面
    private Page liepinPage;

    // 51job页面（预留）
    private Page job51Page;

    // 智联招聘页面（预留）
    private Page zhilianPage;

    // 拉勾页面（预留）
    private Page lagouPage;

    // 登录状态追踪（平台 -> 是否已登录）
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    // 智联页面引用和登录状态分开维护，页面断开时不把已确认的登录直接判定为退出
    private enum ZhilianLoginState { LOGGED_IN, LOGGED_OUT, UNKNOWN }
    private volatile String zhilianPageState = "MISSING";
    private volatile String zhilianLoginState = "UNKNOWN";
    private volatile String zhilianPageUrl;
    private volatile String zhilianStateMessage = "智联页面尚未连接";
    private volatile long zhilianLastCheckedAt;
    private final Set<Page> zhilianMonitoredPages = ConcurrentHashMap.newKeySet();

    // 登录状态监听器
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    // 控制是否暂停对bossPage的后台监控，避免与任务执行并发访问同一页面
    private volatile boolean bossMonitoringPaused = false;
    // 控制是否暂停对liepinPage的后台监控
    private volatile boolean liepinMonitoringPaused = false;
    private volatile long liepinNextRecoveryAtMs = 0L;

    // 控制是否暂停对51jobPage的后台监控
  private volatile boolean job51MonitoringPaused = false;

    // 控制是否暂停对zhilianPage的后台监控
    private volatile boolean zhilianMonitoringPaused = false;

    private volatile boolean lagouMonitoringPaused = false;

    // 记录智联招聘是否已处理过未登录引导（仅初始化时执行一次）
    private volatile boolean zhilianLoginGuided = false;

    // 默认超时时间（毫秒）
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int LIEPIN_NAVIGATION_ATTEMPTS = 2;
    private static final long LIEPIN_RECOVERY_COOLDOWN_MS = 60_000L;

    // 平台URL常量
    private static final String BOSS_URL = "https://www.zhipin.com";
    private static final String LIEPIN_URL = "https://www.liepin.com";
  private static final String JOB51_URL = "https://www.51job.com";
    private static final String ZHILIAN_URL = "https://www.zhaopin.com";
    private static final String LAGOU_URL = "https://www.lagou.com";
    private static final String BOSS_DOMAIN = "zhipin.com";
    private static final String LIEPIN_DOMAIN = "liepin.com";
    private static final String JOB51_DOMAIN = "51job.com";
    private static final String ZHILIAN_DOMAIN = "zhaopin.com";
    private static final String LAGOU_DOMAIN = "lagou.com";
    private static final String LAGOU_ACCESS_VERIFICATION =
            "#aliyunCaptcha-sliding-wrapper, #waf_nc_block";
    private static final List<String> LAGOU_VERIFICATION_RETRY_SELECTORS = List.of(
            "button:has-text('验证失败')",
            "[role='button']:has-text('验证失败')",
            "text=/验证失败.*请刷新/",
            "#aliyunCaptcha-sliding-refresh",
            "[class*='refresh']:has-text('请刷新')"
    );
    private static final int LAGOU_INLINE_RETRIES_BEFORE_REFRESH = 2;
    private static final Pattern LAGOU_VERIFY_RESULT_PATTERN = Pattern.compile(
            "verifyResult\\s*[:=]\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAGOU_VERIFY_CODE_PATTERN = Pattern.compile(
            "verifyCode\\s*[:=]\\s*['\"]?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Path BROWSER_PROFILE_DIRECTORY = Path.of(
            System.getenv().getOrDefault("GET_JOBS_BROWSER_PROFILE_DIR", "db/playwright-profile")
    ).toAbsolutePath().normalize();
    private static final Path LAGOU_PROFILE_MIGRATION_MARKER =
            BROWSER_PROFILE_DIRECTORY.resolve(".lagou-session-v2");
    private static final boolean LAGOU_NATIVE_MOUSE_ENABLED = Boolean.parseBoolean(
            System.getenv().getOrDefault("GET_JOBS_LAGOU_NATIVE_MOUSE", "true"));
    private static final int CHROME_DEBUG_PORT = Integer.parseInt(
            System.getenv().getOrDefault("GET_JOBS_CHROME_DEBUG_PORT", "9223"));
    private static final String BOSS_INIT_SCRIPT_RESOURCE = "anti-detection.js";
    private static final java.nio.file.Path CHROME_EXECUTABLE = java.nio.file.Path.of(
            System.getenv().getOrDefault(
                    "GET_JOBS_CHROME_PATH",
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
            )
    );
    // 降噪：51job Cookie保存日志节流状态
    private volatile long last51CookieLogMs = 0L;
    private volatile int last51CookieLogCount = -1;
    private volatile String last51CookieRemark = "";
    private volatile Boolean lagouVerificationResult;
    private volatile String lagouVerificationCode = "";
    private volatile int lagouVerificationAttempts;
    private volatile int lagouInlineRetries;
    private volatile long lagouNextVerificationAttemptAtMs;

    @Autowired
    private CookieService cookieService;

    /**
     * 初始化Playwright实例（延迟初始化）
     */
    public void init() {
        gate.run(() -> {
            if (isInitialized()) {
                return;
            }
            log.info("========================================");
            log.info("  初始化浏览器自动化引擎");
            log.info("========================================");

            try {
            // 启动Playwright
            playwright = Playwright.create();
            log.info("✓ Playwright引擎已启动");

            // 先按普通桌面Chrome方式启动，再通过本机CDP连接，避免Playwright注入整组启动参数。
            Files.createDirectories(BROWSER_PROFILE_DIRECTORY);
            try {
                launchDesktopChromeAndConnectOverCdp();
            } catch (Exception cdpError) {
                log.warn("普通Chrome CDP连接失败，回退到Playwright持久化启动: {}", cdpError.getMessage());
                stopChromeProcess();
                context = playwright.chromium().launchPersistentContext(
                        BROWSER_PROFILE_DIRECTORY,
                        new BrowserType.LaunchPersistentContextOptions()
                        .setExecutablePath(CHROME_EXECUTABLE)
                        .setHeadless(false)
                        .setSlowMo(0)
                        .setIgnoreDefaultArgs(List.of("--enable-automation"))
                        .setArgs(List.of(
                                "--start-maximized",
                                "--disable-blink-features=AutomationControlled",
                                "--disable-extensions",
                                "--remote-debugging-address=127.0.0.1",
                                "--remote-debugging-port=" + CHROME_DEBUG_PORT
                        ))
                        .setViewportSize(null)
                );
                browser = context.browser();
                persistentBrowserContext = true;
                connectedOverCdp = false;
            }
            log.info("✓ 系统Chrome持久化上下文已启动: {}, cdpMode={}",
                    BROWSER_PROFILE_DIRECTORY, connectedOverCdp);
            injectBossInitScript(context);

            // 持久化 Profile 会恢复上次的标签页。优先复用并去重，避免每次重启再打开一整组页面。
            initializePlatformPages(context);

            // 顺序初始化各平台，所有Playwright调用都由共享gate保护
            log.info("开始顺序初始化所有平台...");
            setupLagouPlatform();
            setupBossPlatform();
            setupLiepinPlatform();
            setup51jobPlatform();
            setupZhilianPlatform();

            log.info("✓ 浏览器自动化引擎初始化完成（所有平台已顺序启动）");
            log.info("========================================");
            } catch (Exception e) {
                log.error("✗ 浏览器自动化引擎初始化失败", e);
                throw new RuntimeException("Playwright初始化失败", e);
            }
        });
    }

    private void launchDesktopChromeAndConnectOverCdp() throws Exception {
        URI versionEndpoint = URI.create("http://127.0.0.1:" + CHROME_DEBUG_PORT + "/json/version");
        if (isCdpEndpointReady(versionEndpoint)) {
            connectToDesktopChrome();
            log.info("检测到已运行的受控Chrome，直接接管现有上下文");
            return;
        }

        File chromeLog = BROWSER_PROFILE_DIRECTORY.resolve("chrome-cdp.log").toFile();
        ProcessBuilder builder = new ProcessBuilder(
                CHROME_EXECUTABLE.toString(),
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=" + CHROME_DEBUG_PORT,
                "--user-data-dir=" + BROWSER_PROFILE_DIRECTORY,
                "--start-maximized",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "about:blank"
        );
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(chromeLog));
        chromeProcess = builder.start();

        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            if (!chromeProcess.isAlive()) {
                throw new IllegalStateException("Chrome在CDP端口就绪前退出");
            }
            ready = isCdpEndpointReady(versionEndpoint);
            if (ready) {
                break;
            }
            Thread.sleep(250);
        }
        if (!ready) {
            throw new IllegalStateException("等待Chrome CDP端口超时: " + CHROME_DEBUG_PORT);
        }

        connectToDesktopChrome();
    }

    private boolean isCdpEndpointReady(URI versionEndpoint) {
        try {
            HttpURLConnection connection = (HttpURLConnection) versionEndpoint.toURL().openConnection();
            connection.setConnectTimeout(250);
            connection.setReadTimeout(250);
            boolean ready = connection.getResponseCode() == 200;
            connection.disconnect();
            return ready;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void connectToDesktopChrome() {
        browser = playwright.chromium().connectOverCDP(
                "http://127.0.0.1:" + CHROME_DEBUG_PORT);
        if (browser.contexts().isEmpty()) {
            throw new IllegalStateException("CDP连接后没有可用的默认浏览器上下文");
        }
        context = browser.contexts().get(0);
        persistentBrowserContext = true;
        connectedOverCdp = true;
    }

    /**
     * 将持久化上下文中已经恢复的平台页面重新绑定到管理器。
     * 每个平台只保留一个顶层页面；多余的平台页和未使用的空白页会被关闭。
     */
    void initializePlatformPages(BrowserContext browserContext) {
        List<Page> restoredPages = new ArrayList<>(browserContext.pages());
        log.info("开始恢复平台Page，浏览器现有标签页数量: {}", restoredPages.size());

        bossPage = claimRestoredPlatformPage(restoredPages, BOSS_DOMAIN);
        liepinPage = claimRestoredPlatformPage(restoredPages, LIEPIN_DOMAIN);
        job51Page = claimRestoredPlatformPage(restoredPages, JOB51_DOMAIN);
        zhilianPage = claimRestoredPlatformPage(restoredPages, ZHILIAN_DOMAIN);
        lagouPage = claimRestoredPlatformPage(restoredPages, LAGOU_DOMAIN);

        bossPage = ensurePlatformPage(browserContext, restoredPages, bossPage, "Boss");
        liepinPage = ensurePlatformPage(browserContext, restoredPages, liepinPage, "猎聘");
        job51Page = ensurePlatformPage(browserContext, restoredPages, job51Page, "51job");
        zhilianPage = ensurePlatformPage(browserContext, restoredPages, zhilianPage, "智联招聘");
        lagouPage = ensurePlatformPage(browserContext, restoredPages, lagouPage, "拉勾");

        restoredPages.stream()
                .filter(this::isBlankPage)
                .forEach(this::closePageQuietly);
    }

    private Page claimRestoredPlatformPage(List<Page> restoredPages, String domain) {
        List<Page> matches = restoredPages.stream()
                .filter(page -> !isPageClosed(page))
                .filter(page -> pageMatchesDomain(page, domain))
                .toList();
        restoredPages.removeAll(matches);
        if (matches.isEmpty()) {
            return null;
        }

        Page selected = matches.stream()
                .filter(this::isTopLevelPage)
                .findFirst()
                .orElse(matches.get(0));
        matches.stream()
                // 带 opener 的页面属于投递流程产生的业务弹窗，不能在初始化整理时关闭。
                .filter(page -> page != selected && isTopLevelPage(page))
                .forEach(this::closePageQuietly);
        log.info("✓ 已复用 {} 页面，关闭重复页 {} 个", domain, matches.size() - 1);
        return selected;
    }

    private Page ensurePlatformPage(
            BrowserContext browserContext,
            List<Page> restoredPages,
            Page restoredPage,
            String platformName) {
        Page page = restoredPage;
        if (page == null) {
            page = restoredPages.stream()
                    .filter(this::isBlankPage)
                    .findFirst()
                    .orElse(null);
            if (page != null) {
                restoredPages.remove(page);
                log.info("✓ {} 复用空白Page", platformName);
            } else {
                page = browserContext.newPage();
                log.info("✓ {} Page已创建", platformName);
            }
        }
        page.setDefaultTimeout(DEFAULT_TIMEOUT);
        return page;
    }

    private boolean pageMatchesDomain(Page page, String domain) {
        try {
            String host = URI.create(page.url()).getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isBlankPage(Page page) {
        try {
            return !page.isClosed() && "about:blank".equalsIgnoreCase(page.url());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isTopLevelPage(Page page) {
        try {
            return page.opener() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPageClosed(Page page) {
        try {
            return page.isClosed();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private void closePageQuietly(Page page) {
        try {
            if (!page.isClosed()) {
                page.close();
            }
        } catch (RuntimeException e) {
            log.debug("关闭重复或空白Page失败: {}", e.getMessage());
        }
    }

    private void stopChromeProcess() {
        if (chromeProcess == null || !chromeProcess.isAlive()) {
            return;
        }
        chromeProcess.destroy();
        try {
            if (!chromeProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                chromeProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chromeProcess.destroyForcibly();
        }
    }

    /**
     * 在上下文层统一注入 Boss 脚本，仅对 zhipin.com 生效。
     */
    private void injectBossInitScript(BrowserContext targetContext) {
        String script = readResourceText(BOSS_INIT_SCRIPT_RESOURCE);
        if (script == null || script.isBlank()) {
            log.warn("Boss 反检测脚本未加载，资源不存在或为空: {}", BOSS_INIT_SCRIPT_RESOURCE);
            return;
        }
        targetContext.addInitScript(wrapBossInitScript(script));
        targetContext.addInitScript(wrapLiepinInitScript(script));
        log.info("Boss/猎聘反检测脚本已注入到Context: {}", BOSS_INIT_SCRIPT_RESOURCE);
    }

    static String wrapBossInitScript(String script) {
        return "(function(){try{if(location&&/(^|\\.)zhipin\\.com$/.test(location.hostname)){"
                + "if(window.__bossAntiDetectInjected){return;}window.__bossAntiDetectInjected=true;"
                + script + "}}catch(e){}})();";
    }

    static String wrapLiepinInitScript(String script) {
        return "(function(){try{if(location&&/(^|\\.)liepin\\.com$/.test(location.hostname)){"
                + "if(window.__liepinAntiDetectInjected){return;}window.__liepinAntiDetectInjected=true;"
                + script + "}}catch(e){}})();";
    }

    private String readResourceText(String resourcePath) {
        try (InputStream input = PlaywrightManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取资源失败: {} - {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /**
     * 设置Boss直聘平台（加载Cookie、导航、监控）
     */
    private void setupBossPlatform() {
        log.info("开始初始化Boss直聘平台...");
        // 尝试从数据库加载Boss平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("boss");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), BOSS_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载Boss Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到Boss Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载Boss Cookie失败: {}", e.getMessage());
        }

        // 导航到Boss直聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                bossPage.navigate(BOSS_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = bossPage.url();
                    pageAccessible = url != null && url.contains("zhipin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("Boss直聘页面导航失败");
        }

        try {
            // 等待页面网络空闲，确保头部导航渲染完成
            try {
                bossPage.waitForLoadState(LoadState.NETWORKIDLE);
            } catch (Exception e) {
                log.debug("等待Boss页面网络空闲失败: {}", e.getMessage());
            }

            // 初始化阶段不主动跳转登录页，仅在导航后设置状态
            // 参考猎聘实现：加载Cookie并导航后，由业务侧决定是否触发后续登录流程
        } catch (Exception e) {
            log.warn("Boss直聘页面导航失败: {}", e.getMessage());
        }
        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("boss", checkIfLoggedIn());
        // 设置登录状态监控
        setupLoginMonitoring(bossPage);
    }

    /**
     * 检查Boss是否已登录
     */
    private boolean checkIfLoggedIn() {
        // 更稳健的登录判断：优先检测用户头像/昵称是否可见；备用检测登录入口是否可见且包含“登录”文本
        try {
            Locator userLabel = bossPage.locator("li.nav-figure span.label-text").first();
            if (userLabel.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 有些版本仅展示头像入口，无 label-text
            Locator navFigure = bossPage.locator("li.nav-figure").first();
            if (navFigure.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 未登录时通常有“登录/注册”入口或按钮容器
            Locator loginAnchor = bossPage.locator("li.nav-sign a, .btns").first();
            if (loginAnchor.isVisible()) {
                String text = loginAnchor.textContent();
                if (text != null && text.contains("登录")) {
                    return false;
                }
            }
        } catch (Exception ignored) {}

        // 无法明确检测到登录特征时，保守返回未登录
        return false;
    }

    /**
     * 设置登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                log.info("Boss主页面导航: {}", frame.url());
                // 事件触发的检查在Playwright内部线程执行，仍需遵守暂停标志
                if (!bossMonitoringPaused) {
                    gate.run(() -> checkLoginStatus(page, "boss"));
                }
            }
        });

        log.info("{}平台登录状态监控已启用", "boss");
    }

    /**
     * 设置猎聘平台（加载Cookie、导航、监控）
     */
    private void setupLiepinPlatform() {
        log.info("开始初始化猎聘平台...");

        // 尝试从数据库加载猎聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("liepin");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), LIEPIN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载猎聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析猎聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到猎聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载猎聘Cookie失败: {}", e.getMessage());
        }

        boolean navigateSuccess = navigateLiepinPage(liepinPage, "现有Page");
        if (!navigateSuccess && context != null) {
            Page oldPage = liepinPage;
            Page replacement = context.newPage();
            replacement.setDefaultTimeout(DEFAULT_TIMEOUT);
            if (navigateLiepinPage(replacement, "替代Page")) {
                liepinPage = replacement;
                closePageQuietly(oldPage);
                navigateSuccess = true;
                log.info("猎聘已切换到可用替代Page");
            } else {
                closePageQuietly(replacement);
            }
        }

        if (!navigateSuccess) {
            log.warn("猎聘页面导航失败，最终URL={}", safePageUrl(liepinPage));
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("liepin", navigateSuccess && checkIfLiepinLoggedIn());
        // 设置登录状态监控
        setupLiepinLoginMonitoring(liepinPage);
    }

    private boolean navigateLiepinPage(Page page, String pageLabel) {
        if (page == null) {
            return false;
        }
        for (int attempt = 1; attempt <= LIEPIN_NAVIGATION_ATTEMPTS; attempt++) {
            try {
                page.navigate(LIEPIN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(10000));
                } catch (Exception networkIdleTimeout) {
                    log.debug("猎聘{}等待网络空闲超时，继续检查页面内容: {}",
                            pageLabel, networkIdleTimeout.getMessage());
                }
                if (isLiepinPageReady(page)) {
                    log.info("猎聘{}导航成功，最终URL={}", pageLabel, safePageUrl(page));
                    return true;
                }
                log.warn("猎聘{}导航后页面未渲染，第{}次，最终URL={}",
                        pageLabel, attempt, safePageUrl(page));
            } catch (Exception e) {
                log.warn("猎聘{}导航失败，第{}次，URL={}，原因={}",
                        pageLabel, attempt, safePageUrl(page), e.getMessage());
            }
            if (attempt < LIEPIN_NAVIGATION_ATTEMPTS) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    boolean isLiepinPageReady(Page page) {
        try {
            if (page == null || page.isClosed() || !pageMatchesDomain(page, LIEPIN_DOMAIN)) {
                return false;
            }
            Locator body = page.locator("body");
            if (body.count() == 0) {
                return false;
            }
            String text = body.innerText();
            return text != null && !text.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recoverLiepinPageIfNeeded() {
        if (context == null || isLiepinPageReady(liepinPage)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < liepinNextRecoveryAtMs) {
            return;
        }
        liepinNextRecoveryAtMs = now + LIEPIN_RECOVERY_COOLDOWN_MS;
        log.warn("检测到猎聘Page无效，开始自动恢复，当前URL={}", safePageUrl(liepinPage));

        Page oldPage = liepinPage;
        Page replacement = context.newPage();
        replacement.setDefaultTimeout(DEFAULT_TIMEOUT);
        if (navigateLiepinPage(replacement, "自动恢复")) {
            liepinPage = replacement;
            closePageQuietly(oldPage);
            setLoginStatus("liepin", checkIfLiepinLoggedIn());
            setupLiepinLoginMonitoring(replacement);
            log.info("猎聘Page自动恢复完成");
        } else {
            closePageQuietly(replacement);
            setLoginStatus("liepin", false);
            log.warn("猎聘Page自动恢复失败，保留原Page等待下一次重试");
        }
    }

    /**
     * 检查猎聘是否已登录
     * 已登录：能找到用户头像 <img class="header-quick-menu-user-photo" ...>
     * 未登录：能找到 <span id="header-quick-menu-login">登录/注册</span>
     */
    private boolean checkIfLiepinLoggedIn() {
        try {
            if (!isLiepinPageReady(liepinPage)) {
                log.info("猎聘页面尚未渲染完成，暂不判定为已登录");
                return false;
            }
            // 先检查“登录/注册”入口是否可见，若可见则明确未登录
            try {
                Locator loginEntry = liepinPage.locator(
                    "#header-quick-menu-login, a[href*='login'], a[data-key='login'], button[data-key='login'], text=/登录|注册/").first();
                if (loginEntry.isVisible()) {
                    log.info("检测到未登录猎聘，保持在登录页或首页等待扫码登录");
                    // 若不在登录页，则导航到登录页并尝试切换二维码
                    String currentUrl = null;
                    try { currentUrl = liepinPage.url(); } catch (Exception ignored) {}
                    try {
                        if (currentUrl == null || !currentUrl.contains("/login")) {
                            liepinPage.navigate("https://www.liepin.com/login");
                            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        // 优先点击官方切换二维码的容器
                        Locator qrSwitch = liepinPage.locator(".switch-type-mask-img-box").first();
                        if (qrSwitch.isVisible()) {
                            qrSwitch.click();
                            log.info("已切换到猎聘二维码登录页面，等待用户扫码...");
                        } else {
                            // 兼容新版页面：图片资源名包含 qrcode-btn，需要点击其父级按钮
                            Locator qrImg = liepinPage.locator("img[src*='qrcode-btn']").first();
                            if (qrImg.count() > 0 && qrImg.isVisible()) {
                                try {
                                    // 尝试点击父节点或最近的可点击容器
                                    qrImg.click();
                                } catch (Exception ignored) {
                                    try {
                                        Locator parentBtn = qrImg.locator("xpath=ancestor::button[1] | xpath=ancestor::*[contains(@class,'btn')][1]").first();
                                        if (parentBtn.count() > 0 && parentBtn.isVisible()) {
                                            parentBtn.click();
                                        }
                                    } catch (Exception ignored2) {}
                                }
                                log.info("已通过二维码按钮切换到扫码登录状态");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("猎聘登录页引导/二维码切换失败: {}", e.getMessage());
                    }
                    return false;
                }
            } catch (Exception ignored) {}

            // 再检查已登录特征：用户信息容器或用户头像是否存在（无需强制可见）
            try {
                if (liepinPage.locator("#header-quick-menu-user-info").count() > 0) {
                    log.debug("猎聘登录检测：存在用户信息容器，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            try {
                if (liepinPage.locator("img.header-quick-menu-user-photo, .header-quick-menu-user-photo").count() > 0) {
                    log.debug("猎聘登录检测：存在用户头像元素，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 兜底：若不存在登录入口且也未找到明确已登录特征，按已登录处理（避免误判）
            try {
                boolean loginEntryExists = liepinPage.locator("#header-quick-menu-login, a[href*='login']").count() > 0;
                if (!loginEntryExists) {
                    log.info("猎聘登录检测：未发现登录入口，兜底判定为已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 默认未登录
            log.debug("猎聘登录检测：未匹配到明确特征，判定未登录");
            return false;
        } catch (Exception e) {
            log.debug("猎聘登录检测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置猎聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLiepinLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!liepinMonitoringPaused) {
                    gate.run(() -> checkLiepinLoginStatus(page));
                }
            }
        });

        log.info("猎聘平台登录状态监控已启用");
    }

    /**
     * 设置51job平台（加载Cookie、导航、监控）
     */
    private void setup51jobPlatform() {
        log.info("开始初始化51job平台...");

        // 尝试从数据库加载51job平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("51job");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), JOB51_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载51job Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析51job Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到51job Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载51job Cookie失败: {}", e.getMessage());
        }

        // 导航到51job首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = job51Page.url();
                    pageAccessible = url != null && url.contains("51job.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("51job页面导航失败");
        }

        try {
            // 检查是否需要登录
            if (!checkIf51jobLoggedIn()) {
                log.info("检测到未登录51job，尝试自动点击登录入口并等待用户登录");

                try {
                    // 优先使用用户提供的选择器：span.login.loginBtnClick
                    Locator loginEntry = job51Page.locator("span.login.loginBtnClick").first();
                    if (loginEntry != null && loginEntry.isVisible()) {
                        loginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                        log.info("已点击 51job 首页的 ‘登录/注册’ 入口，等待用户登录...");
                        asyncWaitFor51jobLogin();
                    } else {
                        // 备用选择器：文本匹配
                        Locator altLoginEntry = job51Page.locator("text=/登录\\/注册|登录|注册/").first();
                        if (altLoginEntry != null && altLoginEntry.isVisible()) {
                            altLoginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                            log.info("已点击 51job 首页的登录入口（文本匹配），等待用户登录...");
                            asyncWaitFor51jobLogin();
                        } else {
                            log.info("未找到 51job 登录入口元素，保持在首页等待用户自行登录");
                            // 启动后台轮询，确保无导航也能检测到登录成功
                            asyncWaitFor51jobLogin();
                        }
                    }
                } catch (Exception clickEx) {
                    log.warn("尝试点击 51job 登录入口时发生异常: {}，保持在首页等待用户登录", clickEx.getMessage());
                    // 启动后台轮询，避免异常导致无法检测登录成功
                    asyncWaitFor51jobLogin();
                }
            } else {
                log.info("51job已登录");
            }
        } catch (Exception e) {
            log.warn("51job页面初始化检查失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("51job", checkIf51jobLoggedIn());
        // 设置登录状态监控
        setup51jobLoginMonitoring(job51Page);
    }

    /**
     * 检查51job是否已登录
     */
  private boolean checkIf51jobLoggedIn() {
      try {
            // 未登录特征：存在“登录/注册”入口
            Locator loginBtn = job51Page.locator("span.login.loginBtnClick").first();
            if (loginBtn.isVisible()) {
                String txt = (loginBtn.textContent() == null ? "" : loginBtn.textContent()).trim();
                if (txt.contains("登录")) {
                    return false;
                }
            }
            // 已登录特征（增强）：顶部显示用户名入口或个人中心链接
            // 1) 明确的用户名锚点（类名：uname e_icon at）
            Locator userAnchor = job51Page.locator("a.uname.e_icon.at");
            if (userAnchor.count() > 0 && userAnchor.first().isVisible()) {
                return true;
            }
            // 2) 个人中心链接（href=/pc/my/myjob）
            Locator myJobLink = job51Page.locator("a[href*='/pc/my/myjob']");
            if (myJobLink.count() > 0 && myJobLink.first().isVisible()) {
                return true;
            }
            // 3) 其他可能的用户信息容器（旧的兜底选择器）
            return job51Page.locator(".login-info, .user-info, .username").count() > 0;
      } catch (Exception e) {
          return false;
      }
  }

    /**
     * 设置51job登录状态监控
     *
     * @param page 页面实例
     */
    private void setup51jobLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!job51MonitoringPaused) {
                    gate.run(() -> check51jobLoginStatus(page));
                }
            }
        });

        log.info("51job平台登录状态监控已启用");
    }

    /**
     * 检查51job登录状态
     *
     * @param page 页面实例
     */
    private void check51jobLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIf51jobLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("51job");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                on51jobLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查51job平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 51job登录成功回调
     */
    private void on51jobLoginSuccess() {
        log.info("51job平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("51job", true);

        // 登录成功时保存 Cookie 到数据库
        save51jobCookiesToDatabase("login success");
    }

    /**
     * 在后台异步等待 51job 登录成功。
     * 说明：不阻塞初始化主流程，独立线程每秒轮询一次登录状态，最长等待5分钟。
     */
    private void asyncWaitFor51jobLogin() {
        Thread waitThread = new Thread(() -> {
            try {
                int maxSeconds = 300; // 最长等待 5 分钟
                for (int i = 0; i < maxSeconds; i++) {
                    boolean loggedIn = false;
                    try {
                        loggedIn = gate.call(this::checkIf51jobLoggedIn);
                    } catch (Exception ignored) {
                    }

                    if (loggedIn) {
                        // 交由统一回调处理登录成功逻辑（包含状态更新与保存 Cookie）
                        on51jobLoginSuccess();
                        log.info("后台等待检测到 51job 登录成功，用时约 {} 秒", i);
                        return;
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("等待 51job 登录线程被中断");
                        return;
                    }
                }
                log.warn("后台等待 51job 登录超时（约5分钟），仍未检测到登录成功");
            } catch (Exception e) {
                log.warn("后台等待 51job 登录过程中发生异常: {}", e.getMessage());
            }
        }, "wait-51job-login-thread");

        waitThread.setDaemon(true);
        waitThread.start();
    }

    /**
     * 保存51job Cookie到数据库
     *
     * @param remark 备注信息
     */
  private void save51jobCookiesToDatabase(String remark) {
      try {
          List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), JOB51_DOMAIN);
          // 使用ObjectMapper序列化为JSON字符串
          String cookieJson = new ObjectMapper().writeValueAsString(cookies);
          boolean result = cookieService.saveOrUpdateCookie("51job", cookieJson, remark);
          if (result) {
                long now = System.currentTimeMillis();
                boolean shouldInfoLog = (now - last51CookieLogMs) > 15000 // 至少间隔15秒
                        || cookies.size() != last51CookieLogCount
                        || (remark != null && !remark.equals(last51CookieRemark));
                if (shouldInfoLog) {
                    log.info("保存51job Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
                    last51CookieLogMs = now;
                    last51CookieLogCount = cookies.size();
                    last51CookieRemark = remark == null ? "" : remark;
                } else {
                    // 近似重复的频繁调用，改为debug降低噪音
                    log.debug("保存51job Cookie成功(节流)，条数={}，remark={}", cookies.size(), remark);
                }
          }
      } catch (Exception e) {
          log.warn("保存51job Cookie失败: {}", e.getMessage());
      }
  }

    /**
     * 主动保存51job Cookie到数据库（用于调试/验证）
     */
    public void save51jobCookiesToDb(String remark) {
        gate.run(() -> save51jobCookiesToDatabase(remark));
    }

    /**
     * 清理51job上下文中的Cookie
     */
    public void clear51jobCookies() {
        gate.run(this::clear51jobCookiesInternal);
    }

    private void clear51jobCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停51job页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pause51jobMonitoring() {
        job51MonitoringPaused = true;
        log.debug("51job登录监控已暂停");
    }

    /**
     * 恢复51job页面的后台登录监控
     */
    public void resume51jobMonitoring() {
        job51MonitoringPaused = false;
        log.debug("51job登录监控已恢复");
    }

    /**
     * 触发 51job 登录流程：打开登录页并点击“微信扫码登录”按钮
     */
    public void trigger51jobLogin() {
        gate.run(this::trigger51jobLoginInternal);
    }

    private void trigger51jobLoginInternal() {
        try {
            if (job51Page == null) {
                if (context == null) {
                    throw new IllegalStateException("浏览器上下文尚未初始化");
                }
                job51Page = context.newPage();
            }

            // 如果已登录则直接返回
            if (checkIf51jobLoggedIn()) {
                log.info("检测到已登录51job，跳过登录触发");
                return;
            }

            // 先尝试在首页点击“登录/注册”入口
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Locator loginEntry = job51Page.locator("span.login.loginBtnClick, text=/登录\\/注册|登录|注册/").first();
                if (loginEntry.isVisible()) {
                    loginEntry.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                }
            } catch (Exception e) {
                log.debug("在首页尝试点击登录入口失败: {}", e.getMessage());
            }

            // 跳转到官方登录页
            String loginUrl = "https://login.51job.com/login.php";
            job51Page.navigate(loginUrl, new Page.NavigateOptions()
                .setTimeout(60000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 尝试点击“微信扫码登录”按钮
            Locator wechatScanBtn = job51Page.locator(
                "i.passIcon.custom-cursor-on-hover[data-sensor-id='sensor_login_wechatScan'], " +
                "i.passIcon[data-sensor-id='sensor_login_wechatScan'], " +
                "[data-sensor-id='sensor_login_wechatScan']"
            ).first();

            if (wechatScanBtn.isVisible()) {
                wechatScanBtn.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                log.info("已点击51job登录页的微信扫码按钮，等待用户扫码登录...");
            } else {
                log.warn("未找到微信扫码登录按钮，用户可在登录页自行选择扫码方式");
            }

            // 不阻塞等待：监控会自动检测到登录成功并保存Cookie
        } catch (Exception e) {
            log.error("触发51job登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发51job登录流程失败", e);
        }
    }

    /**
     * 设置智联招聘平台（加载Cookie、导航、监控）
     */
    private void setupZhilianPlatform() {
        log.info("开始初始化智联招聘平台...");

        // 尝试从数据库加载智联招聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("zhilian");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), ZHILIAN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载智联招聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析智联招聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到智联招聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载智联招聘Cookie失败: {}", e.getMessage());
        }

        // 导航到智联招聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = zhilianPage.url();
                    pageAccessible = url != null && url.contains("zhaopin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("智联招聘页面导航失败");
        }

        // 等待页面加载完成
        try {
            zhilianPage.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("等待智联页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        ZhilianLoginState initialState = detectZhilianLoginState(zhilianPage);
        if (initialState == ZhilianLoginState.UNKNOWN) {
            zhilianLoginState = ZhilianLoginState.UNKNOWN.name();
            loginStatus.putIfAbsent("zhilian", false);
        } else {
            setLoginStatus("zhilian", initialState == ZhilianLoginState.LOGGED_IN);
        }
        // 设置登录状态监控
        setupZhilianLoginMonitoring(zhilianPage);
    }

    private Page resolveLiveZhilianPage(boolean createIfMissing) {
        Page current = zhilianPage;
        if (isLiveZhilianPage(current)) {
            markZhilianPageConnected(current);
            setupZhilianLoginMonitoring(current);
            return current;
        }

        Page candidate = findLiveZhilianPage();
        if (candidate != null) {
            zhilianPage = candidate;
            markZhilianPageConnected(candidate);
            setupZhilianLoginMonitoring(candidate);
            log.info("智联招聘页面引用已重新绑定: {}", safePageUrl(candidate));
            return candidate;
        }

        if (createIfMissing && context != null) {
            try {
                Page created = context.newPage();
                zhilianPage = created;
                markZhilianPageConnected(created);
                setupZhilianLoginMonitoring(created);
                log.info("智联招聘不存在可复用页面，已创建新的主页面");
                return created;
            } catch (Exception e) {
                markZhilianPageState("MISSING", "创建智联页面失败: " + e.getMessage());
            }
        }

        markZhilianPageState("MISSING", "未找到可用的智联主页面");
        return null;
    }

    private Page findLiveZhilianPage() {
        if (context == null) {
            return null;
        }
        Page fallback = null;
        try {
            List<Page> pages = context.pages();
            for (int i = pages.size() - 1; i >= 0; i--) {
                Page page = pages.get(i);
                if (!isLiveZhilianPage(page) || page.opener() != null) {
                    continue;
                }
                String url = safePageUrl(page);
                if (url != null && !url.contains("passport.zhaopin.com")) {
                    return page;
                }
                fallback = page;
            }
        } catch (Exception e) {
            log.debug("扫描智联页面失败: {}", e.getMessage());
        }
        return fallback;
    }

    private boolean isLiveZhilianPage(Page page) {
        if (page == null) {
            return false;
        }
        try {
            return !page.isClosed() && isZhilianUrl(page.url());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isZhilianUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains(ZHILIAN_DOMAIN);
    }

    private String safePageUrl(Page page) {
        try {
            return page == null ? null : page.url();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasVisibleZhilianElement(Page page, String selector) {
        try {
            Locator elements = page.locator(selector);
            int count = Math.min(elements.count(), 50);
            for (int index = 0; index < count; index++) {
                Locator element = count == 1 ? elements.first() : elements.nth(index);
                if (element.isVisible()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("检查智联选择器失败: selector={}, reason={}", selector, e.getMessage());
        }
        return false;
    }

    private void markZhilianPageConnected(Page page) {
        zhilianPageState = "CONNECTED";
        zhilianPageUrl = safePageUrl(page);
        zhilianLastCheckedAt = System.currentTimeMillis();
        zhilianStateMessage = "智联页面连接正常";
    }

    private void markZhilianPageState(String state, String message) {
        zhilianPageState = state;
        zhilianPageUrl = safePageUrl(zhilianPage);
        zhilianLastCheckedAt = System.currentTimeMillis();
        zhilianStateMessage = message;
    }

    private ZhilianLoginState detectZhilianLoginState(Page page) {
        if (page == null || isPageClosed(page)) {
            markZhilianPageState("RECOVERING", "智联页面连接已断开，正在重新绑定");
            return ZhilianLoginState.UNKNOWN;
        }
        try {
            if (hasVisibleZhilianElement(page,
                    "div.a-job-apply-workflow-close div.zppp-panel-login-normal, " +
                    "div.a-job-apply-workflow-close div.zppp-panel-login-qrcode")) {
                return ZhilianLoginState.LOGGED_OUT;
            }

            if (hasVisibleZhilianElement(page,
                    "a.home-header__c-no-login, a.home-search__c-no-login")) {
                return ZhilianLoginState.LOGGED_OUT;
            }

            String url = safePageUrl(page);
            if (url != null && url.contains("i.zhaopin.com")) {
                return ZhilianLoginState.LOGGED_IN;
            }

            if (hasVisibleZhilianElement(page,
                    ".home-header__c-login, .c-login__top__name, .c-login__top__photo, " +
                    ".home-login .login-after, .user-info, .user-name, .username-text, " +
                    "a[href*='user'], a[href*='resume']")) {
                return ZhilianLoginState.LOGGED_IN;
            }

            // 页面导航或前端渲染尚未完成时，缺少标记不能作为已登录证据。
            return ZhilianLoginState.UNKNOWN;
        } catch (Exception e) {
            markZhilianPageState("RECOVERING", "智联登录状态暂时无法确认");
            log.debug("智联招聘：检查登录状态异常: {}", e.getMessage());
            return ZhilianLoginState.UNKNOWN;
        }
    }

    /**
     * 检查智联招聘是否已登录
     * 未登录时只在首次检测时引导用户到登录页
     */
    private boolean checkIfZhilianLoggedInLegacy() {
        try {
            if (zhilianPage == null) {
                return false;
            }

            Locator loginModal = zhilianPage.locator(
                    "div.a-job-apply-workflow-close div.zppp-panel-login-normal, " +
                    "div.a-job-apply-workflow-close div.zppp-panel-login-qrcode"
            );
            if (loginModal.count() > 0 && loginModal.first().isVisible()) {
                return false;
            }

            boolean isLoggedIn = false;
            boolean loginButtonExists = false;

            // 检查是否存在"登录/注册"按钮
            try {
                Locator loginButton = zhilianPage.locator("a.home-header__c-no-login").first();
                int count = loginButton.count();
                if (count > 0) {
                    loginButtonExists = true;
                    // 尝试获取文本进一步确认
                    try {
                        String buttonText = loginButton.textContent();
                        if (buttonText != null && buttonText.contains("登录")) {
                            loginButtonExists = true;
                        }
                    } catch (Exception e) {
                        log.debug("智联招聘：获取登录按钮文本失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("智联招聘：检查登录按钮时异常: {}", e.getMessage());
            }

            // 如果存在登录按钮，说明未登录
            if (loginButtonExists) {
                // 只在首次检测到未登录时执行引导操作
                if (!zhilianLoginGuided) {
                    log.info("检测到未登录智联招聘，重定向到登录页面");
                    zhilianLoginGuided = true;

                    // 重定向到登录页面
                    String currentUrl = null;
                    try {
                        currentUrl = zhilianPage.url();
                    } catch (Exception ignored) {
                    }

                    try {
                        if (currentUrl == null || !currentUrl.contains("passport.zhaopin.com/login")) {
                            boolean loginNavOk = false;
                            try {
                                zhilianPage.navigate(
                                        "https://passport.zhaopin.com/login",
                                        new Page.NavigateOptions()
                                                .setTimeout(60000)
                                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                );
                                loginNavOk = true;
                            } catch (Exception navEx) {
                                String urlAfter = null;
                                try {
                                    urlAfter = zhilianPage.url();
                                } catch (Exception ignored2) {}

                                if (urlAfter != null && urlAfter.contains("passport.zhaopin.com")) {
                                    loginNavOk = true;
                                    log.debug("智联招聘：登录页导航异常但已在登录域: {}", navEx.getMessage());
                                } else {
                                    log.warn("智联招聘：导航至登录页失败: {}", navEx.getMessage());
                                }
                            }

                            if (loginNavOk) {
                                try {
                                    zhilianPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                                } catch (Exception ignored) {}
                                try {
                                    zhilianPage.waitForSelector(
                                            "div.zppp-panel-normal-bar__img, " +
                                            "div.passport-login, #J_loginWrap, " +
                                            "div[class*='qrcode'], img[src*='qrcode']",
                                            new Page.WaitForSelectorOptions().setTimeout(30000)
                                    );
                                } catch (Exception e) {
                                    log.debug("智联招聘：登录页关键元素等待失败: {}", e.getMessage());
                                }
                            }
                        }

                        // 点击二维码登录按钮
                        Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                        if (qrToggle.count() > 0 && qrToggle.isVisible()) {
                            qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                            log.info("已切换到智联二维码登录页面，等待用户扫码...");
                        } else {
                            log.info("智联招聘登录页面已打开，等待用户扫码...");
                        }
                    } catch (Exception e) {
//                        log.warn("智联招聘：打开二维码登录面板失败: {}", e.getMessage());
                    }
                }
                return false;
            }

            // 检查是否有已登录的特征
            try {
                String url = zhilianPage.url();
                if (url != null && url.contains("i.zhaopin.com")) {
                    log.debug("智联招聘：URL包含i.zhaopin.com，判定为已登录");
                    isLoggedIn = true;
                }
            } catch (Exception ignore) {
            }

            // 如果没有登录按钮，也认为已登录
            if (!loginButtonExists) {
                log.debug("智联招聘：未检测到登录按钮，判定为已登录");
                isLoggedIn = true;
            }

            return isLoggedIn;
        } catch (Exception e) {
            log.warn("智联招聘：检查登录状态异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置智联招聘登录状态监控
     *
     * @param page 页面实例
     */
    private boolean checkIfZhilianLoggedIn(Page page) {
        return detectZhilianLoginState(page) == ZhilianLoginState.LOGGED_IN;
    }

    private void setupZhilianLoginMonitoring(Page page) {
        if (page == null || !zhilianMonitoredPages.add(page)) {
            return;
        }

        page.onClose(closedPage -> {
            zhilianMonitoredPages.remove(closedPage);
            if (zhilianPage == closedPage) {
                zhilianPage = null;
                markZhilianPageState("RECOVERING", "智联页面已关闭，正在重新绑定");
            }
        });

        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!zhilianMonitoringPaused) {
                    gate.run(() -> checkZhilianLoginStatus(page));
                }
            }
        });

        log.info("智联招聘平台登录状态监控已启用");
    }

    /**
     * 检查智联招聘登录状态
     *
     * @param page 页面实例
     */
    private void checkZhilianLoginStatus(Page page) {
        try {
            if (page == null || isPageClosed(page)) {
                resolveLiveZhilianPage(false);
                return;
            }
            zhilianPage = page;
            markZhilianPageConnected(page);
            ZhilianLoginState detectedState = detectZhilianLoginState(page);
            if (detectedState == ZhilianLoginState.UNKNOWN) {
                return;
            }
            zhilianLoginState = detectedState.name();

            Boolean previousStatus = loginStatus.get("zhilian");
            if (detectedState == ZhilianLoginState.LOGGED_IN
                    && (previousStatus == null || !previousStatus)) {
                onZhilianLoginSuccess();
            } else if (detectedState == ZhilianLoginState.LOGGED_OUT
                    && Boolean.TRUE.equals(previousStatus)) {
                setLoginStatus("zhilian", false);
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查智联招聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 主动触发智联招聘登录：打开登录入口，登录状态由后台监控异步确认。
     */
    public void triggerZhilianLogin() {
        gate.run(this::triggerZhilianLoginInternal);
    }

    private void triggerZhilianLoginInternal() {
        try {
            Page page = resolveLiveZhilianPage(true);
            if (page == null) {
                throw new IllegalStateException("智联招聘页面未初始化");
            }
            zhilianPage = page;

            // 导航到智联首页，确保DOM就绪
            zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 如果看到未登录入口，尝试打开二维码登录面板
            Locator noLoginAnchor = zhilianPage.locator("a.home-header__c-no-login").first();
            if (noLoginAnchor.isVisible()) {
                Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                if (qrToggle.isVisible()) {
                    qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                    log.info("已点击智联二维码登录入口，等待用户扫码...");
                } else {
                    log.warn("未找到二维码登录入口元素：div.zppp-panel-normal-bar__img");
                }
            } else {
                log.info("未检测到未登录入口，可能已登录或在其他页面");
            }

            // 登录成功由页面导航监听和状态轮询统一检测，避免接口阻塞数分钟。
        } catch (Exception e) {
            log.error("触发智联登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发智联登录流程失败", e);
        }
    }

    /**
     * 智联招聘登录成功回调
     */
    private void onZhilianLoginSuccess() {
        log.info("智联招聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("zhilian", true);

        // 登录成功时保存 Cookie 到数据库
        saveZhilianCookiesToDatabase("login success");
    }

    /**
     * 保存智联招聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveZhilianCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), ZHILIAN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("zhilian", cookieJson, remark);
            if (result) {
                log.info("保存智联招聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存智联招聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存智联招聘Cookie到数据库（用于调试/验证）
     */
    public void saveZhilianCookiesToDb(String remark) {
        gate.run(() -> saveZhilianCookiesToDatabase(remark));
    }

    /**
     * 统一按平台保存 Cookie 到数据库
     *
     * @param platform 平台标识（boss/liepin/51job/zhilian）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        gate.run(() -> saveCookiesToDbInternal(platform, remark));
    }

    private void saveCookiesToDbInternal(String platform, String remark) {
        switch (platform) {
            case "boss" -> saveBossCookiesToDatabase(remark);
            case "liepin" -> saveLiepinCookiesToDatabase(remark);
            case "51job" -> save51jobCookiesToDatabase(remark);
            case "zhilian" -> saveZhilianCookiesToDatabase(remark);
            case "lagou" -> saveLagouCookiesToDatabase(remark);
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
    }

    /**
     * 清理智联招聘上下文中的Cookie
     */
    public void clearZhilianCookies() {
        gate.run(this::clearZhilianCookiesInternal);
    }

    private void clearZhilianCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停智联招聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseZhilianMonitoring() {
        zhilianMonitoringPaused = true;
        log.debug("智联招聘登录监控已暂停");
    }

    /**
     * 恢复智联招聘页面的后台登录监控
     */
    public void resumeZhilianMonitoring() {
        zhilianMonitoringPaused = false;
        log.debug("智联招聘登录监控已恢复");
    }

    /** 初始化拉勾页面、恢复 Cookie 并监控登录状态。 */
    private void setupLagouPlatform() {
        log.info("开始初始化拉勾平台...");
        lagouPage.onConsoleMessage(message -> {
            recordLagouVerificationConsoleMessage(message.text());
            log.info("拉勾页面控制台: {}", message.text());
        });
        lagouPage.onResponse(response -> {
            String url = response.url().toLowerCase(Locale.ROOT);
            if (url.contains("captcha") || url.contains("aliyun")) {
                log.info("拉勾验证码响应: status={}, url={}", response.status(), response.url());
            }
        });
        lagouPage.onRequestFailed(request -> {
            String url = request.url().toLowerCase(Locale.ROOT);
            if (url.contains("captcha") || url.contains("aliyun")) {
                log.warn("拉勾验证码请求失败: failure={}, url={}", request.failure(), request.url());
            }
        });
        try {
            migrateLagouSessionToPersistentProfile();
        } catch (Exception e) {
            log.warn("拉勾旧会话迁移未完成，继续打开页面: {}", e.getMessage());
        }
        try {
            lagouPage.navigate(LAGOU_URL, new Page.NavigateOptions()
                    .setTimeout(60000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            handleLagouAccessVerification();
            try {
                lagouPage.waitForLoadState(LoadState.NETWORKIDLE);
            } catch (Exception e) {
                log.debug("等待拉勾页面网络空闲失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("拉勾页面初始化失败: {}", e.getMessage());
        }
        setLoginStatus("lagou", checkIfLagouLoggedIn());
        lagouPage.onFrameNavigated(frame -> {
            if (frame == lagouPage.mainFrame() && !lagouMonitoringPaused) {
                gate.run(this::checkLagouLoginStatus);
            }
        });
    }

    private void migrateLagouSessionToPersistentProfile() {
        if (!persistentBrowserContext || Files.exists(LAGOU_PROFILE_MIGRATION_MARKER)) {
            log.info("拉勾会话由持久化 Chrome Profile 恢复，不再注入数据库 Cookie");
            return;
        }

        try {
            List<Cookie> preserved = context.cookies().stream()
                    .filter(cookie -> cookie.domain == null
                            || !cookie.domain.toLowerCase(Locale.ROOT).endsWith(LAGOU_DOMAIN))
                    .toList();
            context.clearCookies();
            if (!preserved.isEmpty()) {
                context.addCookies(preserved);
            }
            cookieService.clearCookieByPlatform("lagou", "migrated to persistent browser profile");
            Files.createFile(LAGOU_PROFILE_MIGRATION_MARKER);
            log.info("已移除旧拉勾数据库会话，后续只使用持久化 Chrome Profile");
        } catch (Exception e) {
            throw new IllegalStateException("迁移拉勾持久化会话失败", e);
        }
    }

    private boolean checkIfLagouLoggedIn() {
        try {
            Locator loginEntry = lagouPage.locator(
                    "a[href*='login'], button:has-text('登录'), text=/登录|注册/").first();
            if (loginEntry.isVisible()) {
                return false;
            }
            return lagouPage.locator(".user-info, .header__nav__item--user, a[href*='user']")
                    .first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private void checkLagouLoginStatus() {
        try {
            boolean loggedIn = checkIfLagouLoggedIn();
            Boolean previous = loginStatus.get("lagou");
            if (loggedIn && !Boolean.TRUE.equals(previous)) {
                setLoginStatus("lagou", true);
                saveLagouCookiesToDatabase("login success");
            } else if (!loggedIn && Boolean.TRUE.equals(previous)) {
                setLoginStatus("lagou", false);
            }
        } catch (Exception e) {
            log.debug("检查拉勾登录状态失败: {}", e.getMessage());
        }
    }

    /** 打开拉勾登录页，用户完成站点要求的验证或登录后自动保存 Cookie。 */
    public void triggerLagouLogin() {
        gate.run(() -> {
            if (lagouPage == null) {
                throw new IllegalStateException("拉勾页面未初始化");
            }
            lagouPage.navigate(LAGOU_URL, new Page.NavigateOptions()
                    .setTimeout(60000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            handleLagouAccessVerification();
            Locator loginEntry = lagouPage.locator(
                    "a[href*='login'], button:has-text('登录'), text=/登录|注册/").first();
            if (loginEntry.isVisible()) {
                loginEntry.click();
            }
            asyncWaitForLagouLogin();
        });
    }

    private void handleLagouAccessVerification() {
        long now = System.currentTimeMillis();
        if (now < lagouNextVerificationAttemptAtMs) {
            return;
        }

        try {
            lagouVerificationResult = null;
            lagouVerificationCode = "";
            if (!dragLagouAccessSliderForVerification()) {
                if (!isLagouAccessVerificationVisible()) {
                    resetLagouVerificationRetryState();
                    return;
                }
                scheduleLagouVerificationRetry("滑块尚未就绪");
                prepareNextLagouChallenge();
                return;
            }

            int attempt = ++lagouVerificationAttempts;
            log.info("已拖动拉勾访问验证滑块，第 {} 次尝试", attempt);
            Locator slider = lagouPage.locator("#aliyunCaptcha-sliding-slider");
            boolean failed = false;
            for (int i = 0; i < 40; i++) {
                lagouPage.waitForTimeout(250);
                if (!isLagouAccessVerificationVisible()) {
                    resetLagouVerificationRetryState();
                    saveLagouCookiesToDatabase("access verification success");
                    log.info("拉勾访问验证已通过");
                    return;
                }
                if (Boolean.FALSE.equals(lagouVerificationResult)) {
                    failed = true;
                    break;
                }
                if (findVisibleLagouVerificationRetry() != null || (i >= 20 && slider.isVisible())) {
                    failed = true;
                    break;
                }
            }

            if (!failed) {
                log.warn("拉勾访问验证长时间没有返回结果，本轮结束并等待下次尝试");
            } else {
                log.warn("拉勾访问验证未通过: verifyCode={}, failTip={}, errorCode={}, sliderStyle={}",
                        lagouVerificationCode,
                        safeLagouText("#aliyunCaptcha-sliding-failTip"),
                        safeLagouText("#aliyunCaptcha-sliding-errorCode"),
                        safeLagouAttribute("#aliyunCaptcha-sliding-slider", "style"));
            }
            scheduleLagouVerificationRetry(failed ? "站点拒绝本轮验证" : "站点未返回结果");
            prepareNextLagouChallenge();
        } catch (Exception e) {
            log.warn("拉勾访问验证本轮执行异常，稍后继续尝试: {}", e.getMessage());
            scheduleLagouVerificationRetry("本轮执行异常");
            reloadLagouVerificationPage();
            lagouInlineRetries = 0;
        }
    }

    private void recordLagouVerificationConsoleMessage(String message) {
        if (message == null || !message.contains("verifyResult")) {
            return;
        }
        Matcher resultMatcher = LAGOU_VERIFY_RESULT_PATTERN.matcher(message);
        if (resultMatcher.find()) {
            lagouVerificationResult = Boolean.parseBoolean(resultMatcher.group(1));
        }
        Matcher codeMatcher = LAGOU_VERIFY_CODE_PATTERN.matcher(message);
        if (codeMatcher.find()) {
            lagouVerificationCode = codeMatcher.group(1);
        }
    }

    static long lagouRetryBackoffMs(int attempt) {
        int normalizedAttempt = Math.max(1, attempt);
        return switch (Math.min(normalizedAttempt, 5)) {
            case 1 -> 30_000L;
            case 2 -> 60_000L;
            case 3 -> 120_000L;
            case 4 -> 300_000L;
            default -> 600_000L;
        };
    }

    private void scheduleLagouVerificationRetry(String reason) {
        int attempt = Math.max(1, lagouVerificationAttempts);
        long delay = lagouRetryBackoffMs(attempt);
        lagouNextVerificationAttemptAtMs = System.currentTimeMillis() + delay;
        log.warn("拉勾验证第 {} 次未通过（{}），{} 秒后允许下一轮", attempt, reason, delay / 1000);
    }

    private void prepareNextLagouChallenge() {
        if (lagouInlineRetries < LAGOU_INLINE_RETRIES_BEFORE_REFRESH
                && clickLagouVerificationRetry()) {
            lagouInlineRetries++;
            return;
        }
        reloadLagouVerificationPage();
        lagouInlineRetries = 0;
    }

    private void resetLagouVerificationRetryState() {
        lagouVerificationAttempts = 0;
        lagouInlineRetries = 0;
        lagouNextVerificationAttemptAtMs = 0L;
        lagouVerificationResult = null;
        lagouVerificationCode = "";
    }

    private boolean isLagouAccessVerificationVisible() {
        return lagouPage.locator(LAGOU_ACCESS_VERIFICATION).first().isVisible();
    }

    private String safeLagouText(String selector) {
        try {
            Locator locator = lagouPage.locator(selector);
            return locator.count() > 0 ? locator.first().textContent() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeLagouAttribute(String selector, String attribute) {
        try {
            Locator locator = lagouPage.locator(selector);
            return locator.count() > 0 ? locator.first().getAttribute(attribute) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private Locator findVisibleLagouVerificationRetry() {
        for (String selector : LAGOU_VERIFICATION_RETRY_SELECTORS) {
            try {
                Locator retry = lagouPage.locator(selector).first();
                if (retry.isVisible()) {
                    return retry;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean clickLagouVerificationRetry() {
        Locator retry = findVisibleLagouVerificationRetry();
        if (retry == null) {
            return false;
        }
        try {
            retry.click();
            lagouPage.waitForTimeout(600);
            log.info("已点击拉勾验证页面中央的重试按钮");
            return true;
        } catch (Exception e) {
            log.warn("点击拉勾验证页内重试按钮失败，将刷新整个页面: {}", e.getMessage());
            return false;
        }
    }

    private void reloadLagouVerificationPage() {
        log.warn("拉勾验证页内重试仍未通过，刷新整个页面后继续尝试");
        try {
            lagouPage.reload(new Page.ReloadOptions()
                    .setTimeout(60000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception reloadError) {
            log.warn("刷新拉勾验证页失败，重新导航后继续尝试: {}", reloadError.getMessage());
            try {
                lagouPage.navigate(LAGOU_URL, new Page.NavigateOptions()
                        .setTimeout(60000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (Exception navigateError) {
                log.warn("重新导航拉勾验证页失败，稍后继续尝试: {}", navigateError.getMessage());
            }
        }
        try {
            lagouPage.waitForTimeout(800);
        } catch (Exception ignored) {
        }
    }

    static boolean dragLagouAccessSlider(Page page) {
        return dragLagouAccessSlider(page, ThreadLocalRandom.current());
    }

    private boolean dragLagouAccessSliderForVerification() {
        if (LAGOU_NATIVE_MOUSE_ENABLED && System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win")) {
            try {
                int attempt = Math.max(1, lagouVerificationAttempts + 1);
                if (dragLagouAccessSliderWithNativeMouse(
                        lagouPage, ThreadLocalRandom.current(), attempt)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Windows原生鼠标拖动不可用，回退到Playwright鼠标: {}", e.getMessage());
            }
        }
        return dragLagouAccessSlider(lagouPage);
    }

    @SuppressWarnings("unchecked")
    static boolean dragLagouAccessSliderWithNativeMouse(Page page, RandomGenerator random)
            throws AWTException {
        return dragLagouAccessSliderWithNativeMouse(page, random, 2);
    }

    @SuppressWarnings("unchecked")
    static boolean dragLagouAccessSliderWithNativeMouse(
            Page page, RandomGenerator random, int attempt) throws AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }

        Locator challenge = page.locator(LAGOU_ACCESS_VERIFICATION).first();
        for (int i = 0; i < 60 && !challenge.isVisible(); i++) {
            page.waitForTimeout(250);
        }
        if (!challenge.isVisible()) {
            return false;
        }

        Locator track = page.locator("#aliyunCaptcha-sliding-body, .nc_scale").first();
        Locator slider = page.locator(
                "#aliyunCaptcha-sliding-slider, .nc_scale .btn_slide, .nc_scale .nc_iconfont").first();
        for (int i = 0; i < 40 && (!track.isVisible() || !slider.isVisible()); i++) {
            page.waitForTimeout(250);
        }
        if (!track.isVisible() || !slider.isVisible()) {
            return false;
        }

        var trackBox = track.boundingBox();
        var sliderBox = slider.boundingBox();
        if (trackBox == null || sliderBox == null || trackBox.width <= sliderBox.width) {
            return false;
        }

        page.bringToFront();
        page.waitForTimeout(250);
        Map<String, Number> windowMetrics = (Map<String, Number>) page.evaluate("""
                () => ({
                  screenX: window.screenX,
                  screenY: window.screenY,
                  outerWidth: window.outerWidth,
                  outerHeight: window.outerHeight,
                  innerWidth: window.innerWidth,
                  innerHeight: window.innerHeight
                })
                """);
        double horizontalBorder = Math.max(0,
                (number(windowMetrics, "outerWidth") - number(windowMetrics, "innerWidth")) / 2);
        double viewportScreenX = number(windowMetrics, "screenX") + horizontalBorder;
        double viewportScreenY = number(windowMetrics, "screenY")
                + Math.max(0, number(windowMetrics, "outerHeight")
                - number(windowMetrics, "innerHeight") - horizontalBorder);

        double startX = viewportScreenX + sliderBox.x + sliderBox.width / 2;
        double y = viewportScreenY + sliderBox.y + sliderBox.height / 2;
        double endX = viewportScreenX + trackBox.x + trackBox.width - sliderBox.width / 2;
        Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (!screenBounds.contains((int) Math.round(startX), (int) Math.round(y))
                || !screenBounds.contains((int) Math.round(endX), (int) Math.round(y))) {
            throw new IllegalStateException("换算后的滑块屏幕坐标超出桌面范围");
        }

        Robot robot = new Robot();
        robot.setAutoDelay(0);
        int speedProfile = Math.floorMod(attempt - 1, 3);
        int baseSteps = switch (speedProfile) {
            case 0 -> 38;
            case 1 -> 50;
            default -> 60;
        };
        int steps = clamp((int) Math.round(baseSteps + random.nextGaussian() * 5), 32, 68);
        double split = clamp(0.52 + random.nextGaussian() * 0.06, 0.4, 0.65);
        double accelerationPower = clamp(1.95 + random.nextGaussian() * 0.2, 1.5, 2.5);
        double decelerationPower = clamp(2.15 + random.nextGaussian() * 0.24, 1.6, 2.8);
        double verticalAmplitude = clamp(0.75 + Math.abs(random.nextGaussian()) * 0.35, 0.65, 1.8);

        double approachStartX = startX - clamp(65 + Math.abs(random.nextGaussian()) * 20, 50, 115);
        double approachStartY = y + clamp(16 + random.nextGaussian() * 5, 8, 27);
        moveNativeMouse(robot, approachStartX, approachStartY);
        sleepNative(logNormalDelay(random, 70, 0.22, 40, 125));
        int approachSteps = clamp((int) Math.round(10 + random.nextGaussian() * 2), 7, 14);
        for (int i = 1; i <= approachSteps; i++) {
            double progress = (double) i / approachSteps;
            double eased = 1 - Math.pow(1 - progress, 1.7);
            double arc = Math.sin(Math.PI * progress) * 2.0;
            moveNativeMouse(robot,
                    approachStartX + (startX - approachStartX) * eased,
                    approachStartY + (y - approachStartY) * eased - arc);
            sleepNative(logNormalDelay(random, 14, 0.22, 8, 27));
        }
        moveNativeMouse(robot, startX, y);
        sleepNative(logNormalDelay(random, 95, 0.22, 60, 165));

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            double holdMedian = switch (speedProfile) {
                case 0 -> 58;
                case 1 -> 92;
                default -> 125;
            };
            sleepNative(logNormalDelay(random, holdMedian, 0.2, 40, 190));
            double verticalNoise = 0;
            for (int i = 1; i < steps; i++) {
                double progress = (double) i / steps;
                double eased = asymmetricEase(
                        progress, split, accelerationPower, decelerationPower);
                verticalNoise = verticalNoise * 0.64 + random.nextGaussian() * 0.36;
                double verticalOffset = clamp(
                        verticalNoise * verticalAmplitude * Math.sin(Math.PI * progress), -2.0, 2.0);
                moveNativeMouse(robot, startX + (endX - startX) * eased, y + verticalOffset);
                double distanceFromFastPoint = Math.abs(progress - split);
                double localDelay = switch (speedProfile) {
                    case 0 -> 4.5 + 11 * Math.pow(distanceFromFastPoint, 1.4);
                    case 1 -> 8 + 22 * Math.pow(distanceFromFastPoint, 1.4);
                    default -> 11 + 28 * Math.pow(distanceFromFastPoint, 1.4);
                };
                sleepNative(logNormalDelay(random, localDelay, 0.24, 3, 58));
            }
            double settleBackoff = clamp(0.8 + Math.abs(random.nextGaussian()) * 0.5, 0.5, 2.0);
            moveNativeMouse(robot, endX - settleBackoff, y + clamp(random.nextGaussian() * 0.25, -0.6, 0.6));
            sleepNative(logNormalDelay(random, 62, 0.18, 36, 105));
            moveNativeMouse(robot, endX, y);
            sleepNative(logNormalDelay(random, 125, 0.2, 75, 210));
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.waitForIdle();
        String profileName = switch (speedProfile) {
            case 0 -> "fast";
            case 1 -> "normal";
            default -> "slow";
        };
        log.info("已使用Windows原生鼠标轨迹: attempt={}, profile={}, steps={}",
                attempt, profileName, steps);
        return true;
    }

    private static double number(Map<String, Number> values, String key) {
        Number value = values.get(key);
        return value == null ? 0 : value.doubleValue();
    }

    private static void moveNativeMouse(Robot robot, double x, double y) {
        robot.mouseMove((int) Math.round(x), (int) Math.round(y));
    }

    private static void sleepNative(double milliseconds) {
        try {
            Thread.sleep(Math.max(1L, Math.round(milliseconds)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("原生鼠标拖动被中断", e);
        }
    }

    static boolean dragLagouAccessSlider(Page page, RandomGenerator random) {
        Locator challenge = page.locator(LAGOU_ACCESS_VERIFICATION).first();
        for (int i = 0; i < 60 && !challenge.isVisible(); i++) {
            page.waitForTimeout(250);
        }
        if (!challenge.isVisible()) {
            return false;
        }

        Locator track = page.locator("#aliyunCaptcha-sliding-body, .nc_scale").first();
        Locator slider = page.locator(
                "#aliyunCaptcha-sliding-slider, .nc_scale .btn_slide, .nc_scale .nc_iconfont").first();
        for (int i = 0; i < 40 && (!track.isVisible() || !slider.isVisible()); i++) {
            page.waitForTimeout(250);
        }
        if (!track.isVisible() || !slider.isVisible()) {
            return false;
        }

        var trackBox = track.boundingBox();
        var sliderBox = slider.boundingBox();
        if (trackBox == null || sliderBox == null || trackBox.width <= sliderBox.width) {
            return false;
        }

        double startX = sliderBox.x + sliderBox.width / 2;
        double y = sliderBox.y + sliderBox.height / 2;
        double endX = trackBox.x + trackBox.width - sliderBox.width / 2;
        Mouse mouse = page.mouse();
        int steps = clamp((int) Math.round(52 + random.nextGaussian() * 7), 38, 72);
        double accelerationSplit = clamp(0.52 + random.nextGaussian() * 0.07, 0.38, 0.67);
        double accelerationPower = clamp(2.05 + random.nextGaussian() * 0.24, 1.55, 2.75);
        double decelerationPower = clamp(2.2 + random.nextGaussian() * 0.28, 1.6, 3.0);
        double verticalAmplitude = clamp(0.85 + Math.abs(random.nextGaussian()) * 0.42, 0.75, 2.1);

        // 先从滑块左下方以一条轻微弧线接近，避免指针从未知位置单帧跳到滑块中心。
        double approachStartX = startX - clamp(70 + Math.abs(random.nextGaussian()) * 22, 55, 125);
        double approachStartY = y + clamp(18 + random.nextGaussian() * 6, 8, 30);
        mouse.move(approachStartX, approachStartY);
        page.waitForTimeout(logNormalDelay(random, 75, 0.22, 40, 130));
        int approachSteps = clamp((int) Math.round(11 + random.nextGaussian() * 2), 8, 15);
        double approachNoise = 0;
        for (int i = 1; i <= approachSteps; i++) {
            double progress = (double) i / approachSteps;
            double eased = 1 - Math.pow(1 - progress, 1.7);
            approachNoise = approachNoise * 0.55 + random.nextGaussian() * 0.45;
            double arc = Math.sin(Math.PI * progress) * clamp(2.2 + random.nextGaussian() * 0.25, 1.5, 3.0);
            double approachX = approachStartX + (startX - approachStartX) * eased;
            double approachY = approachStartY + (y - approachStartY) * eased
                    - arc + clamp(approachNoise * 0.35, -0.65, 0.65);
            mouse.move(approachX, approachY);
            page.waitForTimeout(logNormalDelay(random, 15, 0.25, 8, 30));
        }
        mouse.move(startX, y + clamp(random.nextGaussian() * 0.18, -0.35, 0.35));
        page.waitForTimeout(logNormalDelay(random, 105, 0.24, 65, 190));
        mouse.down();
        try {
            page.waitForTimeout(logNormalDelay(random, 115, 0.22, 65, 210));
            double verticalNoise = 0;
            for (int i = 1; i < steps; i++) {
                double progress = (double) i / steps;
                double eased = asymmetricEase(
                        progress,
                        accelerationSplit,
                        accelerationPower,
                        decelerationPower);
                verticalNoise = verticalNoise * 0.62 + random.nextGaussian() * 0.38;
                double edgeDamping = Math.sin(Math.PI * progress);
                double verticalOffset = clamp(
                        verticalNoise * verticalAmplitude * edgeDamping,
                        -2.4,
                        2.4);
                mouse.move(startX + (endX - startX) * eased,
                        y + verticalOffset);

                double distanceFromFastPoint = Math.abs(progress - accelerationSplit);
                double localBaseDelay = 11 + 28 * Math.pow(distanceFromFastPoint, 1.45);
                page.waitForTimeout(logNormalDelay(random, localBaseDelay, 0.27, 6, 60));
            }

            double settleBackoff = clamp(0.7 + Math.abs(random.nextGaussian()) * 0.55, 0.5, 2.2);
            double settleY = clamp(random.nextGaussian() * 0.3, -0.7, 0.7);
            mouse.move(endX - settleBackoff, y + settleY);
            page.waitForTimeout(logNormalDelay(random, 72, 0.2, 38, 135));
            mouse.move(endX, y);
            page.waitForTimeout(logNormalDelay(random, 165, 0.22, 95, 285));
        } finally {
            mouse.up();
        }
        return true;
    }

    private static double asymmetricEase(
            double progress,
            double split,
            double accelerationPower,
            double decelerationPower) {
        if (progress <= split) {
            double local = progress / split;
            return split * Math.pow(local, accelerationPower);
        }
        double local = (progress - split) / (1 - split);
        return split + (1 - split) * (1 - Math.pow(1 - local, decelerationPower));
    }

    private static double logNormalDelay(
            RandomGenerator random,
            double median,
            double sigma,
            double minimum,
            double maximum) {
        return clamp(median * Math.exp(random.nextGaussian() * sigma), minimum, maximum);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void asyncWaitForLagouLogin() {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 300; i++) {
                if (gate.call(this::checkIfLagouLoggedIn)) {
                    gate.run(() -> {
                        setLoginStatus("lagou", true);
                        saveLagouCookiesToDatabase("login success");
                    });
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "lagou-login-waiter");
        thread.setDaemon(true);
        thread.start();
    }

    private void saveLagouCookiesToDatabase(String remark) {
        try {
            List<Cookie> cookies = filterCookiesByDomain(context.cookies(), LAGOU_DOMAIN);
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            cookieService.saveOrUpdateCookie("lagou", cookieJson, remark);
            log.info("保存拉勾 Cookie 成功，共 {} 条；完整浏览器会话由 Profile 持久化，remark={}",
                    cookies.size(), remark);
        } catch (Exception e) {
            log.warn("保存拉勾 Cookie 失败: {}", e.getMessage());
        }
    }

    public void saveLagouCookiesToDb(String remark) {
        gate.run(() -> saveLagouCookiesToDatabase(remark));
    }

    public void clearLagouCookies() {
        gate.run(() -> {
            if (context != null) {
                context.clearCookies();
            }
        });
    }

    public void pauseLagouMonitoring() {
        lagouMonitoringPaused = true;
    }

    public void resumeLagouMonitoring() {
        lagouMonitoringPaused = false;
    }

    /**
     * 检查猎聘登录状态
     *
     * @param page 页面实例
     */
    private void checkLiepinLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIfLiepinLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("liepin");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLiepinLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查猎聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 猎聘登录成功回调
     */
    private void onLiepinLoginSuccess() {
        log.info("猎聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("liepin", true);

        // 登录成功时保存 Cookie 到数据库
        saveLiepinCookiesToDatabase("login success");
    }

    /**
     * 保存猎聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveLiepinCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), LIEPIN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("liepin", cookieJson, remark);
            if (result) {
                log.info("保存猎聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存猎聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存猎聘Cookie到数据库（用于调试/验证）
     */
    public void saveLiepinCookiesToDb(String remark) {
        gate.run(() -> saveLiepinCookiesToDatabase(remark));
    }

    /**
     * 清理猎聘上下文中的Cookie
     */
    public void clearLiepinCookies() {
        gate.run(this::clearLiepinCookiesInternal);
    }

    private void clearLiepinCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停猎聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseLiepinMonitoring() {
        liepinMonitoringPaused = true;
        log.debug("猎聘登录监控已暂停");
    }

    /**
     * 恢复猎聘页面的后台登录监控
     */
    public void resumeLiepinMonitoring() {
        liepinMonitoringPaused = false;
        log.debug("猎聘登录监控已恢复");
    }

    /**
     * 检查登录状态
     *
     * @param page     页面实例
     * @param platform 平台名称
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            boolean isLoggedIn = false;
            if (platform.equals("boss")) {
                // 统一复用更稳健的Boss登录判断逻辑
                isLoggedIn = checkIfLoggedIn();
            }
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get(platform);
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLoginSuccess(platform);
            } else if (!isLoggedIn && Boolean.TRUE.equals(previousStatus)) {
                if ("boss".equals(platform)) {
                    handleBossAuthenticationExpired();
                } else {
                    setLoginStatus(platform, false);
                }
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, e.getMessage());
        }
    }

    /**
     * 登录成功回调
     *
     * @param platform 平台名称
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);

        // 更新登录状态并通知（统一使用setLoginStatus方法）
        setLoginStatus(platform, true);

        // 登录成功时保存 Cookie 到数据库（仅 boss 平台）
        if ("boss".equals(platform)) {
            saveBossCookiesToDatabase("login success");
        }
    }

    /**
     * 统一的Boss Cookie保存方法（使用JSON序列化）
     *
     * @param remark 备注信息
     */
    private void saveBossCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), BOSS_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("boss", cookieJson, remark);
            if (result) {
                log.info("保存Boss Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存Boss Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存 Boss Cookie 到数据库（用于调试/验证）
     */
    public void saveBossCookiesToDb(String remark) {
        gate.run(() -> saveBossCookiesToDatabase(remark));
    }

    /**
     * 用前端提交的 Cookie 替换 Boss 会话并立即验证。
     */
    public BossCookieLoginResult loginBossWithCookies(String rawCookies) {
        return gate.call(() -> {
            List<Cookie> cookies = filterCookiesByDomain(parseBossCookies(rawCookies), BOSS_DOMAIN);
            if (cookies.isEmpty()) {
                throw new IllegalArgumentException("未解析到有效的 zhipin.com Cookie");
            }

            replaceCookiesForDomain(BOSS_DOMAIN, cookies);
            bossPage.navigate(BOSS_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            boolean loggedIn = checkIfLoggedIn();
            setLoginStatus("boss", loggedIn);
            if (!loggedIn) {
                throw new IllegalArgumentException("Cookie 无效或已过期，请重新导出后再试");
            }

            saveBossCookiesToDatabase("frontend cookie login");
            return new BossCookieLoginResult(true, cookies.size(), bossPage.url());
        });
    }

    private void replaceCookiesForDomain(String domainSuffix, List<Cookie> replacement) {
        List<Cookie> preserved = context.cookies().stream()
                .filter(cookie -> cookie.domain == null
                        || !cookie.domain.toLowerCase(Locale.ROOT).endsWith(domainSuffix))
                .toList();
        context.clearCookies();
        if (!preserved.isEmpty()) {
            context.addCookies(preserved);
        }
        context.addCookies(replacement);
    }

    /**
     * 清理Boss上下文中的Cookie
     * 用于退出登录时清除浏览器上下文中的所有Cookie
     */
    public void clearBossCookies() {
        gate.run(this::clearBossCookiesInternal);
    }

    private void clearBossCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 定时检查登录状态（每3秒）
     * 用于捕获通过DOM元素判断登录状态的场景（无导航也可触发）
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        gate.runIfIdle(this::scheduledLoginCheckInternal);
    }

    private void scheduledLoginCheckInternal() {
        try {
            if (liepinPage != null && !liepinMonitoringPaused) {
                recoverLiepinPageIfNeeded();
                checkLiepinLoginStatus(liepinPage);
            }
            // 其他平台如需也可启用（保留，但不强制）
            if (bossPage != null && !bossMonitoringPaused) {
                checkLoginStatus(bossPage, "boss");
            }
            if (job51Page != null && !job51MonitoringPaused) {
                check51jobLoginStatus(job51Page);
            }
            Page liveZhilianPage = resolveLiveZhilianPage(false);
            if (liveZhilianPage != null && !zhilianMonitoringPaused) {
                checkZhilianLoginStatus(liveZhilianPage);
            }
            if (lagouPage != null && !lagouMonitoringPaused) {
                if (lagouPage.locator(LAGOU_ACCESS_VERIFICATION).first().isVisible()) {
                    handleLagouAccessVerification();
                } else {
                    checkLagouLoginStatus();
                }
            }
        } catch (Exception e) {
            log.debug("定时登录检测异常: {}", e.getMessage());
        }
    }

    /**
     * 暂停Boss页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseBossMonitoring() {
        bossMonitoringPaused = true;
        log.debug("Boss登录监控已暂停");
    }

    /**
     * 恢复Boss页面的后台登录监控
     */
    public void resumeBossMonitoring() {
        bossMonitoringPaused = false;
        log.debug("Boss登录监控已恢复");
    }

    /**
     * 关闭Playwright实例
     * 在Spring容器销毁前自动执行
     */
    @PreDestroy
    public void destroy() {
        gate.run(this::destroyInternal);
    }

    private void destroyInternal() {
        log.info("开始关闭Playwright管理器...");

        try {
            // 关闭所有页面
            if (bossPage != null) {
                bossPage.close();
                log.info("Boss直聘页面已关闭");
            }
            if (liepinPage != null) {
                liepinPage.close();
                log.info("猎聘页面已关闭");
            }
            if (job51Page != null) {
                job51Page.close();
                log.info("51job页面已关闭");
            }
            if (zhilianPage != null) {
                zhilianPage.close();
                log.info("智联招聘页面已关闭");
            }
            if (lagouPage != null) {
                lagouPage.close();
                log.info("拉勾页面已关闭");
            }

            // 持久化上下文关闭时会把完整站点状态写回 Profile，并同时关闭其浏览器。
            if (context != null && !connectedOverCdp) {
                context.close();
                log.info("共享BrowserContext已关闭");
            }

            if (browser != null && connectedOverCdp) {
                browser.close();
                log.info("Chrome CDP连接已关闭");
            } else if (browser != null && !persistentBrowserContext) {
                browser.close();
                log.info("浏览器已关闭");
            }
            stopChromeProcess();

            if (playwright != null) {
                playwright.close();
                log.info("Playwright实例已关闭");
            }

            log.info("Playwright管理器关闭完成！");
        } catch (Exception e) {
            log.error("关闭Playwright管理器时发生错误", e);
        }
    }

    /**
     * 检查Playwright是否已初始化
     */
    public boolean isInitialized() {
        return playwright != null && context != null && bossPage != null;
    }

    public boolean hasBrowser() {
        return context != null;
    }

    public boolean hasPage(String platform) {
        return switch (platform) {
            case "boss" -> bossPage != null;
            case "liepin" -> liepinPage != null;
            case "51job" -> job51Page != null;
            case "zhilian" -> gate.call(() -> {
                // initializePlatformPages is also used as an isolated test seam before
                // the manager has been bound to its browser context.
                if (context == null) {
                    return zhilianPage != null;
                }
                return resolveLiveZhilianPage(false) != null;
            });
            case "lagou" -> lagouPage != null;
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        };
    }

    public <T> T withPage(String platform, Function<Page, T> action) {
        return gate.call(() -> {
            Page page = switch (platform) {
                case "boss" -> bossPage;
                case "liepin" -> liepinPage;
                case "51job" -> job51Page;
                case "zhilian" -> resolveLiveZhilianPage(false);
                case "lagou" -> lagouPage;
                default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
            };
            if (page == null) {
                throw new IllegalStateException("Playwright页面未初始化: " + platform);
            }
            return action.apply(page);
        });
    }

    /**
     * Runs one complete delivery job through its own Playwright/CDP connection.
     * Each platform task therefore owns its Playwright thread while sharing the visible Chrome window.
     */
    public <T> T withDeliveryPage(String platform, Function<Page, T> action) {
        URI endpoint = URI.create("http://127.0.0.1:" + CHROME_DEBUG_PORT + "/json/version");
        if (!isCdpEndpointReady(endpoint)) {
            return withPage(platform, action);
        }

        try (Playwright deliveryPlaywright = Playwright.create()) {
            Browser deliveryBrowser = deliveryPlaywright.chromium().connectOverCDP(
                    "http://127.0.0.1:" + CHROME_DEBUG_PORT);
            if (deliveryBrowser.contexts().isEmpty()) {
                throw new IllegalStateException("投递浏览器上下文不可用: " + platform);
            }
            Page page = findPlatformPage(deliveryBrowser.contexts().get(0), platform);
            if (page == null) {
                throw new IllegalStateException("Playwright页面未初始化: " + platform);
            }
            page.setDefaultTimeout(DEFAULT_TIMEOUT);
            return action.apply(page);
        }
    }

    Page findPlatformPage(BrowserContext browserContext, String platform) {
        String domain = switch (platform) {
            case "boss" -> BOSS_DOMAIN;
            case "liepin" -> LIEPIN_DOMAIN;
            case "51job" -> JOB51_DOMAIN;
            case "zhilian" -> ZHILIAN_DOMAIN;
            case "lagou" -> LAGOU_DOMAIN;
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        };
        return browserContext.pages().stream()
                .filter(page -> !isPageClosed(page))
                .filter(page -> pageMatchesDomain(page, domain))
                .filter(this::isTopLevelPage)
                .findFirst()
                .orElse(null);
    }

    public Map<String, Object> getZhilianSessionStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("loginState", zhilianLoginState);
        status.put("pageState", zhilianPageState);
        status.put("pageAlive", "CONNECTED".equals(zhilianPageState));
        status.put("pageUrl", zhilianPageUrl);
        status.put("message", zhilianStateMessage);
        status.put("lastCheckedAt", zhilianLastCheckedAt);
        return status;
    }

    public boolean refreshZhilianLoginStatus() {
        return gate.call(() -> {
            Page page = resolveLiveZhilianPage(false);
            if (page == null) {
                zhilianLoginState = ZhilianLoginState.UNKNOWN.name();
                return false;
            }
            checkZhilianLoginStatus(page);
            return isLoggedIn("zhilian");
        });
    }

    public Map<String, String> testBossNavigation() {
        return withPage("boss", page -> {
            page.navigate(BOSS_URL);
            return Map.of("title", page.title(), "url", page.url());
        });
    }

    public Map<String, Object> getLagouPageStatus() {
        return withPage("lagou", page -> Map.of(
                "url", page.url(),
                "title", page.title(),
                "accessVerificationVisible",
                page.locator(LAGOU_ACCESS_VERIFICATION).first().isVisible(),
                "verificationCode", lagouVerificationCode,
                "verificationAttempts", lagouVerificationAttempts,
                "nextVerificationAttemptAt", lagouNextVerificationAttemptAtMs
        ));
    }

    /**
     * 注册登录状态监听器
     *
     * @param listener 监听器
     */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    /**
     * 移除登录状态监听器
     *
     * @param listener 监听器
     */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    /**
     * 获取平台登录状态
     *
     * @param platform 平台名称
     * @return 是否已登录
     */
    public boolean isLoggedIn(String platform) {
        return loginStatus.getOrDefault(platform, false);
    }

    /**
     * 手动设置平台登录状态（会触发SSE通知）
     *
     * @param platform   平台名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previousStatus = loginStatus.get(platform);

        if ("zhilian".equals(platform)) {
            zhilianLoginState = isLoggedIn
                    ? ZhilianLoginState.LOGGED_IN.name()
                    : ("CONNECTED".equals(zhilianPageState)
                    ? ZhilianLoginState.LOGGED_OUT.name()
                    : ZhilianLoginState.UNKNOWN.name());
        }

        // 只有状态真正发生变化时才更新和通知
        if (previousStatus == null || previousStatus != isLoggedIn) {
            loginStatus.put(platform, isLoggedIn);

            // 通知所有监听器（触发SSE推送）
            LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
            loginStatusListeners.forEach(listener -> {
                try {
                    listener.accept(change);
                } catch (Exception e) {
                    log.error("通知登录状态监听器失败: platform={}, isLoggedIn={}", platform, isLoggedIn, e);
                }
            });

//            log.info("登录状态已更新: platform={}, isLoggedIn={}", platform, isLoggedIn);
        }
    }

    /** Boss 会话失效：只清理 Boss 持久化 Cookie，不触碰共享浏览器上下文。 */
    public void handleBossAuthenticationExpired() {
        setLoginStatus("boss", false);
        try {
            cookieService.clearCookieByPlatform("boss", "authentication expired");
        } catch (Exception e) {
            log.warn("清空 Boss 持久化 Cookie 失败: {}", e.getMessage());
        }
    }

    /**
     * 从JSON字符串解析Cookie列表
     *
     * @param cookieJson Cookie的JSON字符串
     * @return Cookie列表
     */
    private static List<Cookie> parseCookiesFromString(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cookieJson);

            for (com.fasterxml.jackson.databind.JsonNode node : jsonArray) {
                // 创建Cookie对象（name和value是必需的）
                Cookie cookie = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText()
                );

                // 设置可选字段
                if (node.has("domain") && !node.get("domain").isNull()) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.has("path") && !node.get("path").isNull()) {
                    cookie.path = node.get("path").asText();
                }
                if (node.has("expires") && !node.get("expires").isNull()) {
                    cookie.expires = node.get("expires").asDouble();
                } else if (node.has("expirationDate") && !node.get("expirationDate").isNull()) {
                    cookie.expires = node.get("expirationDate").asDouble();
                }
                if (node.has("httpOnly") && !node.get("httpOnly").isNull()) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.has("secure") && !node.get("secure").isNull()) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.has("sameSite") && !node.get("sameSite").isNull()) {
                    String sameSite = node.get("sameSite").asText("").toLowerCase(Locale.ROOT);
                    cookie.sameSite = switch (sameSite) {
                        case "strict" -> com.microsoft.playwright.options.SameSiteAttribute.STRICT;
                        case "lax" -> com.microsoft.playwright.options.SameSiteAttribute.LAX;
                        case "none", "no_restriction" -> com.microsoft.playwright.options.SameSiteAttribute.NONE;
                        default -> null;
                    };
                }

                cookies.add(cookie);
            }

            log.debug("成功解析Cookie，共 {} 条", cookies.size());
        } catch (Exception e) {
            log.error("解析Cookie JSON失败: {}", e.getMessage(), e);
        }

        return cookies;
    }

    static List<Cookie> parseBossCookies(String rawCookies) {
        if (rawCookies == null || rawCookies.isBlank()) {
            return List.of();
        }

        String value = rawCookies.trim();
        if (value.startsWith("[")) {
            List<Cookie> cookies = parseCookiesFromString(value);
            cookies.forEach(cookie -> {
                if (cookie.domain == null || cookie.domain.isBlank()) {
                    cookie.domain = ".zhipin.com";
                }
                if (cookie.path == null || cookie.path.isBlank()) {
                    cookie.path = "/";
                }
            });
            return cookies;
        }

        List<Cookie> cookies = new ArrayList<>();
        for (String part : value.split(";")) {
            String pair = part.trim();
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            Cookie cookie = new Cookie(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
            cookie.domain = ".zhipin.com";
            cookie.path = "/";
            cookies.add(cookie);
        }
        return cookies;
    }

    private static List<Cookie> filterCookiesByDomain(List<Cookie> cookies, String domainSuffix) {
        if (cookies == null || cookies.isEmpty()) {
            return new ArrayList<>();
        }

        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }

        return filtered;
    }

    public record BossCookieLoginResult(boolean loggedIn, int cookieCount, String url) {
    }

    /**
     * LoginStatusChange - 登录状态变化DTO
     */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
