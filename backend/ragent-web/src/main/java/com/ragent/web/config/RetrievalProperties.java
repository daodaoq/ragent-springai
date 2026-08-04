package com.ragent.web.config;

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
}
