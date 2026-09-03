package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lagou_config")
public class LagouConfigEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String keywords;
    private String city;
    private String resumeType;
    private String resumeName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
