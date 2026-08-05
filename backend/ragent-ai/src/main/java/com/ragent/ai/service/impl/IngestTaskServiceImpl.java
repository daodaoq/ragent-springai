package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.ai.config.IngestProperties;
import com.ragent.ai.entity.IngestTask;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.IngestTaskMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.IngestTaskService;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.ai.service.ingest.KbFilenameLock;
import com.ragent.common.context.RagentContext;
import com.ragent.common.context.RagentThreadPools;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步摄取任务队列消费端（P9-5a）。
 * <ul>
 *   <li><b>轮询</b>：{@link #poll} 每 pollIntervalMs 取 QUEUED 任务，CAS {@code claim} 后交
 *       {@code kb-ingest} 执行器跑（TTL+MDC 透传；拒绝则回写 QUEUED）。</li>
 *   <li><b>状态机</b>：QUEUED → RUNNING → SUCCESS；瞬时失败（SYSTEM_ERROR=500）attempt++ 回 QUEUED
 *       自动重试，永久失败（如 BAD_REQUEST=400 的扫描件）或 attempt 达上限 → DLQ + 文档 FAILED。</li>
 *   <li><b>每文件名锁</b>：worker 与 {@code delete()} / 两阶段替换经 {@link KbFilenameLock} 串行化，
 *       杜绝同名文档并发写入竞态（历史 p8_dedup 事故的根因）。</li>
 *   <li><b>链路</b>：任务携带入队时 traceId，worker 恢复 {@link RagentContext}（MDC），异步日志可关联。</li>
 * </ul>
 */
@Slf4j
@Service
public class IngestTaskServiceImpl implements IngestTaskService {

    /** 摄取 worker：核心 2/最大 4、有界队列 50；AbortPolicy 拒绝时由 poll 回写 QUEUED（不阻塞轮询线程） */
    private static final ThreadPoolExecutor INGEST_EXECUTOR = RagentThreadPools.newExecutor("kb-ingest",
            2, 4, 50, new ThreadPoolExecutor.AbortPolicy());

    private final IngestTaskMapper taskMapper;
    private final KbDocumentMapper documentMapper;
    private final KnowledgeBaseService kbService;
    private final IngestProperties props;
    private final KbFilenameLock kbFilenameLock;

    public IngestTaskServiceImpl(IngestTaskMapper taskMapper, KbDocumentMapper documentMapper,
                                 KnowledgeBaseService kbService, IngestProperties props,
                                 KbFilenameLock kbFilenameLock) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.kbService = kbService;
        this.props = props;
        this.kbFilenameLock = kbFilenameLock;
    }

    @PostConstruct
    void init() {
        requeueStaleRunning();
        log.info("异步摄取队列已启动: poll={}ms batch={} maxAttempts={}",
                props.getPollIntervalMs(), props.getBatchSize(), props.getMaxAttempts());
    }

    @Override
    @Scheduled(fixedDelayString = "${ragent.ingest.poll-interval-ms:1000}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        List<IngestTask> queued;
        try {
            queued = taskMapper.selectList(new LambdaQueryWrapper<IngestTask>()
                    .eq(IngestTask::getStatus, IngestTask.STATUS_QUEUED)
                    .orderByAsc(IngestTask::getId)
                    .last("LIMIT " + Math.max(1, props.getBatchSize())));
        } catch (Exception e) {
            log.warn("摄取队列拉取失败: {}", e.getMessage());
            return;
        }
        for (IngestTask t : queued) {
            if (taskMapper.claim(t.getId()) == 0) {
                continue; // 已被其他轮询实例/线程认领
            }
            try {
                INGEST_EXECUTOR.execute(() -> safeExecute(t.getId()));
            } catch (RejectedExecutionException e) {
                // worker 队列满：释放认领，下轮再试
                taskMapper.requeue(t.getId());
                log.warn("摄取 worker 队列已满，任务 {} 回写 QUEUED", t.getId());
            }
        }
    }

    /** worker 入口：恢复 trace 上下文 + 按文件名加锁，再执行任务（单文件失败只记日志，不拖垮批）。 */
    private void safeExecute(Long taskId) {
        IngestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        restoreTrace(task.getTraceId());
        try {
            KbDocument doc = documentMapper.selectById(task.getDocumentId());
            if (doc == null) {
                // 文档已被删除：任务无意义，标记 SUCCESS（避免失败重试空转）
                finish(task, IngestTask.STATUS_SUCCESS, "文档已删除，任务跳过");
                return;
            }
            kbFilenameLock.runWithLock(doc.getFilename(), () -> execute(task, doc));
        } catch (Exception e) {
            log.error("摄取任务执行异常: taskId={} docId={}", taskId, task.getDocumentId(), e);
        } finally {
            RagentContext.clear();
        }
    }

    /** 真实处理：成功 SUCCESS；失败按错误码分类（永久→DLQ，瞬时→重试）。包内可见便于单测。 */
    void execute(IngestTask task, KbDocument doc) {
        try {
            kbService.processDocument(task.getDocumentId(), task.getTaskType());
            finish(task, IngestTask.STATUS_SUCCESS, null);
            // processDocument 成功路径已把文档置 READY 并清 errorMsg
        } catch (BusinessException be) {
            handleFailure(task, doc, be.getCode(), be.getMessage());
        } catch (Exception e) {
            handleFailure(task, doc, ErrorCode.SYSTEM_ERROR.getCode(),
                    e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    /**
     * 失败分派：SYSTEM_ERROR(500) 视为瞬时——attempt 未达上限则回 QUEUED 重试、文档回 PENDING；
     * 其他错误码（400/404 等）为永久失败，或尝试超限——直接 DLQ + 文档 FAILED。
     */
    private void handleFailure(IngestTask task, KbDocument doc, int code, String msg) {
        String shortMsg = truncate(msg);
        boolean transientError = code == ErrorCode.SYSTEM_ERROR.getCode();
        if (!transientError || task.getAttempt() >= task.getMaxAttempts()) {
            task.setStatus(IngestTask.STATUS_DLQ);
            task.setLastError(shortMsg);
            taskMapper.updateById(task);
            doc.setStatus("FAILED");
            doc.setErrorMsg(shortMsg);
            documentMapper.updateById(doc);
            log.warn("摄取任务进 DLQ: task={} doc={} attempts={} err={}",
                    task.getId(), doc.getId(), task.getAttempt(), shortMsg);
        } else {
            task.setAttempt(task.getAttempt() + 1);
            task.setStatus(IngestTask.STATUS_QUEUED);
            task.setLastError(shortMsg);
            taskMapper.updateById(task);
            // 文档回 PENDING：避免 FAILED↔PROCESSING 反复闪烁，前端持续轮询处理中
            doc.setStatus("PENDING");
            doc.setErrorMsg(null);
            documentMapper.updateById(doc);
            log.info("摄取任务将重试: task={} doc={} attempt={}/{} err={}",
                    task.getId(), doc.getId(), task.getAttempt(), task.getMaxAttempts(), shortMsg);
        }
    }

    private void finish(IngestTask task, String status, String note) {
        task.setStatus(status);
        task.setLastError(note);
        taskMapper.updateById(task);
    }

    /** 按任务记录的 traceId 恢复请求上下文（MDC 同步写入，异步日志可关联链路）。 */
    private void restoreTrace(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            RagentContext.set(RagentContext.builder().traceId(traceId).build());
        }
    }

    @Override
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupOldTasks() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusDays(Math.max(1, props.getRetentionDays()));
            int n = taskMapper.delete(new LambdaQueryWrapper<IngestTask>()
                    .in(IngestTask::getStatus,
                            IngestTask.STATUS_SUCCESS, IngestTask.STATUS_DLQ, IngestTask.STATUS_CANCELLED)
                    .lt(IngestTask::getUpdatedAt, deadline));
            if (n > 0) {
                log.info("清理过期摄取任务 {} 条（保留 {} 天）", n, props.getRetentionDays());
            }
        } catch (Exception e) {
            log.warn("清理过期摄取任务失败: {}", e.getMessage());
        }
    }

    @Override
    public void requeueStaleRunning() {
        try {
            int n = taskMapper.requeueAllRunning();
            if (n > 0) {
                log.info("启动恢复 {} 个中断的摄取任务（RUNNING→QUEUED）", n);
            }
        } catch (Exception e) {
            log.warn("恢复中断摄取任务失败: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String clean = s.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 900 ? clean.substring(0, 900) + "…" : clean;
    }
}
