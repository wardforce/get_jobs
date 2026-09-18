package com.getjobs.worker.lagou;

import com.getjobs.application.entity.LagouJobDataEntity;
import com.getjobs.application.service.LagouService;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Offline browser fixtures reconstructed from the selectors in the worker, not a live-site capture. */
class LagouBrowserTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    Lagou worker;
    LagouConfig config;
    Map<String, String> statuses;
    List<String> messages;
    List<String> visits;
    String search;
    String searchAfterFirstDetail;
    String detail;
    String previousLimit;

    @BeforeAll static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll static void shutdown() { browser.close(); playwright.close(); }

    @BeforeEach void setup() {
        previousLimit = System.getProperty("getjobs.delivery.max");
        System.setProperty("getjobs.delivery.max", "5");
        context = browser.newContext();
        page = context.newPage();
        statuses = new LinkedHashMap<>();
        messages = new ArrayList<>();
        visits = new ArrayList<>();
        LagouService service = mock(LagouService.class);
        doAnswer(call -> {
            LagouJobDataEntity job = call.getArgument(0);
            statuses.put(job.getJobId(), job.getDeliveryStatus() == null
                    ? statuses.getOrDefault(job.getJobId(), "未投递") : job.getDeliveryStatus());
            return null;
        }).when(service).saveOrUpdateJob(any());
        when(service.getJobDeliveryStatus(any())).thenAnswer(call -> statuses.get(call.getArgument(0)));
        worker = new Lagou(service);
        config = new LagouConfig();
        config.setKeywords(List.of("Java"));
        config.setResumeType("ONLINE");
        config.setMaxCount(1);
        worker.setConfig(config);
        worker.setPage(page);
        worker.setProgressCallback((message, current, total) -> messages.add(message));
        worker.prepare();
        search = "<div class='job-card'><a id='openWinPostion' onclick=\"window.open('/jobs/123.html')\">Java developer</a></div>";
        searchAfterFirstDetail = null;
        detail = "<h1>Java developer</h1><div class='resume-selection'><label><input type='radio' name='resume'>在线简历</label></div>"
                + "<button class='resume-deliver' onclick=\"this.textContent='已投递'\">投简历</button>";
        context.route("**/*", route -> {
            String url = route.request().url();
            visits.add(url);
            if (searchAfterFirstDetail != null && url.endsWith("/jobs/123.html")) {
                search = searchAfterFirstDetail;
            }
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                    .setBody(url.contains("/wn/jobs?") ? search : detail));
        });
    }

    @AfterEach void cleanup() {
        context.close();
        if (previousLimit == null) System.clearProperty("getjobs.delivery.max");
        else System.setProperty("getjobs.delivery.max", previousLimit);
    }

    @Test void opensTitleWithoutHrefBeforeResolvingJobId() {
        worker.execute();
        assertEquals("已投递", statuses.get("123"));
        assertEquals(1, visits.stream().filter(url -> url.endsWith("/jobs/123.html")).count());
    }

    @Test void recognizesExactChineseApplyLabel() {
        page.setContent(detail);
        assertNotNull(LagouPage.apply(page), () -> page.locator("button").allTextContents().toString());
    }

    @Test void handlesSameTabNavigationAndReacquiresRemainingCards() {
        config.setMaxCount(2);
        search = "<a href='/jobs/123.html'>Java one</a><a href='/jobs/456.html'>Java two</a>";
        worker.execute();
        assertEquals(Map.of("123", "已投递", "456", "已投递"), statuses);
        assertTrue(page.url().contains("/wn/jobs?"));
    }

    @Test void continuesWhenSameTabReturnRemovesEarlierNoIdCard() {
        config.setMaxCount(2);
        search = "<a id='openWinPostion' onclick=\"location.href='/jobs/123.html'\">Java one</a>"
                + "<a id='openWinPostion' onclick=\"location.href='/jobs/456.html'\">Java two</a>";
        searchAfterFirstDetail = "<a id='openWinPostion' onclick=\"location.href='/jobs/456.html'\">Java two</a>";

        Lagou.Result result = worker.execute();

        assertEquals(Lagou.EndState.COMPLETED, result.state());
        assertEquals(2, result.delivered());
        assertEquals(Map.of("123", "已投递", "456", "已投递"), statuses);
    }

    @Test void doesNotSelectPartialAttachmentNameOrSubmit() {
        config.setResumeType("ATTACHMENT");
        config.setResumeName("resume.pdf");
        search = "<a target='_blank' href='/jobs/123.html'>Java developer</a>";
        detail = "<h1>Java developer</h1><div class='resume-selection'><label><input type='radio'>附件简历：resume.pdf.old</label></div>"
                + "<button class='resume-deliver' onclick=\"this.textContent='已投递'\">投简历</button>";
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertEquals(0, result.attempts());
        assertNotEquals("已投递", statuses.get("123"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("简历")));
    }

    @Test void genericAcknowledgementDoesNotProveDelivery() {
        search = "<a target='_blank' href='/jobs/123.html'>Java developer</a>";
        detail = "<h1>Java developer</h1><div class='resume-selection'><label><input type='radio'>在线简历</label></div>"
                + "<button class='resume-deliver' onclick=\"document.querySelector('#notice').hidden=false\">投简历</button>"
                + "<div id='notice' hidden>操作提示<button>我知道了</button></div>";
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.PENDING, result.state());
        assertEquals(1, result.uncertain());
        assertEquals("待确认", statuses.get("123"));
        assertEquals(2, context.pages().size(), "Keep uncertain detail available for inspection");
        assertTrue(messages.stream().anyMatch(message -> message.contains("待确认")));
    }

    @Test void ignoresCompanyLinkAndUsesNestedPositionId() {
        search = "<div class='job-card'><a href='/gongsi/789.html'>Company</a>"
                + "<span data-position-id='123'><a id='openWinPostion' onclick=\"window.open('/jobs/123.html')\">Java developer</a></span></div>";
        assertEquals(1, worker.execute().delivered());
        assertTrue(visits.stream().noneMatch(url -> url.contains("gongsi")));
        assertEquals(Set.of("123"), statuses.keySet());
    }

    @Test void rejectsExternalAndSearchLinksAsJobIds() {
        assertNull(LagouPage.jobId("https://other.example/jobs/123.html"));
        assertNull(LagouPage.jobId("https://www.lagou.com/wn/jobs?kd=Java"));
        assertNull(LagouPage.jobId("https://www.lagou.com/gongsi/123.html"));
        assertEquals("123", LagouPage.jobId("/wn/jobs/123.html?tracking=x"));
    }

    @Test void zeroAttemptBudgetDoesNotNavigate() {
        System.setProperty("getjobs.delivery.max", "0");
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.LIMITED, result.state());
        assertEquals(0, result.attempts());
        assertTrue(visits.isEmpty());
    }

    @Test void explicitFailureConsumesAttemptBudget() {
        System.setProperty("getjobs.delivery.max", "1");
        config.setMaxCount(5);
        search = "<a target='_blank' href='/jobs/123.html'>Java one</a><a target='_blank' href='/jobs/456.html'>Java two</a>";
        detail = detail.replace("this.textContent='已投递'", "this.textContent='投递失败'");
        Lagou.Result result = worker.execute();
        assertEquals(1, result.attempts());
        assertEquals(1, result.failed());
        assertEquals(0, result.delivered());
        assertEquals("投递失败", statuses.get("123"));
        assertFalse(statuses.containsKey("456"));
    }

    @Test void stopsBeforeClickWhenUserStopsAtSubmissionStage() {
        boolean[] stopped = { false };
        worker.setShouldStopCallback(() -> stopped[0]);
        worker.setProgressCallback((message, current, total) -> {
            if (message.startsWith("提交并等待结果")) stopped[0] = true;
        });
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.STOPPED, result.state());
        assertEquals(0, result.attempts());
        assertEquals(0, result.uncertain());
        assertEquals("未投递", statuses.get("123"));
    }

    @Test void exactAttachmentIsSelectedAndDuplicateNamesStop() {
        config.setResumeType("ATTACHMENT");
        config.setResumeName("resume.pdf");
        detail = "<h1>Java developer</h1><div class='resume-selection'>"
                + "<label><input type='radio' name='resume' checked>附件简历：resume.pdf.old</label>"
                + "<label><input id='right' type='radio' name='resume'>附件简历：resume.pdf</label></div>"
                + "<button class='resume-deliver' onclick=\"this.textContent=document.querySelector('#right').checked ? '已投递' : '投递失败'\">投简历</button>";
        assertEquals(1, worker.execute().delivered());
        statuses.clear(); worker.prepare();
        detail = detail.replace("resume.pdf.old", "resume.pdf");
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertEquals(0, result.attempts());
    }

    @Test void doesNotClickSubmitToDiscoverResumeOptions() {
        detail = "<h1>Java developer</h1><button class='resume-deliver' aria-haspopup='dialog' "
                + "onclick=\"document.querySelector('#selection').innerHTML='<label><input type=radio>在线简历</label><button onclick=confirmDelivery()>确认投递</button>'\">投简历</button>"
                + "<div role='dialog' id='selection'></div>"
                + "<script>function confirmDelivery(){document.querySelector('.resume-deliver').textContent='已投递';}</script>";
        Lagou.Result result = worker.execute();
        assertEquals(0, result.attempts());
        assertEquals(0, result.delivered());
        assertEquals(Lagou.EndState.ERROR, result.state());
    }

    @Test void selectsConfiguredResumeDirectlyFromJobSidebar() {
        config.setResumeType("ATTACHMENT");
        config.setResumeName("吴振华简历_已更新.pdf");
        detail = "<h1>Java developer</h1>"
                + "<aside class='job-detail-aside'>"
                + "<label><input id='online' type='radio' name='resume'>在线简历</label>"
                + "<label><input id='configured' type='radio' name='resume'>吴振华简历_已更新.pdf</label>"
                + "<label><input id='other' type='radio' name='resume'>吴振华简历_2026.pdf</label>"
                + "<button>上传附件简历</button>"
                + "</aside>"
                + "<button class='resume-deliver' onclick=\"this.textContent=document.querySelector('#configured').checked ? '已投递' : '投递失败'\">投简历</button>";

        Lagou.Result result = worker.execute();

        assertEquals(1, result.delivered());
        assertEquals(1, result.attempts());
        assertEquals("已投递", statuses.get("123"));
    }

    // Reconstructed from the public Lagou job-detail component: spans, not native radio inputs.
    private String customResumeSidebar() {
        return "<h1>Java developer</h1><style>.select-radio{display:inline-block;width:18px;height:18px;border:1px solid green}</style>"
                + "<div class='resume-group'><li class='resume online-resume'>"
                + "<span id='online' class='select-radio' onclick='choose(this)'></span>"
                + "<span><a href='/resume/myresume.html'>在线简历</a></span></li>"
                + "<li class='resume resume-attachment'><span id='attachment' class='select-radio selected' onclick='choose(this)'></span>"
                + "<span class='select-content'><a href='/nearBy/downloadResume?id=2' title='下载candidate_updated.pdf'>candidate_...</a></span></li>"
                + "<li class='resume resume-attachment'><span id='second' class='select-radio' onclick='choose(this)'></span>"
                + "<span class='select-content'><a href='/nearBy/downloadResume?id=3' title='下载candidate_project.pdf'>candidate_...</a></span></li></div>"
                + "<script>window.choiceClicks=0;function choose(el){window.choiceClicks++;document.querySelectorAll('.select-radio').forEach(x=>x.classList.remove('selected'));el.classList.add('selected');}</script>"
                + "<a class='resume-deliver' onclick=\"this.textContent=document.querySelector('#EXPECTED').classList.contains('selected')?'已投递':'投递失败'\">投简历</a>";
    }

    @Test void selectsCustomOnlineOptionInsteadOfDefaultAttachment() {
        detail = customResumeSidebar().replace("EXPECTED", "online");
        Lagou.Result result = worker.execute();
        assertEquals(1, result.delivered(), result.summary());
        assertEquals(1, result.attempts());
        assertTrue(visits.stream().noneMatch(url -> url.contains("myresume") || url.contains("downloadResume")));
    }

    @Test void selectsCustomAttachmentUsingFullTitleInsteadOfTruncatedText() {
        config.setResumeType("ATTACHMENT");
        config.setResumeName("candidate_project.pdf");
        detail = customResumeSidebar().replace("EXPECTED", "second");
        Lagou.Result result = worker.execute();
        assertEquals(1, result.delivered(), result.summary());
        assertEquals(1, result.attempts());
        assertTrue(visits.stream().noneMatch(url -> url.contains("downloadResume")));
    }

    @Test void recognizesAlreadySelectedCustomAttachmentWithoutClickingAgain() {
        config.setResumeType("ATTACHMENT");
        config.setResumeName("candidate_updated.pdf");
        detail = customResumeSidebar().replace("EXPECTED", "attachment")
                .replace("this.textContent=document", "if(window.choiceClicks)throw Error('unexpected reselection');this.textContent=document");
        assertEquals(1, worker.execute().delivered());
    }

    @Test void confirmsExternalPlatformSyncDialogAfterInitialSubmission() {
        detail = "<h1>Java developer</h1>"
                + "<div class='resume-selection'><label><input type='radio' name='resume'>在线简历</label></div>"
                + "<button class='resume-deliver' onclick=\"document.querySelector('#sync-dialog').hidden=false\">投简历</button>"
                + "<div id='sync-dialog' role='dialog' hidden>"
                + "<p>该职位来自于前程无忧，投递后简历同步至前程无忧平台</p>"
                + "<button>我再想想</button>"
                + "<button onclick=\"document.querySelector('.resume-deliver').textContent='已投递'; this.closest('[role=dialog]').hidden=true\">确认投递</button>"
                + "</div>";

        Lagou.Result result = worker.execute();

        assertEquals(Lagou.EndState.COMPLETED, result.state());
        assertEquals(1, result.attempts());
        assertEquals(1, result.delivered());
        assertEquals(0, result.uncertain());
        assertEquals("已投递", statuses.get("123"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("确认同步投递")));
    }

    @Test void continuesDeliveryOnlyWhenOptionalDialogAppearsAfterClick() {
        detail = detail.replace("this.textContent='已投递'", "setTimeout(()=>document.querySelector('#extra').hidden=false,200)")
                + "<div id='extra' role='dialog' hidden><p>请确认是否继续本次投递</p>"
                + "<button onclick=\"document.querySelector('.resume-deliver').textContent='已投递';this.parentElement.hidden=true\">继续投递</button></div>";
        Lagou.Result result = worker.execute();
        assertEquals(1, result.delivered(), result.summary());
        assertEquals(1, result.attempts());
        assertEquals(0, result.uncertain());
    }

    @Test void existingDeliveryAndUncertainHistoryAreNotResubmitted() {
        search = "<a target='_blank' href='/jobs/123.html'>Java developer</a>";
        statuses.put("123", "已投递");
        Lagou.Result result = worker.execute();
        assertEquals(1, result.skipped());
        assertEquals(0, result.attempts());
        statuses.put("123", "待确认"); worker.prepare();
        result = worker.execute();
        assertEquals(Lagou.EndState.PENDING, result.state());
        assertEquals(0, result.attempts());
        assertTrue(visits.stream().noneMatch(url -> url.endsWith("/123.html")));
    }

    @Test void repeatedPageStopsInsteadOfLooping() {
        config.setMaxCount(5);
        search += "<button aria-label='下一页'>下一页</button>";
        Lagou.Result result = worker.execute();
        assertEquals(1, result.delivered());
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertTrue(result.reason().contains("重复"));
        assertEquals(2, visits.stream().filter(url -> url.contains("/wn/jobs?")).count());
    }

    @Test void unrelatedPlatformTabSurvivesThreeNoOpClicks() {
        Page unrelated = context.newPage();
        unrelated.navigate("https://www.liepin.com/");
        search = "<a href='/jobs/1.html' onclick='return false'>One</a>"
                + "<a href='/jobs/2.html' onclick='return false'>Two</a>"
                + "<a href='/jobs/3.html' onclick='return false'>Three</a>"
                + "<a href='/jobs/4.html' onclick='return false'>Four</a><button aria-label='下一页'>下一页</button>";
        Lagou.Result result = worker.execute();
        assertFalse(unrelated.isClosed());
        assertEquals("https://www.liepin.com/", unrelated.url());
        assertEquals(3, result.failed());
        assertEquals(0, result.attempts());
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertEquals(2, context.pages().size());
    }

    @Test void waitsForDelayedListAndRecognizesExplicitEmptyResult() {
        String cards = search;
        search = "<div id='list'></div><script>setTimeout(()=>document.querySelector('#list').innerHTML="
                + new com.google.gson.Gson().toJson(cards) + ", 300)</script>";
        assertEquals(1, worker.execute().delivered());
        worker.prepare(); search = "<p>暂无相关职位</p>";
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.LIMITED, result.state());
        assertEquals(0, result.delivered());
        assertTrue(result.reason().contains("0/1"));
    }

    @Test void reportsLimitedWhenAvailableJobsDoNotReachTarget() {
        config.setMaxCount(2);

        Lagou.Result result = worker.execute();

        assertEquals(Lagou.EndState.LIMITED, result.state());
        assertEquals(1, result.delivered());
        assertTrue(result.reason().contains("1/2"));
    }

    @Test void rejectsVisibleUnparseableCardsWithoutPaging() {
        search = "<div data-job-id='123'>Broken card without title</div><button aria-label='下一页'>下一页</button>";
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertTrue(result.reason().contains("有效岗位 0"));
        assertEquals(1, visits.size());
    }

    @Test void completesEachPageBeforeOpeningNextPage() {
        config.setMaxCount(2);
        search += "<button aria-label='下一页'>下一页</button>";
        context.route("**/wn/jobs?**pn=2", route -> {
            assertEquals("已投递", statuses.get("123"), "First page must finish before pagination");
            visits.add(route.request().url());
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                    .setBody("<a target='_blank' href='/jobs/456.html'>Java developer two</a>"));
        });
        Lagou.Result result = worker.execute();
        assertEquals(2, result.delivered());
        assertEquals(2, result.attempts());
        assertEquals(Lagou.EndState.COMPLETED, result.state());
        assertEquals(1, context.pages().size());
    }

    @Test void rejectsMismatchedDetailIdentity() {
        search = "<div class='job-card' data-job-id='123'><a id='openWinPostion' onclick=\"window.open('/jobs/999.html')\">Java developer</a></div>";
        Lagou.Result result = worker.execute();
        assertEquals(0, result.attempts());
        assertEquals(1, result.failed());
        assertFalse(statuses.containsKey("999"));
        assertEquals(1, context.pages().size());
    }

    @Test void resumesWithDifferentTitlesDoNotTriggerDefaultSubmission() {
        detail = "<h1>Java developer</h1><button class='resume-deliver' onclick=\"this.textContent='已投递'\">投简历</button>";
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertEquals(0, result.attempts());
        assertNotEquals("已投递", statuses.get("123"));
    }

    @Test void accessVerificationStopsBeforeOpeningJobs() {
        search = "<h1>访问验证</h1>" + search;
        Lagou.Result result = worker.execute();
        assertEquals(Lagou.EndState.ERROR, result.state());
        assertEquals(0, result.attempts());
        assertEquals(1, visits.size());
    }
}
