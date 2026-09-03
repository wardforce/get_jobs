package com.getjobs.worker.lagou;

import com.getjobs.application.entity.LagouJobDataEntity;
import com.getjobs.application.service.LagouService;
import com.getjobs.worker.utils.DeliveryLimit;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Supplier;

/** 拉勾岗位采集与投递 worker。 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Lagou {
    private static final String SEARCH_URL = "https://www.lagou.com/wn/jobs";
    private static final int MAX_PAGES_PER_KEYWORD = 100;

    @Setter private Page page;
    @Setter private LagouConfig config;
    @Setter private ProgressCallback progressCallback;
    @Setter private Supplier<Boolean> shouldStopCallback;
    private final LagouService lagouService;
    private int deliveryAttempts;

    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    public void prepare() {
        deliveryAttempts = 0;
    }

    public int execute() {
        if (!isConfigValid(config)) {
            sendProgress("拉勾配置无有效搜索关键词", null, null);
            return 0;
        }
        if (page == null) {
            sendProgress("拉勾页面未初始化", null, null);
            return 0;
        }
        int delivered = 0;
        for (String keyword : config.getKeywords()) {
            for (int pageNumber = 1; pageNumber <= MAX_PAGES_PER_KEYWORD
                    && !shouldStop() && !deliveryLimitReached(); pageNumber++) {
                page.navigate(buildSearchUrl(keyword, config.getCity(), pageNumber));
                page.waitForTimeout(1200);
                detectCriticalDeliveryState();
                List<Locator> cards = jobCards();
                if (cards.isEmpty()) {
                    detectCriticalDeliveryState();
                    sendProgress("当前页没有可处理的拉勾岗位，结束该关键词分页", pageNumber, null);
                    break;
                }
                for (Locator card : cards) {
                    if (shouldStop() || deliveryLimitReached()) break;
                    String jobId = persistCard(card);
                    if (jobId == null) continue;
                    if (submitCard(card, jobId)) delivered++;
                }
                sendProgress("已处理拉勾搜索页", pageNumber, null);
                if (!hasNextPage()) break;
            }
        }
        return delivered;
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
                        || (config.getResumeName() != null && !config.getResumeName().isBlank()));
    }

    /** 附件名称锁定后只接受精确命中。 */
    public static Optional<String> selectResume(List<String> attachmentNames, String requestedName) {
        if (attachmentNames == null || requestedName == null || requestedName.isBlank()) return Optional.empty();
        return attachmentNames.stream()
                .filter(name -> requestedName.trim().equals(normalizeResumeName(name)))
                .findFirst();
    }

    private List<Locator> jobCards() {
        Locator all = null;
        for (String selector : List.of(
                "#openWinPostion, [data-job-id], [data-jobid]",
                ".job-item, .job-card, [class*='job-item'], [class*='job-card']")) {
            Locator candidate = page.locator(selector);
            if (candidate.count() > 0) {
                all = candidate;
                break;
            }
        }
        List<Locator> cards = new ArrayList<>();
        if (all == null) return cards;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < all.count(); index++) {
            Locator card = all.nth(index);
            if (!card.isVisible()) continue;
            String key = card.getAttribute("data-job-id");
            if (key == null || key.isBlank()) key = card.getAttribute("data-jobid");
            if (key == null || key.isBlank()) {
                Locator link = card.locator("a[href*='/jobs/'], a[href*='job']").first();
                key = link.count() > 0 ? link.getAttribute("href") : card.innerText();
            }
            if (seen.add(key)) cards.add(card);
        }
        return cards;
    }

    private String persistCard(Locator card) {
        try {
            String jobId = card.getAttribute("data-job-id");
            if (jobId == null || jobId.isBlank()) jobId = card.getAttribute("data-jobid");
            String jobLink = card.getAttribute("href");
            Locator link = card.locator("a[href*='jobs'], a[href*='job']").first();
            if (jobLink == null || jobLink.isBlank()) jobLink = link.count() > 0 ? link.getAttribute("href") : null;
            if (jobId == null || jobId.isBlank()) jobId = jobLink;
            if (jobId == null || jobId.isBlank()) return null;
            LagouJobDataEntity job = new LagouJobDataEntity();
            job.setJobId(jobId);
            job.setJobLink(jobLink);
            String cardText = card.innerText();
            job.setJobTitle(firstNonBlank(text(card, ".job-name, .position-name, h3"), firstLine(cardText)));
            job.setCompanyName(firstNonBlank(text(card, ".company-name, .company"), text(card, "[class*='company']")));
            job.setSalary(firstNonBlank(text(card, ".salary"), text(card, "[class*='salary']")));
            job.setLocation(firstNonBlank(text(card, ".job-area, .location"), text(card, "[class*='location'], [class*='area']")));
            job.setExperience(firstNonBlank(text(card, ".experience"), text(card, "[class*='experience']")));
            job.setDegree(firstNonBlank(text(card, ".education, .degree"), text(card, "[class*='education'], [class*='degree']")));
            job.setIndustry(text(card, ".industry, [class*='industry']"));
            job.setCompanyScale(text(card, ".company-size, .company-scale, [class*='company-size']"));
            lagouService.saveOrUpdateJob(job);
            return jobId;
        } catch (Exception e) {
            log.debug("采集拉勾岗位卡片失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean submitCard(Locator card, String jobId) {
        try {
            card.click();
            page.waitForTimeout(500);
            Locator detail = page.locator("button:has-text('投简历'), a:has-text('投简历'), [class*='deliver']");
            if (hasVisibleText(detail, "已投递")) {
                markStatus(jobId, "已投递");
                return false;
            }
            Locator apply = firstVisible(page.locator(
                    "button:has-text('投简历'), a:has-text('投简历'), [class*='deliver'], button:has-text('立即投递')"));
            if (apply == null) {
                detectCriticalDeliveryState();
                return false;
            }
            if (deliveryLimitReached()) return false;
            deliveryAttempts++;
            apply.click();
            page.waitForTimeout(300);
            selectConfiguredResume();
            Locator confirm = firstVisible(page.getByText("确认投递", new Page.GetByTextOptions().setExact(true)));
            if (confirm == null) throw new IllegalStateException("拉勾投递弹窗缺少确认按钮");
            confirm.click();
            page.waitForTimeout(1200);
            boolean success = hasVisible(page.getByText("投递成功", new Page.GetByTextOptions().setExact(false)))
                    || hasVisibleText(detail, "已投递");
            if (success) markStatus(jobId, "已投递");
            else {
                markStatus(jobId, "投递失败");
                detectCriticalDeliveryState();
            }
            return success;
        } catch (Exception e) {
            if (e instanceof CriticalDeliveryException) throw (CriticalDeliveryException) e;
            if (e instanceof MissingResumeException) throw (MissingResumeException) e;
            markStatus(jobId, "投递失败");
            log.debug("投递拉勾岗位失败: {}", e.getMessage());
            return false;
        }
    }

    private void selectConfiguredResume() {
        if (!"ATTACHMENT".equalsIgnoreCase(config.getResumeType())) {
            Locator online = firstVisible(page.locator("label, [role='radio'], .resume-item")
                    .filter(new Locator.FilterOptions().setHasText("在线简历")));
            if (online != null) online.click();
            return;
        }
        String requested = config.getResumeName();
        Locator names = page.locator(".resume-item, [data-resume-name], [class*='resume']");
        List<String> available = new ArrayList<>();
        for (int index = 0; index < names.count(); index++) available.add(names.nth(index).innerText());
        Optional<String> selected = selectResume(available, requested);
        if (selected.isEmpty()) {
            sendProgress("未找到已锁定的附件简历：" + requested, null, null);
            throw new MissingResumeException("未找到已锁定的附件简历：" + requested);
        }
        for (int index = 0; index < names.count(); index++) {
            Locator candidate = names.nth(index);
            if (requested.trim().equals(normalizeResumeName(candidate.innerText()))) {
                candidate.click();
                return;
            }
        }
        throw new MissingResumeException("未找到已锁定的附件简历：" + requested);
    }

    private void markStatus(String jobId, String status) {
        if (jobId == null || jobId.isBlank()) return;
        LagouJobDataEntity job = new LagouJobDataEntity();
        job.setJobId(jobId);
        job.setDeliveryStatus(status);
        lagouService.saveOrUpdateJob(job);
    }

    private boolean hasNextPage() {
        try {
            Locator next = firstVisible(page.locator("button:has-text('下一页'), a:has-text('下一页'), .lg-pagination-item-link:last-child, [class*='pagination'] [class*='next']"));
            if (next == null) return false;
            String classes = next.getAttribute("class");
            String disabled = next.getAttribute("aria-disabled");
            return isPaginationEnabled(classes, disabled);
        } catch (Exception ignored) { return false; }
    }

    static boolean isPaginationEnabled(String classes, String ariaDisabled) {
        return (classes == null || !classes.contains("disabled")) && !"true".equalsIgnoreCase(ariaDisabled);
    }

    private void detectCriticalDeliveryState() {
        try {
            String body = page.locator("body").innerText();
            if (body.contains("今日投递上限") || body.contains("达到投递上限") || body.contains("访问验证")) {
                throw new CriticalDeliveryException("拉勾提示当前投递受限，任务已停止");
            }
        } catch (CriticalDeliveryException e) {
            throw e;
        } catch (Exception ignored) { }
    }

    private boolean deliveryLimitReached() {
        return deliveryAttempts >= DeliveryLimit.configuredMax();
    }

    private static Locator firstVisible(Locator locator) {
        try {
            for (int index = 0; index < locator.count(); index++) {
                Locator candidate = locator.nth(index);
                if (candidate.isVisible()) return candidate;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean hasVisible(Locator locator) {
        return firstVisible(locator) != null;
    }

    private static boolean hasVisibleText(Locator locator, String expected) {
        try {
            for (int index = 0; index < locator.count(); index++) {
                Locator candidate = locator.nth(index);
                if (candidate.isVisible() && candidate.innerText().contains(expected)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static class MissingResumeException extends RuntimeException {
        MissingResumeException(String message) { super(message); }
    }

    private static class CriticalDeliveryException extends RuntimeException {
        CriticalDeliveryException(String message) { super(message); }
    }

    private static String text(Locator parent, String selector) {
        try {
            Locator value = parent.locator(selector).first();
            return value.count() > 0 ? value.innerText() : null;
        } catch (Exception ignored) { return null; }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim()
                : (second == null || second.isBlank() ? null : second.trim());
    }

    private static String normalizeResumeName(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("^附件简历\\s*[:：]\\s*", "").trim();
    }

    private static String firstLine(String value) {
        if (value == null) return null;
        for (String line : value.split("\\R")) {
            if (!line.isBlank()) return line.trim();
        }
        return null;
    }

    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) progressCallback.accept(message, current, total);
    }

    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
