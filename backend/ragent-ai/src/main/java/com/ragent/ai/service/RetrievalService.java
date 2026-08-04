package com.ragent.ai.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * P4 混合检索：向量（Qdrant） + 关键词（MySQL FULLTEXT ngram）→ RRF 融合 → DashScope 重排。
 * P6 扩展：支持多路检索查询（多查询/HyDE）与实体过滤（filename/page）。
 * 每个阶段独立兜底：关键词失败→仅向量；重排失败→用融合结果；向量失败→空列表（不抛给调用方）。
 */
public interface RetrievalService {

    /** 实体过滤提示：filename/page 从问题中抽取，用于精确收窄候选 */
    record EntityHint(String filename, Integer page) {
    }

    /**
     * 检索规格：rerankQuery 用于重排；denseQueries/keywordQueries 为各自通道的查询列表（每路一条 ranked list）；
     * filter 为实体过滤。单查询等价于 {@link #single(String)}。
     */
    record RetrievalQuery(String rerankQuery,
                          List<String> denseQueries,
                          List<String> keywordQueries,
                          EntityHint filter) {

        /** 单查询、无过滤（等价于改造前的行为） */
        public static RetrievalQuery single(String q) {
            return new RetrievalQuery(q, List.of(q), List.of(q), null);
        }
    }

    /** 主入口（改造前签名）：等价于 {@code retrieve(RetrievalQuery.single(question), topK)} */
    List<Document> retrieve(String question, int topK);

    /** 多路检索 + 实体过滤入口（P6 管线使用） */
    List<Document> retrieve(RetrievalQuery rq, int topK);
}
