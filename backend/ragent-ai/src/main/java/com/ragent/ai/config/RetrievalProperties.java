package com.ragent.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索配置（application.yml 中 ragent.retrieval.*）。
 * 管线：稠密向量 topN + 关键词 topN → RRF 融合 → 可选 rerank 重排 → topK。
 */
@Data
@ConfigurationProperties(prefix = "ragent.retrieval")
public class RetrievalProperties {

    /** 向量检索候选数 */
    private int denseTopN = 20;

    /** 关键词检索候选数 */
    private int keywordTopN = 20;

    /** 最终返回条数（同时作为重排 top_n） */
    private int topK = 5;

    /** RRF 融合常数 k */
    private int rrfK = 60;

    /** 是否启用关键词检索（FULLTEXT 索引缺失时可关闭降级） */
    private boolean keywordEnabled = true;

    /** 是否启用 rerank 重排 */
    private boolean rerankEnabled = true;

    /** 送入重排的候选数（RRF 融合后截断到该数） */
    private int rerankTopN = 10;

    /** 重排模型 */
    private String rerankModel = "gte-rerank-v2";

    /** DashScope Key；占位符在 application.yml 中解析（默认复用 embedding key） */
    private String rerankApiKey = "";

    /** 查询处理主开关（A-G 管线）：false 只做 A 规范化 + 原样检索；阶段启停/顺序走 DB kb_query_stage */
    private boolean queryProcessingEnabled = true;

    /** D 多查询变体数量上限 */
    private int multiQueryCount = 3;

    /** F 喂给改写的最近用户轮数 */
    private int contextTurnCount = 3;

    /** 查询日志采集开关（每次 RAG 请求后台异步落库 rag_query_log，供评测集挖掘/质量分析） */
    private boolean queryLogEnabled = true;

    /** P8-1a：重排后相关性分数下限（0 表示不启用阈值）。仅 rerank 成功时生效；
     * RRF 归一化分数与 rerank relevance_score 尺度不同，降级路径不做阈值以免误杀。 */
    private double minScore = 0;

    /** P8-7a：检索结果缓存 TTL（秒，0=关闭）。避免重复查询每次重跑 ≤9 次检索 + rerank；
     * 文档增删改/重切时主动失效，TTL 仅作兜底。 */
    private int cacheTtlSeconds = 60;

    /** P9：是否在 Qdrant payload 上按 kbId 预过滤稠密检索。默认 false——
     * 旧向量 payload 无 kbId，预过滤会隐藏所有历史文档；正确性当前靠 DB 后置过滤保证。
     * 全量重摄入（所有向量都带 kbId payload）后再开启可提升多库场景的稠密召回精准度。 */
    private boolean denseFilterByKbid = false;
}
