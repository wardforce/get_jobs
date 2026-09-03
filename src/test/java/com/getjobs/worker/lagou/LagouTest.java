package com.getjobs.worker.lagou;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LagouTest {
    @Test
    void parsesBracketedKeywordsAndChineseCommas() {
        assertEquals(List.of("Java", "大模型", "Python"),
                Lagou.parseKeywords("[Java， 大模型, Python]"));
    }

    @Test
    void trimsAndDeduplicatesKeywordsPreservingOrder() {
        assertEquals(List.of("Java", "Python"),
                Lagou.parseKeywords(" Java, Java，Python, Java "));
    }

    @Test
    void buildsPagedNationwideSearchUrlWithoutCityParameter() {
        assertEquals("https://www.lagou.com/wn/jobs?kd=Java&pn=3",
                Lagou.buildSearchUrl("Java", "全国", 3));
    }

    @Test
    void encodesShenzhenWhenBuildingSearchUrl() {
        assertEquals("https://www.lagou.com/wn/jobs?kd=%E5%90%8E%E7%AB%AF&pn=2&city=%E6%B7%B1%E5%9C%B3",
                Lagou.buildSearchUrl("后端", "深圳", 2));
    }

    @Test
    void rejectsConfigWithoutSearchKeywords() {
        LagouConfig config = new LagouConfig();
        config.setKeywords(List.of());

        assertFalse(Lagou.isConfigValid(config));
    }

    @Test
    void rejectsAttachmentModeWithoutLockedResumeName() {
        LagouConfig config = new LagouConfig();
        config.setKeywords(List.of("Java"));
        config.setResumeType("ATTACHMENT");

        assertFalse(Lagou.isConfigValid(config));
    }

    @Test
    void selectsOnlyTheRequestedResumeAttachment() {
        List<String> resumes = List.of("默认简历", "Java简历");

        assertEquals(Optional.of("Java简历"), Lagou.selectResume(resumes, "Java简历"));
        assertEquals(Optional.empty(), Lagou.selectResume(resumes, "不存在的简历"));
        assertEquals(Optional.of("附件简历：Java简历"), Lagou.selectResume(List.of("附件简历：Java简历"), "Java简历"));
    }

    @Test
    void recognizesDisabledPaginationControls() {
        assertFalse(Lagou.isPaginationEnabled("lg-pagination-item-link disabled", null));
        assertFalse(Lagou.isPaginationEnabled("lg-pagination-item-link", "true"));
        assertTrue(Lagou.isPaginationEnabled("lg-pagination-item-link", "false"));
    }
}
