package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 评测结果（P8-6b）：每次 /eval/run 的汇总指标 + 完整报告 JSON 快照。
 * 供历史对比 / 回归追踪 / 配置变更前后对比（此前 EvalReport 仅内存返回，刷新即丢）。
 */
@Data
@TableName("eval_result")
public class EvalResult {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 是否走查询处理管线（0 = 原样检索 A/B 基线） */
    private Boolean processed;

    /** 是否含回答生成 + LLM 裁判打分（0 = 仅检索指标） */
    private Boolean withAnswer;

    private Integer totalCases;

    /** Recall@5 */
    private Double recall;

    /** Precision@5 */
    private Double precision;

    /** MRR@5 */
    private Double mrr;

    /** NDCG@5 */
    private Double ndcg;

    private Double avgFaithfulness;

    private Double avgRelevance;

    private Double citationRate;

    /** 完整评测报告 JSON（含逐用例结果），供下钻分析 */
    private String detailJson;

    private LocalDateTime createdAt;
}
