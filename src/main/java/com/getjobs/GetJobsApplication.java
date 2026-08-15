package com.getjobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GetJobs应用程序启动类
 * 自动化求职平台的主入口
 *
 * @author GetJobs
 * @version 0.0.1-SNAPSHOT
 */
@SpringBootApplication(scanBasePackages = "com.getjobs")
@EnableScheduling
@EnableAsync
public class GetJobsApplication {
    public static void main(String[] args) {
        try {
            Files.createDirectories(Path.of("db"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据库目录 db", e);
        }
        SpringApplication.run(GetJobsApplication.class, args);
    }
}
