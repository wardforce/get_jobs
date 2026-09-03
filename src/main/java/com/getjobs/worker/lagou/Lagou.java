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
import java.util.Optional;
import java.util.function.Supplier;

/** 拉勾岗位采集与投递 worker。 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Lagou {
    private static final String SEARCH_URL = "https://www.lagou.com/wn/jobs";

    @Setter private Page page;
    @Setter private LagouConfig config;
    @Setter private ProgressCallback progressCallback;
    @Setter private Supplier<Boolean> shouldStopCallback;
    private final LagouService lagouService;

    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    public void prepare() {
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
            for (int pageNumber = 1; !shouldStop() && delivered < DeliveryLimit.configuredMax(); pageNumber++) {
                page.navigate(buildSearchUrl(keyword, config.getCity(), pageNumber));
                List<Locator> cards = jobCards();
                if (cards.isEmpty()) {
                    sendProgress("当前页没有可处理的拉勾岗位，结束该关键词分页", pageNumber, null);
                    break;
                }
                for (Locator card : cards) {
                    if (shouldStop() || delivered >= DeliveryLimit.configuredMax()) break;
                    persistCard(card);
                    if (submitCard(card)) delivered++;
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
        List<String> keywords = new ArrayList<>();
        for (String item : normalized.split(",")) {
            String value = item.trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isEmpty()) keywords.add(value);
        }
        return keywords;
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

    /** 附件名称被锁定时只能精确命中；绝不回退到首份或在线简历。 */
    public static Optional<String> selectResume(List<String> attachmentNames, String requestedName) {
        if (attachmentNames == null || requestedName == null || requestedName.isBlank()) return Optional.empty();
        return attachmentNames.stream().filter(requestedName::equals).findFirst();
    }

    private List<Locator> jobCards() {
        Locator all = page.locator("[data-job-id], .job-item, .job-card");
        List<Locator> cards = new ArrayList<>();
        for (int index = 0; index < all.count(); index++) {
            Locator card = all.nth(index);
            if (card.isVisible()) cards.add(card);
        }
        return cards;
    }

    private void persistCard(Locator card) {
        try {
            String jobId = card.getAttribute("data-job-id");
            Locator link = card.locator("a[href*='jobs'], a[href*='job']").first();
            String jobLink = link.count() > 0 ? link.getAttribute("href") : null;
            if (jobId == null || jobId.isBlank()) jobId = jobLink;
            if (jobId == null || jobId.isBlank()) return;
            LagouJobDataEntity job = new LagouJobDataEntity();
            job.setJobId(jobId);
            job.setJobLink(jobLink);
            job.setJobTitle(text(card, ".job-name, .position-name, h3"));
            job.setCompanyName(text(card, ".company-name, .company"));
            job.setSalary(text(card, ".salary"));
            job.setLocation(text(card, ".job-area, .location"));
            job.setExperience(text(card, ".experience"));
            job.setDegree(text(card, ".education, .degree"));
            job.setIndustry(text(card, ".industry"));
            job.setCompanyScale(text(card, ".company-size, .company-scale"));
            lagouService.saveOrUpdateJob(job);
        } catch (Exception e) {
            log.debug("采集拉勾岗位卡片失败: {}", e.getMessage());
        }
    }

    private boolean submitCard(Locator card) {
        try {
            card.click();
            Locator apply = page.getByText("立即投递", new Page.GetByTextOptions().setExact(true)).first();
            if (apply.count() == 0 || !apply.isVisible()) return false;
            apply.click();
            if (!selectConfiguredResume()) return false;
            Locator confirm = page.getByText("确认投递", new Page.GetByTextOptions().setExact(true)).first();
            if (confirm.count() > 0 && confirm.isVisible()) confirm.click();
            boolean success = page.getByText("投递成功", new Page.GetByTextOptions().setExact(false)).first().isVisible();
            if (success) markCardDelivered(card);
            return success;
        } catch (Exception e) {
            log.debug("投递拉勾岗位失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean selectConfiguredResume() {
        if (!"ATTACHMENT".equalsIgnoreCase(config.getResumeType())) return true;
        String requested = config.getResumeName();
        Locator names = page.locator(".resume-item, [data-resume-name]");
        List<String> available = new ArrayList<>();
        for (int index = 0; index < names.count(); index++) available.add(names.nth(index).innerText());
        Optional<String> selected = selectResume(available, requested);
        if (selected.isEmpty()) {
            sendProgress("未找到已锁定的附件简历：" + requested, null, null);
            return false;
        }
        page.getByText(selected.get(), new Page.GetByTextOptions().setExact(true)).first().click();
        return true;
    }

    private void markCardDelivered(Locator card) {
        String jobId = card.getAttribute("data-job-id");
        if (jobId == null || jobId.isBlank()) return;
        LagouJobDataEntity job = new LagouJobDataEntity();
        job.setJobId(jobId);
        job.setDeliveryStatus("已投递");
        lagouService.saveOrUpdateJob(job);
    }

    private boolean hasNextPage() {
        try {
            Locator next = page.getByText("下一页", new Page.GetByTextOptions().setExact(true)).first();
            if (next.count() == 0 || !next.isVisible()) return false;
            String classes = next.getAttribute("class");
            return classes == null || !classes.contains("disabled");
        } catch (Exception ignored) { return false; }
    }

    private static String text(Locator parent, String selector) {
        try {
            Locator value = parent.locator(selector).first();
            return value.count() > 0 ? value.innerText() : null;
        } catch (Exception ignored) { return null; }
    }

    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) progressCallback.accept(message, current, total);
    }

    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
