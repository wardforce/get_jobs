package com.getjobs.worker.lagou;

import com.getjobs.application.entity.LagouJobDataEntity;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** DOM adaptation only. The owning worker performs all browser access on its delivery thread. */
final class LagouPage {
    private static final String ENTRIES = "#openWinPostion, a[href*='/jobs/'], a[href*='/wn/jobs/'], "
            + "[data-job-id], [data-jobid], [data-position-id], [data-positionid], .job-name, .position-name";
    private static final String TITLE = "#openWinPostion, a[href*='/jobs/'], a[href*='/wn/jobs/'], .job-name, .position-name, h3";
    static final String ACTIONS = ".resume-deliver, button, a, [role='button']";
    private static final String RESUME_OPTIONS = "input[type='radio'], [role='radio'], .resume-group li.resume > .select-radio";

    record Candidate(String key, Locator title, LagouJobDataEntity job) { }
    record Collection(int rawCount, List<Candidate> candidates) { }

    static Collection collect(Page page) {
        Locator entries = page.locator(ENTRIES);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int raw = 0;
        for (int i = 0; i < entries.count(); i++) {
            Locator entry = entries.nth(i);
            if (!entry.isVisible()) continue;
            raw++;
            Locator title = entry;
            String href = entry.getAttribute("href");
            if (href != null && !href.isBlank() && !href.equals("#") && !href.startsWith("javascript:")) {
                if (jobId(href) == null) continue; // Company/search URLs are never job identifiers.
            } else if (!"openWinPostion".equals(entry.getAttribute("id"))) {
                Locator child = visible(entry.locator(TITLE));
                if (child != null) title = child;
                else if (entry.getAttribute("onclick") == null
                        && !"link".equals(entry.getAttribute("role"))
                        && !String.valueOf(entry.getAttribute("class")).matches(".*(job-name|position-name).*")) continue;
            }
            String link = detailUrl(title.getAttribute("href"));
            Locator root = title.locator("xpath=ancestor-or-self::*[contains(@class,'job-card') or contains(@class,'job-item') "
                    + "or contains(@class,'item__') or @data-job-id or @data-jobid or @data-position-id or @data-positionid][1]");
            if (root.count() == 0) root = title;
            String id = firstNonBlank(attributeId(title), attributeId(root));
            if (id == null) {
                Locator childId = root.locator("[data-job-id], [data-jobid], [data-position-id], [data-positionid]");
                if (childId.count() > 0) id = attributeId(childId.first());
            }
            String linkId = jobId(link);
            if (id != null && linkId != null && !id.equals(linkId)) {
                throw new IllegalStateException("岗位卡片 ID 与详情链接不一致");
            }
            id = firstNonBlank(id, linkId);
            String name = title.innerText().trim();
            if (name.isBlank()) continue;
            LagouJobDataEntity job = new LagouJobDataEntity();
            job.setJobId(id);
            job.setJobLink(link);
            job.setJobTitle(name);
            job.setCompanyName(text(root, ".company-name, [class*='company-name'], a[href*='/gongsi/']"));
            job.setSalary(text(root, ".salary, [class*='salary']"));
            job.setLocation(text(root, ".job-area, .location, [class*='location'], [class*='area']"));
            job.setExperience(text(root, ".experience, [class*='experience']"));
            job.setDegree(text(root, ".education, .degree, [class*='education']"));
            job.setIndustry(text(root, ".industry, [class*='industry']"));
            job.setCompanyScale(text(root, ".company-size, .company-scale"));
            // A DOM path distinguishes duplicate anonymous titles without inventing a persistent job ID.
            String path = (String) title.evaluate("el => { const p=[]; while(el && el.parentElement) { "
                    + "p.unshift(el.tagName+':'+Array.from(el.parentElement.children).indexOf(el)); el=el.parentElement; } return p.join('/'); }");
            String key = id != null ? id : name + "|" + job.getCompanyName() + "|" + path;
            candidates.putIfAbsent(key, new Candidate(key, title, job));
        }
        return new Collection(raw, new ArrayList<>(candidates.values()));
    }

    static String detailUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create("https://www.lagou.com").resolve(raw);
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getUserInfo() != null
                    || !("www.lagou.com".equalsIgnoreCase(uri.getHost()) || "lagou.com".equalsIgnoreCase(uri.getHost()))) return null;
            if (!uri.getPath().matches("/(?:wn/)?jobs/\\d+(?:\\.html)?/?")) return null;
            // Tracking/query parameters are unnecessary for identity or persistence.
            return "https://www.lagou.com" + uri.getPath();
        } catch (IllegalArgumentException e) { return null; }
    }

    static String jobId(String raw) {
        String url = detailUrl(raw);
        if (url == null) return null;
        var matcher = Pattern.compile("/jobs/(\\d+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String attributeId(Locator locator) {
        for (String attribute : List.of("data-job-id", "data-jobid", "data-position-id", "data-positionid")) {
            String id = locator.getAttribute(attribute);
            if (id != null && id.matches("\\d+")) return id;
        }
        return null;
    }

    static Locator action(Page page, String label) {
        Locator actions = page.locator(ACTIONS);
        for (int i = 0; i < actions.count(); i++) {
            Locator action = actions.nth(i);
            if (action.isVisible() && label.equals(action.innerText().trim())) return action;
        }
        return null;
    }

    static Locator apply(Page page) {
        Locator apply = action(page, "投简历");
        return apply == null ? action(page, "立即投递") : apply;
    }

    static Locator optionalDeliveryConfirm(Page page) {
        Locator dialogs = page.locator("[role='dialog'], .modal, .dialog, [class*='modal'], [class*='dialog']");
        for (int dialogIndex = 0; dialogIndex < dialogs.count(); dialogIndex++) {
            Locator dialog = dialogs.nth(dialogIndex);
            if (!dialog.isVisible()) continue;
            boolean syncPrompt = dialog.innerText().contains("该职位来自于前程无忧")
                    && dialog.innerText().contains("投递后简历同步至前程无忧平台");
            Locator buttons = dialog.locator("button, a, [role='button']");
            for (int buttonIndex = 0; buttonIndex < buttons.count(); buttonIndex++) {
                Locator button = buttons.nth(buttonIndex);
                if (!button.isVisible()) continue;
                String label = button.innerText().trim();
                if ("继续投递".equals(label) || syncPrompt && "确认投递".equals(label)) return button;
            }
        }
        return null;
    }

    static boolean delivered(Page page) { return action(page, "已投递") != null; }

    static boolean success(Page page) {
        return delivered(page) || visible(page.getByText("投递成功", new Page.GetByTextOptions().setExact(true))) != null
                || visible(page.getByText(Pattern.compile("^简历已成功投出去了[，,！!。\\s].*"))) != null;
    }

    static boolean failed(Page page) {
        return visible(page.getByText(Pattern.compile("^(投递失败|简历投递失败|投递未成功)([，,：:！!。\\s].*)?$"))) != null;
    }

    static boolean empty(Page page) {
        return visible(page.getByText(Pattern.compile(".*(暂无相关职位|没有找到相关职位|暂无职位|没有符合条件的职位).*"))) != null;
    }

    static void checkCritical(Page page) {
        String url = page.url();
        String body = page.locator("body").innerText();
        if (body.contains("今日投递上限") || body.contains("达到投递上限"))
            throw new IllegalStateException("拉勾提示今日投递上限，任务停止");
        if (body.contains("访问验证") || url.contains("/verify"))
            throw new IllegalStateException("拉勾需要访问验证，任务停止，请在浏览器处理");
        if (url.contains("passport.lagou") || visible(page.locator("input[type='password']")) != null
                || visible(page.getByText("登录后投递", new Page.GetByTextOptions().setExact(true))) != null)
            throw new IllegalStateException("拉勾登录已失效，任务停止");
    }

    /** Select only the exact configured radio option; resume choices may live directly in the job sidebar. */
    static void selectResume(Page page, LagouConfig config) {
        String requested = "ATTACHMENT".equalsIgnoreCase(config.getResumeType()) ? config.getResumeName() : "在线简历";
        Locator all = page.locator(RESUME_OPTIONS);
        List<Locator> matches = new ArrayList<>();
        for (int i = 0; i < all.count(); i++) {
            Locator radio = all.nth(i);
            String name = resumeOptionName(radio);
            if (!normalizeResumeName(name).equals(normalizeResumeName(requested))) continue;
            if (resumeOptionVisible(radio)) matches.add(radio);
        }
        if (matches.size() != 1) throw new IllegalStateException("指定简历缺失或重名，停止投递: " + requested);
        Locator radio = matches.getFirst();
        if (!selected(radio)) {
            if (radio.isVisible()) radio.click();
            else {
                Locator label = radio.locator("xpath=ancestor::label[1]");
                if (label.count() == 0) throw new IllegalStateException("指定简历选项不可点击: " + requested);
                label.click();
            }
        }
        page.waitForCondition(() -> selected(radio), new Page.WaitForConditionOptions().setTimeout(3000));
    }

    private static boolean selected(Locator radio) {
        return Boolean.TRUE.equals(radio.evaluate("el => el.checked === true || el.getAttribute('aria-checked') === 'true' "
                + "|| (el.matches('.resume-group li.resume > .select-radio') && el.classList.contains('selected'))"));
    }

    static boolean hasResumeOptions(Page page, LagouConfig config) {
        String requested = "ATTACHMENT".equalsIgnoreCase(config.getResumeType()) ? config.getResumeName() : "在线简历";
        Locator radios = page.locator(RESUME_OPTIONS);
        for (int i = 0; i < radios.count(); i++) {
            Locator radio = radios.nth(i);
            if (!resumeOptionVisible(radio)) continue;
            String name = normalizeResumeName(resumeOptionName(radio));
            if ("在线简历".equals(name) || normalizeResumeName(requested).equals(name)) return true;
        }
        return false;
    }

    private static String resumeOptionName(Locator radio) {
        return (String) radio.evaluate("""
                el => {
                  if (el.matches('.resume-group li.resume > .select-radio')) {
                    const row = el.closest('li.resume');
                    if (row.classList.contains('online-resume')) {
                      const label = row.querySelector('a')?.textContent?.trim();
                      return label === '在线简历' ? label : '';
                    }
                    const title = row.querySelector('.select-content a[title]')?.getAttribute('title');
                    return title?.startsWith('下载') ? title.slice(2) : '';
                  }
                  return el.getAttribute('aria-label') ||
                """
                + "Array.from(el.labels || []).map(l => l.innerText).join(' ') || "
                + "el.closest('[role=radio], label, .resume-item')?.innerText || "
                + "el.parentElement?.innerText || ''; }");
    }

    private static boolean resumeOptionVisible(Locator radio) {
        return Boolean.TRUE.equals(radio.evaluate("el => { const n=el.closest('label, [role=radio], .resume-item') || el; "
                + "return n.getClientRects().length > 0 && getComputedStyle(n).visibility !== 'hidden'; }"));
    }

    static boolean nextEnabled(Page page) {
        Locator next = visible(page.locator("li[title='下一页'], [aria-label='下一页'], .lg-pagination-next, "
                + "[class*='pagination-next'], button:has-text('下一页'), a:has-text('下一页')"));
        return next != null && !next.isDisabled() && Lagou.isPaginationEnabled(next.getAttribute("class"), next.getAttribute("aria-disabled"))
                && !Boolean.TRUE.equals(next.evaluate("el => !!el.closest('[aria-disabled=true], .disabled, .lg-pagination-disabled')"));
    }

    static Locator visible(Locator locator) {
        for (int i = 0; i < locator.count(); i++) if (locator.nth(i).isVisible()) return locator.nth(i);
        return null;
    }

    static String text(Locator root, String selector) {
        Locator node = visible(root.locator(selector));
        return node == null ? null : node.innerText().trim();
    }

    static String normalizeResumeName(String value) {
        return value == null ? "" : value.trim().replaceFirst("^附件简历\\s*[:：]\\s*", "").trim();
    }

    static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null || second.isBlank() ? null : second.trim();
    }

    /** Structural evidence only: no cookies, input values, resume names or arbitrary page text. */
    static String diagnostic(Page page) {
        return (String) page.locator(ENTRIES + ", " + ACTIONS + ", " + RESUME_OPTIONS).evaluateAll("""
                nodes => JSON.stringify({
                  path: location.origin + location.pathname,
                  nodes: nodes.slice(0, 60).map(el => ({
                    tag: el.tagName,
                    id: el.id,
                    class: el.className,
                    role: el.getAttribute('role'),
                    jobId: el.getAttribute('data-job-id') || el.getAttribute('data-position-id'),
                    href: (() => { try { const u = new URL(el.getAttribute('href'), location.href);
                      return el.hasAttribute('href') ? u.origin + u.pathname : null; } catch { return null; } })(),
                    visible: el.getClientRects().length > 0
                  }))
                }, null, 2)
                """);
    }
}
