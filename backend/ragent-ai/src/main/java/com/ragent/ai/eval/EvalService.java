package com.ragent.ai.eval;

import java.util.List;

/**
 * 检索与回答质量评测程序。
 * 流程：种子知识库 → 逐题检索+生成+LLM裁判打分 → 汇总指标。
 * 触发：POST /api/eval/run，返回可量化的评测报告。
 */
public interface EvalService {

    /**
     * 运行评测。
     *
     * @param processed true=检索走查询处理管线（改写/多查询/HyDE/实体）；false=原样检索（A/B 基线）
     */
    EvalReport run(boolean processed);

    // ==================== 数据结构 ====================

    record EvalCase(String question, List<String> docs) {
    }

    record JudgeScores(int faithfulness, int relevance) {
    }

    record CaseResult(String question, List<String> expectedDocs,
                      double recall, double precision, double mrr, double ndcg,
                      int faithfulness, int relevance, String answer) {
    }

    record RetrievalMetrics(double recallAt5, double precisionAt5, double mrrAt5, double ndcgAt5) {
    }

    record AnswerMetrics(double avgFaithfulness, double avgRelevance, double citationRate) {
    }

    record EvalReport(int totalCases, RetrievalMetrics retrieval, AnswerMetrics answer, List<CaseResult> cases) {
    }
}
