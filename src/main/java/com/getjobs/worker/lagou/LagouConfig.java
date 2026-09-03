package com.getjobs.worker.lagou;

import lombok.Data;

import java.util.List;

@Data
public class LagouConfig {
    private List<String> keywords;
    /** 城市名称；全国或空值表示不传 city 查询参数。 */
    private String city;
    /** ONLINE 或 ATTACHMENT。 */
    private String resumeType;
    /** ATTACHMENT 模式下已锁定的附件简历名称。 */
    private String resumeName;
}
