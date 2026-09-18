package com.getjobs.worker.lagou;

import com.getjobs.application.entity.LagouJobDataEntity;
import com.getjobs.application.service.LagouService;
import com.getjobs.worker.utils.DeliveryLimit;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 拉勾岗位采集与投递；浏览器调用由任务服务的 delivery thread 串行执行。 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Lagou {
    private static final String SEARCH_URL = "https://www.lagou.com/wn/jobs";
    private static final int MAX_PAGES_PER_KEYWORD = 100;
    private static final int MAX_CANDIDATES_PER_PAGE = 200;
    private static final int WAIT_MS = 8000;

    @Setter private Page page;
    @Setter private LagouConfig config;
    @Setter private ProgressCallback progressCallback;
    @Setter private Supplier<Boolean> shouldStopCallback;
    private final LagouService lagouService;
    private int deliveryAttempts;
    private int delivered;
    private int skipped;
    private int failed;
    private int uncertain;
    private int opened;
    private int consecutiveOpenFailures;
    private final Set<String> handledIds = new HashSet<>();

    public enum EndState { COMPLETED, STOPPED, LIMITED, ERROR, PENDING }
    public record Result(int delivered, int skipped, int failed, int uncertain, int attempts,
                         EndState state, String reason) {
        public String summary() {
            return String.format("%s；成功 %d，跳过 %d，失败 %d，待确认 %d，尝试 %d",
                    reason, delivered, skipped, failed, uncertain, attempts);
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    public void prepare() {
        deliveryAttempts = delivered = skipped = failed = uncertain = opened = consecutiveOpenFailures = 0;
        handledIds.clear();
    }

    public Result execute() {
        if (!isConfigValid(config)) return finish(EndState.ERROR, "拉勾配置无效，请检查关键词、简历和数量");
        if (page == null) return finish(EndState.ERROR, "拉勾页面未初始化");
        int target = config.getMaxCount() == null ? 30 : config.getMaxCount();
        try {
            if (shouldStop()) return finish(EndState.STOPPED, "用户停止任务");
            if (deliveryLimitReached()) return finish(EndState.LIMITED, "已达到提交尝试额度");
            for (String keyword : config.getKeywords()) {
                if (keyword == null || keyword.isBlank()) continue;
                Set<String> pageSignatures = new HashSet<>();
                for (int number = 1; number <= MAX_PAGES_PER_KEYWORD; number++) {
                    checkStop();
                    if (delivered >= target) return finish(EndState.COMPLETED, "已达到成功投递目标");
                    if (deliveryLimitReached()) return finish(EndState.LIMITED, "已达到提交尝试额度");
                    progress("读取列表：关键词 " + keyword + "，第 " + number + " 页");
                    String searchUrl = buildSearchUrl(keyword, config.getCity(), number);
                    navigateSearch(searchUrl);
                    LagouPage.Collection collection = readCards();
                    if (collection.candidates().isEmpty()) {
                        progress("当前关键词没有匹配岗位");
                        break;
                    }
                    String signature = signature(collection);
                    if (!pageSignatures.add(signature)) return finish(EndState.ERROR, "翻页后岗位集合重复，停止分页");
                    int beforeOpened = opened, beforeSkipped = skipped, beforeFailed = failed, beforeDelivered = delivered;
                    progress("列表识别：候选节点 " + collection.rawCount() + "，有效岗位 " + collection.candidates().size());
                    Set<String> seenCandidateKeys = new HashSet<>();
                    while (true) {
                        checkStop();
                        if (delivered >= target) return finish(EndState.COMPLETED, "已达到成功投递目标");
                        if (deliveryLimitReached()) return finish(EndState.LIMITED, "已达到提交尝试额度");
                        // A same-tab detail view can remove or reorder cards. Only click a candidate
                        // freshly collected from the restored list; never reuse a stale locator or DOM path.
                        LagouPage.Candidate candidate = collection.candidates().stream()
                                .filter(card -> seenCandidateKeys.add(card.key())).findFirst().orElse(null);
                        if (candidate == null) break;
                        if (seenCandidateKeys.size() > MAX_CANDIDATES_PER_PAGE)
                            return finish(EndState.LIMITED, "当前页候选持续变化，已达到单页处理上限");
                        process(candidate, searchUrl);
                        collection = readCards();
                    }
                    progress(String.format("本页完成：有效岗位 %d，详情打开 %d，成功 %d，跳过 %d，失败 %d",
                            collection.candidates().size(), opened - beforeOpened, delivered - beforeDelivered,
                            skipped - beforeSkipped, failed - beforeFailed));
                    if (!LagouPage.nextEnabled(page)) break;
                    if (number == MAX_PAGES_PER_KEYWORD) return finish(EndState.LIMITED, "已达到单关键词分页上限");
                }
            }
            return finish(EndState.LIMITED,
                    String.format("可用岗位已处理完，仅完成 %d/%d 个成功投递", delivered, target));
        } catch (PendingDelivery e) {
            return finish(EndState.PENDING, e.getMessage());
        } catch (Stopped e) {
            return finish(EndState.STOPPED, "用户停止任务");
        } catch (Exception e) {
            log.error("拉勾流程中止，保留已确认的投递计数", e);
            diagnostics(page);
            return finish(shouldStop() ? EndState.STOPPED : EndState.ERROR,
                    shouldStop() ? "用户停止任务" : e.getMessage());
        }
    }

    private void navigateSearch(String url) {
        checkStop();
        page.navigate(url, new Page.NavigateOptions().setTimeout(30000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        LagouPage.checkCritical(page);
    }

    private LagouPage.Collection readCards() {
        await(page, () -> !LagouPage.collect(page).candidates().isEmpty() || LagouPage.empty(page), WAIT_MS);
        LagouPage.checkCritical(page);
        LagouPage.Collection cards = LagouPage.collect(page);
        if (cards.candidates().isEmpty() && !LagouPage.empty(page)) {
            progress("岗位解析为空，等待页面完成渲染后重新采集一次");
            await(page, () -> !LagouPage.collect(page).candidates().isEmpty() || LagouPage.empty(page), WAIT_MS);
            cards = LagouPage.collect(page);
            if (cards.candidates().isEmpty() && !LagouPage.empty(page))
                throw new IllegalStateException("候选节点 " + cards.rawCount() + "，有效岗位 0；页面未就绪或岗位结构变化，停止分页");
        }
        return cards;
    }

    private String signature(LagouPage.Collection cards) {
        return cards.candidates().stream().map(LagouPage.Candidate::key).sorted().reduce("", (a, b) -> a + "\n" + b);
    }

    private void process(LagouPage.Candidate candidate, String searchUrl) {
        LagouJobDataEntity job = candidate.job();
        String jobId = job.getJobId();
        if (jobId != null && skipPersisted(jobId)) return;
        Page detail = null;
        boolean submitted = false;
        boolean pending = false;
        try {
            if (jobId != null) lagouService.saveOrUpdateJob(job);
            progress("打开详情：" + job.getJobTitle());
            try {
                detail = openDetail(candidate);
            } catch (OpenFailed e) {
                failed++;
                consecutiveOpenFailures++;
                log.warn("打开拉勾详情失败：标题={}，岗位ID={}，连续失败={}",
                        job.getJobTitle(), jobId, consecutiveOpenFailures, e);
                diagnostics(page);
                if (jobId != null) markStatus(jobId, "投递失败");
                progress("详情打开失败：" + job.getJobTitle() + "；" + e.getMessage());
                if (consecutiveOpenFailures >= 3)
                    throw new OpenFailed("连续三个不同岗位打开详情失败，停止任务", e);
                return;
            }
            consecutiveOpenFailures = 0;
            opened++;
            LagouPage.checkCritical(detail);
            jobId = LagouPage.jobId(detail.url());
            job.setJobId(jobId);
            job.setJobLink(LagouPage.detailUrl(detail.url()));
            if (skipPersisted(jobId)) return;
            handledIds.add(jobId);
            String title = LagouPage.text(detail.locator("body"), "h1, .position-head-wrap-position-name");
            if (title != null && !title.isBlank()) job.setJobTitle(title);
            String company = LagouPage.text(detail.locator("body"), ".company-name, [class*='company-name']");
            if (company != null && !company.isBlank()) job.setCompanyName(company);
            String salary = LagouPage.text(detail.locator("body"), ".salary, [class*='salary']");
            if (salary != null && !salary.isBlank()) job.setSalary(salary);
            lagouService.saveOrUpdateJob(job);
            Page target = detail;
            if (!await(target, () -> LagouPage.apply(target) != null || LagouPage.delivered(target)
                    || LagouPage.action(target, "立即沟通") != null, WAIT_MS))
                throw new IllegalStateException("详情已打开但未识别投递入口：" + jobId);
            if (LagouPage.delivered(target)) {
                markStatus(jobId, "已投递");
                skipped++;
                progress("跳过已投递岗位：" + jobId);
                return;
            }
            Locator apply = LagouPage.apply(target);
            if (apply == null) {
                skipped++;
                progress("岗位 " + jobId + " 仅有沟通入口，未确认投递状态，跳过");
                return;
            }
            progress("选择指定简历：" + jobId);
            // Resume selection happens on the detail page, before any submission click.
            if (!await(target, () -> LagouPage.hasResumeOptions(target, config), WAIT_MS))
                throw new IllegalStateException("详情页未识别到可选简历，请检查简历配置：" + jobId);
            LagouPage.selectResume(target, config);
            checkStop();
            if (deliveryLimitReached()) return;
            Locator submit = LagouPage.apply(target);
            if (submit == null) throw new IllegalStateException("指定简历已选中，但未找到提交按钮：" + jobId);
            boolean hadSuccess = LagouPage.success(target);
            progress("提交并等待结果：" + jobId);
            checkStop();
            // Write before the irreversible click: a crash or disconnect leaves a durable no-retry marker.
            markStatus(jobId, "待确认");
            deliveryAttempts++;
            submitted = true;
            submit.click(new Locator.ClickOptions().setTimeout(WAIT_MS));
            await(target, () -> LagouPage.delivered(target) || (!hadSuccess && LagouPage.success(target))
                    || LagouPage.failed(target) || LagouPage.optionalDeliveryConfirm(target) != null, WAIT_MS);
            Locator externalConfirm = LagouPage.optionalDeliveryConfirm(target);
            if (externalConfirm != null) {
                progress(("继续投递".equals(externalConfirm.innerText().trim()) ? "继续投递：" : "确认同步投递：") + jobId);
                checkStop();
                externalConfirm.click(new Locator.ClickOptions().setTimeout(WAIT_MS));
                await(target, () -> LagouPage.delivered(target) || (!hadSuccess && LagouPage.success(target))
                        || LagouPage.failed(target), WAIT_MS);
            }
            if (LagouPage.delivered(target) || (!hadSuccess && LagouPage.success(target))) {
                markStatus(jobId, "已投递");
                submitted = false;
                delivered++;
                progress("投递成功：" + jobId);
            } else if (LagouPage.failed(target)) {
                markStatus(jobId, "投递失败");
                submitted = false;
                failed++;
                progress("平台明确提示投递失败：" + jobId);
            } else {
                throw new IllegalStateException("提交后未获得明确结果");
            }
        } catch (Exception e) {
            diagnostics(detail == null ? page : detail);
            if (submitted) {
                uncertain++;
                pending = true;
                log.warn("拉勾岗位 {} 提交结果待确认，停止自动重试", jobId, e);
                throw new PendingDelivery("岗位 " + jobId + " 投递结果待确认，已停止；请核对平台投递记录");
            }
            if (!(e instanceof Stopped) && !(e instanceof OpenFailed)) failed++;
            throw e;
        } finally {
            if (!pending && detail != null && detail != page && !detail.isClosed()) {
                detail.close();
            }
            if (!pending && !page.isClosed() && !page.url().equals(searchUrl) && !shouldStop()) {
                navigateSearch(searchUrl);
                readCards();
            }
        }
    }

    private boolean skipPersisted(String jobId) {
        if (handledIds.contains(jobId)) {
            skipped++;
            progress("跳过本轮已处理岗位：" + jobId);
            return true;
        }
        String status = lagouService.getJobDeliveryStatus(jobId);
        if ("待确认".equals(status)) throw new PendingDelivery("岗位 " + jobId + " 上次投递结果待确认，请先核对平台记录");
        if ("已投递".equals(status)) {
            handledIds.add(jobId);
            skipped++;
            progress("跳过已记录投递岗位：" + jobId);
            return true;
        }
        return false;
    }

    private Page openDetail(LagouPage.Candidate candidate) {
        String original = page.url();
        List<Page> popups = new ArrayList<>();
        Consumer<Page> listener = popups::add;
        page.onPopup(listener);
        try {
            checkStop();
            candidate.title().click(new Locator.ClickOptions().setTimeout(WAIT_MS));
            Page[] found = new Page[1];
            boolean ready = await(page, () -> {
                for (Page popup : popups) {
                    if (!popup.isClosed() && LagouPage.jobId(popup.url()) != null) {
                        found[0] = popup;
                        return true;
                    }
                }
                if (!original.equals(page.url()) && LagouPage.jobId(page.url()) != null) {
                    found[0] = page;
                    return true;
                }
                return false;
            }, WAIT_MS);
            if (!ready) throw new OpenFailed("点击后未出现拉勾岗位详情");
            String actual = LagouPage.jobId(found[0].url());
            String expected = candidate.job().getJobId();
            if (expected != null && !expected.equals(actual)) throw new OpenFailed("详情岗位 ID 与列表不一致");
            return found[0];
        } catch (Stopped e) {
            throw e;
        } catch (Exception e) {
            // Close only job popups opened by this click, never an existing platform tab.
            for (Page popup : popups)
                if (!popup.isClosed() && LagouPage.jobId(popup.url()) != null) popup.close();
            throw e instanceof OpenFailed open ? open : new OpenFailed(e.getMessage(), e);
        } finally {
            page.offPopup(listener);
        }
    }

    private boolean await(Page target, BooleanSupplier ready, int timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            checkStop();
            if (ready.getAsBoolean()) return true;
            LagouPage.checkCritical(target);
            if (System.nanoTime() >= deadline) return false;
            target.waitForTimeout(100);
        }
    }

    private void checkStop() { if (shouldStop()) throw new Stopped(); }
    private void diagnostics(Page target) {
        if (target == null || target.isClosed() || shouldStop()) return;
        try {
            java.nio.file.Path directory = java.nio.file.Path.of("target", "lagou-diagnostics");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Path file = java.nio.file.Files.createTempFile(directory, "page-", ".json");
            java.nio.file.Files.writeString(file, LagouPage.diagnostic(target), StandardCharsets.UTF_8);
            log.warn("拉勾页面结构诊断已保存：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("保存拉勾页面结构诊断失败", e);
        }
    }
    private boolean shouldStop() {
        return Thread.currentThread().isInterrupted() || shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get());
    }
    private boolean deliveryLimitReached() { return deliveryAttempts >= DeliveryLimit.configuredMax(); }
    private void markStatus(String id, String status) {
        LagouJobDataEntity job = new LagouJobDataEntity();
        job.setJobId(id);
        job.setDeliveryStatus(status);
        lagouService.saveOrUpdateJob(job);
    }
    private void progress(String stage) {
        String message = String.format("%s | 成功 %d，跳过 %d，失败 %d，待确认 %d，尝试 %d",
                stage, delivered, skipped, failed, uncertain, deliveryAttempts);
        log.info("[lagou] {}", message);
        if (progressCallback != null) progressCallback.accept(message, delivered, config.getMaxCount());
    }
    private Result finish(EndState state, String reason) {
        Result result = new Result(delivered, skipped, failed, uncertain, deliveryAttempts, state, reason);
        if (progressCallback != null) progressCallback.accept(result.summary(), delivered, config == null ? null : config.getMaxCount());
        return result;
    }
    private static class Stopped extends RuntimeException { }
    private static class PendingDelivery extends RuntimeException {
        PendingDelivery(String message) { super(message); }
    }
    private static class OpenFailed extends RuntimeException {
        OpenFailed(String message) { super(message); }
        OpenFailed(String message, Throwable cause) { super(message, cause); }
    }

    public static List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String normalized = raw.trim().replace('，', ',');
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String item : normalized.split(",")) {
            String value = item.trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isEmpty()) keywords.add(value);
        }
        return new ArrayList<>(keywords);
    }

    public static String buildSearchUrl(String keyword, String city, int pageNumber) {
        String encodedKeyword = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8)
                .replace("+", "%20");
        StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?kd=").append(encodedKeyword)
                .append("&pn=").append(Math.max(1, pageNumber));
        if (city != null && !city.isBlank() && !"全国".equals(city.trim()) && !"不限".equals(city.trim())) {
            url.append("&city=")
                    .append(URLEncoder.encode(city.trim(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return url.toString();
    }

    public static boolean isConfigValid(LagouConfig config) {
        return config != null && config.getKeywords() != null
                && config.getKeywords().stream().anyMatch(keyword -> keyword != null && !keyword.isBlank())
                && (!"ATTACHMENT".equalsIgnoreCase(config.getResumeType())
                        || (config.getResumeName() != null && !config.getResumeName().isBlank()))
                && (config.getMaxCount() == null || config.getMaxCount() > 0);
    }

    /** 附件名称锁定后只接受精确命中。 */
    public static Optional<String> selectResume(List<String> attachmentNames, String requestedName) {
        if (attachmentNames == null || requestedName == null || requestedName.isBlank()) return Optional.empty();
        List<String> matches = attachmentNames.stream()
                .filter(name -> LagouPage.normalizeResumeName(requestedName).equals(LagouPage.normalizeResumeName(name)))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }


    static boolean isPaginationEnabled(String classes, String ariaDisabled) {
        return (classes == null || !classes.contains("disabled")) && !"true".equalsIgnoreCase(ariaDisabled);
    }
}
