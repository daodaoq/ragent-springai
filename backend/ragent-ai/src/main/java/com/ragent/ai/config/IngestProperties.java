package com.ragent.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步摄取队列配置（application.yml 中 ragent.ingest.*）。
 */
@Data
@ConfigurationProperties(prefix = "ragent.ingest")
public class IngestProperties {

    /** 总开关（false 时队列不消费，任务滞留 QUEUED——用于排查/维护） */
    private boolean enabled = true;

    /** 轮询间隔 ms（默认 1s） */
    private long pollIntervalMs = 1000;

    /** 每次轮询最多认领任务数 */
    private int batchSize = 10;

    /** 单任务最大尝试次数（超限进 DLQ） */
    private int maxAttempts = 3;

    /** 终态任务保留天数（每日 03:30 清理 SUCCESS/DLQ/CANCELLED） */
    private int retentionDays = 7;
}
