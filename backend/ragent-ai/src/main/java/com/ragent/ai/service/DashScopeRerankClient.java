package com.ragent.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 阿里百炼 gte-rerank 重排客户端（P4）。
 * 调用 DashScope 原生 rerank 接口（非 OpenAI 兼容模式），对候选文档按相关性精排。
 * 失败时抛异常，由 RetrievalService 回退到 RRF 融合结果。
 */
public interface DashScopeRerankClient {

    /**
     * 对候选文档重排，返回按相关性降序、已写 score 的文档列表。
     *
     * @param query      用户问题
     * @param candidates 候选文档（RRF 融合后）
     * @param topN       返回条数
     */
    List<Document> rerank(String query, List<Document> candidates, int topN);

    // ---------- DashScope 请求/响应结构（snake_case 与 JSON 精确对齐） ----------

    record RerankRequest(String model, Input input, Parameters parameters) {
        public RerankRequest(String model, String query, List<String> documents, int topN) {
            this(model, new Input(query, documents), new Parameters(false, topN));
        }

        public record Input(String query, List<String> documents) {
        }

        public record Parameters(boolean return_documents, int top_n) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(Output output) {
        public record Output(List<Result> results) {
        }

        public record Result(int index, double relevance_score) {
        }
    }
}
