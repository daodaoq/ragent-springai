package com.ragent.ai.service;

/**
 * 异步摄取任务队列（P9-5a）：轮询 ingest_task 消费，瞬时失败自动重试、永久失败进 DLQ。
 * 轮询/清理由 @Scheduled 驱动；对外主要暴露状态迁移语义，供测试与运维观察。
 */
public interface IngestTaskService {

    /** 认领 QUEUED 任务并提交 worker（@Scheduled 轮询，也可手动触发） */
    void poll();

    /** 清理过期的终态任务（SUCCESS/DLQ/CANCELLED），保留 retentionDays 天 */
    void cleanupOldTasks();

    /** 启动时把陈旧 RUNNING 任务回写 QUEUED（上次进程退出遗留） */
    void requeueStaleRunning();
}
