package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 查询日志（自动采集）：每次 ragStream 的完整轨迹——原始问题/意图/改写查询/召回来源 JSON/
 * 回答/耗时/是否被意图门禁拦截。供评测集挖掘与质量分析（前端「切片质量」页可查看真实查询）。
 */
@Data
@TableName("rag_query_log")
public class RagQueryLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户 ID（未登录为 null） */
    private Long userId;

    /** P8-7b：全链路 traceId（与 ELK 请求日志关联，定位坏案例到请求链路） */
    private String traceId;

    private String conversationId;

    /** 原始问题 */
    private String question;

    /** 意图：RAG/CHAT/OTHER */
    private String intent;

    /** 改写后检索查询 */
    private String rewrittenQuery;

    /** 意图门禁拦截：0否 1是 */
    private Boolean gated;

    /** 召回来源 JSON（含 filename/documentId/score/headingPath 等，与 SSE sources 一致） */
    private String sources;

    /** AI 回答 */
    private String answer;

    /** 总耗时 ms */
    private Integer latencyMs;

    /** 异常信息（若失败） */
    private String error;

    /** 创建时间（DB 端 DEFAULT CURRENT_TIMESTAMP 维护） */
    private LocalDateTime createdAt;
}
