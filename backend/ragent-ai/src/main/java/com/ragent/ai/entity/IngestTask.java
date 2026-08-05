package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库异步处理任务（P9-5a）：
 * 上传/重切/重试 统一入此队列，轮询 worker 消费；瞬时失败自动重试，永久失败/超限进 DLQ。
 * 状态机：QUEUED → RUNNING → SUCCESS | FAILED | DLQ；删除文档时 QUEUED/RUNNING → CANCELLED。
 */
@Data
@TableName("ingest_task")
public class IngestTask {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DLQ = "DLQ";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String TYPE_UPLOAD = "UPLOAD";
    public static final String TYPE_RECHUNK = "RECHUNK";
    public static final String TYPE_RETRY = "RETRY";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 kb_document.id */
    private Long documentId;

    /** UPLOAD / RECHUNK / RETRY */
    private String taskType;

    /** QUEUED / RUNNING / SUCCESS / FAILED / DLQ / CANCELLED */
    private String status;

    private Integer attempt;

    private Integer maxAttempts;

    private String lastError;

    /** 入队时请求 traceId（worker 恢复 MDC，链路可查） */
    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
