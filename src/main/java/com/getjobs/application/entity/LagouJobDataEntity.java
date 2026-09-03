package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lagou_data")
public class LagouJobDataEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("job_id") private String jobId;
    @TableField("job_title") private String jobTitle;
    @TableField("job_link") private String jobLink;
    private String salary;
    private String location;
    private String experience;
    private String degree;
    @TableField("company_name") private String companyName;
    private String industry;
    @TableField("company_scale") private String companyScale;
    @TableField("delivery_status") private String deliveryStatus;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;
}
