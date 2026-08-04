package com.ragent.ai.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * P4 混合检索：向量（Qdrant） + 关键词（MySQL FULLTEXT ngram）→ RRF 融合 → DashScope 重排。
 * 每个阶段独立兜底：关键词失败→仅向量；重排失败→用融合结果；向量失败→空列表（不抛给调用方）。
 */
public interface RetrievalService {

    /** 主入口：混合检索 + 重排，返回最终 topK 条（带 score）。 */
    List<Document> retrieve(String question, int topK);
}
