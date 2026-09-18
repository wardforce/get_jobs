package com.getjobs.application.init;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库启动阶段迁移补丁。
 * 在 DataSource 创建后、Spring Boot 执行 data.sql 及业务 Mapper 之前执行，
 * 保证既有 SQLite 数据库平滑补全字段，避免因旧表结构导致启动失败。
 */
@Slf4j
@Component
public class DatabaseSchemaInitializer implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            migrateLagouConfig(dataSource);
        }
        return bean;
    }

    private void migrateLagouConfig(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            boolean tableExists = false;
            boolean hasMaxCount = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info('lagou_config')")) {
                while (rs.next()) {
                    tableExists = true;
                    if ("max_count".equalsIgnoreCase(rs.getString("name"))) {
                        hasMaxCount = true;
                        break;
                    }
                }
            }
            if (tableExists && !hasMaxCount) {
                stmt.execute("ALTER TABLE lagou_config ADD COLUMN max_count INTEGER DEFAULT 30");
                stmt.execute("UPDATE lagou_config SET max_count = 30 WHERE max_count IS NULL OR max_count <= 0");
                log.info("已成功为既有 lagou_config 表补充 max_count 字段并赋默认值");
            }
        } catch (Exception e) {
            log.warn("DatabaseSchemaInitializer 检查或升级 lagou_config 表结构异常: {}", e.getMessage());
        }
    }
}
