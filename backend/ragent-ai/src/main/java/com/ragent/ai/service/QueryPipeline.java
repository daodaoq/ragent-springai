package com.ragent.ai.service;

import java.util.List;

/**
 * 查询处理管线：按 DB 配置顺序运行启用的 {@link QueryStage}，产出最终检索输入。
 * 任何阶段失败都降级跳过；主开关关闭时只做 A 规范化（等价原行为）。
 */
public interface QueryPipeline {

    /** 管线产物：改写/多查询/HyDE/实体 的最终结果 + 各阶段运行轨迹（供 SSE 透明展示） */
    record ProcessedQuery(String intent, String rewrittenQuery, List<String> variants,
                          String hyde, String filename, Integer page,
                          String normalizedQuery, boolean gated, List<StageRun> runs) {
    }

    /** 单阶段运行记录（name/是否成功/耗时 ms/错误信息） */
    record StageRun(String name, boolean ok, long ms, String error) {
    }

    /**
     * @param gateByIntent 是否启用意图门禁：true 时非 RAG 意图标记 gated=true（调用方短路）；
     *                     false 时（评测/关闭门禁）一律视为 RAG 照常检索
     */
    ProcessedQuery run(String rawQuestion, List<ChatMemoryService.ChatMessage> history, boolean gateByIntent);
}
