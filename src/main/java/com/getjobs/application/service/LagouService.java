package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.LagouConfigEntity;
import com.getjobs.application.entity.LagouJobDataEntity;
import com.getjobs.application.entity.LagouOptionEntity;
import com.getjobs.application.mapper.LagouConfigMapper;
import com.getjobs.application.mapper.LagouJobDataMapper;
import com.getjobs.application.mapper.LagouOptionMapper;
import com.getjobs.worker.lagou.Lagou;
import com.getjobs.worker.lagou.LagouConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LagouService {
    private final LagouConfigMapper lagouConfigMapper;
    private final LagouOptionMapper lagouOptionMapper;
    private final LagouJobDataMapper lagouJobDataMapper;
    private final DataSource dataSource;

    @PostConstruct
    public void ensureTableAndColumns() {
        // ponytail: 自动为既有 SQLite 表补齐 max_count 列，无需复杂的外部迁移工具
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            boolean hasMaxCount = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info('lagou_config')")) {
                while (rs.next()) {
                    if ("max_count".equalsIgnoreCase(rs.getString("name"))) {
                        hasMaxCount = true;
                        break;
                    }
                }
            }
            if (!hasMaxCount) {
                stmt.execute("ALTER TABLE lagou_config ADD COLUMN max_count INTEGER DEFAULT 30");
                log.info("已为 lagou_config 表补充 max_count 字段");
            }
        } catch (Exception e) {
            log.warn("检查或升级 lagou_config 表结构失败: {}", e.getMessage());
        }
    }

    public LagouConfigEntity getFirstConfig() {
        return lagouConfigMapper.selectOne(new QueryWrapper<LagouConfigEntity>().last("LIMIT 1"));
    }

    public LagouConfig loadLagouConfig() {
        LagouConfigEntity entity = getFirstConfig();
        LagouConfig config = new LagouConfig();
        if (entity == null) {
            config.setKeywords(List.of());
            config.setCity("全国");
            config.setMaxCount(30);
            return config;
        }
        config.setKeywords(Lagou.parseKeywords(entity.getKeywords()));
        config.setCity(entity.getCity() == null || entity.getCity().isBlank() ? "全国" : entity.getCity().trim());
        config.setResumeType(entity.getResumeType() == null || entity.getResumeType().isBlank()
                ? "ONLINE" : entity.getResumeType().trim().toUpperCase());
        config.setResumeName(entity.getResumeName());
        config.setMaxCount(entity.getMaxCount() != null && entity.getMaxCount() > 0 ? entity.getMaxCount() : 30);
        return config;
    }

    public LagouConfigEntity updateConfig(LagouConfigEntity config) {
        if (config == null) return null;
        if (config.getId() == null) return saveOrUpdateFirstSelective(config);
        config.setUpdatedAt(LocalDateTime.now());
        lagouConfigMapper.updateById(config);
        return lagouConfigMapper.selectById(config.getId());
    }

    public LagouConfigEntity saveOrUpdateFirstSelective(LagouConfigEntity incoming) {
        LagouConfigEntity first = getFirstConfig();
        LocalDateTime now = LocalDateTime.now();
        if (first == null) {
            incoming.setCreatedAt(now);
            incoming.setUpdatedAt(now);
            if (incoming.getMaxCount() == null || incoming.getMaxCount() <= 0) incoming.setMaxCount(30);
            lagouConfigMapper.insert(incoming);
            return getFirstConfig();
        }
        LagouConfigEntity update = new LagouConfigEntity();
        update.setId(first.getId());
        update.setKeywords(incoming.getKeywords() == null ? first.getKeywords() : incoming.getKeywords());
        update.setCity(incoming.getCity() == null ? first.getCity() : incoming.getCity());
        update.setResumeType(incoming.getResumeType() == null ? first.getResumeType() : incoming.getResumeType());
        update.setResumeName(incoming.getResumeName() == null ? first.getResumeName() : incoming.getResumeName());
        update.setMaxCount(incoming.getMaxCount() == null ? (first.getMaxCount() == null ? 30 : first.getMaxCount()) : incoming.getMaxCount());
        update.setCreatedAt(first.getCreatedAt());
        update.setUpdatedAt(now);
        lagouConfigMapper.updateById(update);
        return lagouConfigMapper.selectById(first.getId());
    }

    public List<LagouOptionEntity> getOptionsByType(String type) {
        return lagouOptionMapper.selectList(new QueryWrapper<LagouOptionEntity>()
                .eq("type", type).orderByAsc("sort_order"));
    }

    /** Preserve uncertain submissions across task restarts so they are not sent again automatically. */
    public String getJobDeliveryStatus(String jobId) {
        LagouJobDataEntity existing = lagouJobDataMapper.selectOne(new QueryWrapper<LagouJobDataEntity>()
                .eq("job_id", jobId).last("LIMIT 1"));
        return existing == null ? null : existing.getDeliveryStatus();
    }

    public void saveOrUpdateJob(LagouJobDataEntity job) {
        if (job == null || job.getJobId() == null || job.getJobId().isBlank()) return;
        LagouJobDataEntity existing = lagouJobDataMapper.selectOne(new QueryWrapper<LagouJobDataEntity>()
                .eq("job_id", job.getJobId()).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            job.setCreateTime(now); job.setUpdateTime(now);
            if (job.getDeliveryStatus() == null) job.setDeliveryStatus("未投递");
            lagouJobDataMapper.insert(job);
        } else {
            job.setId(existing.getId()); job.setCreateTime(existing.getCreateTime()); job.setUpdateTime(now);
            if (job.getDeliveryStatus() == null) job.setDeliveryStatus(existing.getDeliveryStatus());
            lagouJobDataMapper.updateById(job);
        }
    }

    public StatsResponse getLagouStats(List<String> statuses, String location, String experience, String degree,
                                       Double minK, Double maxK, String keyword) {
        List<LagouJobDataEntity> jobs = filteredJobs(statuses, location, experience, degree, minK, maxK, keyword);
        StatsResponse result = new StatsResponse();
        result.kpi = new Kpi();
        result.kpi.total = jobs.size();
        result.kpi.delivered = jobs.stream().filter(j -> "已投递".equals(j.getDeliveryStatus())).count();
        result.kpi.pending = jobs.stream().filter(j -> "未投递".equals(j.getDeliveryStatus())).count();
        result.kpi.failed = jobs.stream().filter(j -> "投递失败".equals(j.getDeliveryStatus())).count();
        result.kpi.uncertain = jobs.stream().filter(j -> "待确认".equals(j.getDeliveryStatus())).count();
        result.kpi.filtered = jobs.stream().filter(j -> "已过滤".equals(j.getDeliveryStatus())).count();
        result.charts = new Charts();
        result.charts.byStatus = group(jobs, LagouJobDataEntity::getDeliveryStatus);
        result.charts.byCity = group(jobs, LagouJobDataEntity::getLocation);
        result.charts.byCompany = group(jobs, LagouJobDataEntity::getCompanyName);
        result.charts.byIndustry = group(jobs, LagouJobDataEntity::getIndustry);
        result.charts.byExperience = group(jobs, LagouJobDataEntity::getExperience);
        result.charts.byDegree = group(jobs, LagouJobDataEntity::getDegree);
        result.charts.salaryBuckets = salaryBuckets(jobs);
        result.charts.dailyTrend = dailyTrend(jobs);
        return result;
    }

    public PagedResult listLagouJobs(List<String> statuses, String location, String experience, String degree,
                                     Double minK, Double maxK, String keyword, int page, int size) {
        page = Math.max(1, page); size = Math.max(1, size);
        List<LagouJobDataEntity> jobs = filteredJobs(statuses, location, experience, degree, minK, maxK, keyword);
        int from = Math.min(jobs.size(), (page - 1) * size);
        PagedResult result = new PagedResult();
        result.items = jobs.subList(from, Math.min(jobs.size(), from + size));
        result.total = jobs.size(); result.page = page; result.size = size;
        return result;
    }

    private List<LagouJobDataEntity> filteredJobs(List<String> statuses, String location, String experience,
                                                   String degree, Double minK, Double maxK, String keyword) {
        QueryWrapper<LagouJobDataEntity> query = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) query.in("delivery_status", statuses.stream()
                .filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        if (location != null && !location.isBlank()) query.eq("location", location.trim());
        if (experience != null && !experience.isBlank()) query.eq("experience", experience.trim());
        if (degree != null && !degree.isBlank()) query.eq("degree", degree.trim());
        if (keyword != null && !keyword.isBlank()) query.and(q -> q.like("job_title", keyword.trim())
                .or().like("company_name", keyword.trim()));
        query.orderByDesc("create_time");
        List<LagouJobDataEntity> result = lagouJobDataMapper.selectList(query);
        if (minK == null && maxK == null) return result;
        return result.stream().filter(job -> {
            Double median = parseSalaryMedianK(job.getSalary());
            if (median == null) return false;
            return (minK == null || median >= minK) && (maxK == null || median <= maxK);
        }).toList();
    }

    static Double parseSalaryMedianK(String salary) {
        if (salary == null || salary.isBlank() || salary.contains("面议")) return null;
        String normalized = salary.replaceAll("(?i)\\d+\\s*薪", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(normalized);
        List<Double> values = new ArrayList<>();
        while (matcher.find() && values.size() < 2) values.add(Double.parseDouble(matcher.group(1)));
        if (values.isEmpty()) return null;
        if (salary.contains("元") && values.stream().anyMatch(v -> v > 1000)) {
            values = values.stream().map(v -> v / 1000d).toList();
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static List<NameValue> group(List<LagouJobDataEntity> jobs,
                                         java.util.function.Function<LagouJobDataEntity, String> key) {
        return jobs.stream().collect(Collectors.groupingBy(j -> {
            String value = key.apply(j); return value == null || value.isBlank() ? "未知" : value;
        }, Collectors.counting())).entrySet().stream().map(e -> new NameValue(e.getKey(), e.getValue())).toList();
    }

    private static List<BucketValue> salaryBuckets(List<LagouJobDataEntity> jobs) {
        long zeroToTen = 0, tenToFifteen = 0, fifteenToTwenty = 0, twentyToThirty = 0, thirtyPlus = 0, unknown = 0;
        for (LagouJobDataEntity job : jobs) {
            Double median = parseSalaryMedianK(job.getSalary());
            if (median == null) { unknown++; continue; }
            if (median < 10) zeroToTen++;
            else if (median < 15) tenToFifteen++;
            else if (median < 20) fifteenToTwenty++;
            else if (median < 30) twentyToThirty++;
            else thirtyPlus++;
        }
        return List.of(new BucketValue("0-10K", zeroToTen), new BucketValue("10-15K", tenToFifteen),
                new BucketValue("15-20K", fifteenToTwenty), new BucketValue("20-30K", twentyToThirty),
                new BucketValue(">=30K", thirtyPlus), new BucketValue("面议或未知", unknown));
    }

    private static List<NameValue> dailyTrend(List<LagouJobDataEntity> jobs) {
        return jobs.stream().filter(j -> j.getCreateTime() != null).collect(Collectors.groupingBy(
                j -> j.getCreateTime().toLocalDate().toString(), Collectors.counting())).entrySet().stream()
                .map(e -> new NameValue(e.getKey(), e.getValue())).toList();
    }

    public static class Kpi { public long total; public long delivered; public long pending; public long filtered; public long failed; public long uncertain; }
    public static class NameValue { public String name; public long value; public NameValue(String name, long value) { this.name = name; this.value = value; } }
    public static class BucketValue { public String bucket; public long value; public BucketValue(String bucket, long value) { this.bucket = bucket; this.value = value; } }
    public static class Charts { public List<NameValue> byStatus = new ArrayList<>(); public List<NameValue> byCity = new ArrayList<>(); public List<NameValue> byIndustry = new ArrayList<>(); public List<NameValue> byCompany = new ArrayList<>(); public List<NameValue> byExperience = new ArrayList<>(); public List<NameValue> byDegree = new ArrayList<>(); public List<BucketValue> salaryBuckets = new ArrayList<>(); public List<NameValue> dailyTrend = new ArrayList<>(); }
    public static class StatsResponse { public Kpi kpi; public Charts charts; }
    public static class PagedResult { public List<LagouJobDataEntity> items; public long total; public int page; public int size; }
}
