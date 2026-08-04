package com.ragent.web.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragent.web.config.RetrievalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * 阿里百炼 gte-rerank 重排客户端（P4）。
 * 调用 DashScope 原生 rerank 接口（非 OpenAI 兼容模式），对候选文档按相关性精排。
 * 失败时抛异常，由 RetrievalService 回退到 RRF 融合结果。
 */
@Slf4j
@Service
public class DashScopeRerankClient {

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestClient restClient;
    private final RetrievalProperties props;

    public DashScopeRerankClient(RetrievalProperties props) {
        this.props = props;
        // 显式超时：ragStream 同步检索阶段会调用本客户端，超时太长会卡住 SSE 首字节
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 对候选文档重排，返回按相关性降序、已写 score 的文档列表。
     *
     * @param query      用户问题
     * @param candidates 候选文档（RRF 融合后）
     * @param topN       返回条数
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        String key = props.getRerankApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ragent.retrieval.rerank-api-key 未配置");
        }
        RerankRequest body = new RerankRequest(props.getRerankModel(), query,
                candidates.stream().map(Document::getText).toList(), topN);
        RerankResponse resp = restClient.post()
                .uri(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RerankResponse.class);
        if (resp == null || resp.output() == null || resp.output().results() == null) {
            throw new IllegalStateException("DashScope rerank 响应为空");
        }
        List<RerankResponse.Result> results = resp.output().results();
        return results.stream()
                .sorted(Comparator.<RerankResponse.Result>comparingDouble(
                        RerankResponse.Result::relevance_score).reversed())
                .map(r -> candidates.get(r.index()).mutate().score(r.relevance_score()).build())
                .toList();
    }

    // ---------- DashScope 请求/响应结构（snake_case 与 JSON 精确对齐） ----------

    public record RerankRequest(String model, Input input, Parameters parameters) {
        public RerankRequest(String model, String query, List<String> documents, int topN) {
            this(model, new Input(query, documents), new Parameters(false, topN));
        }

        public record Input(String query, List<String> documents) {
        }

        public record Parameters(boolean return_documents, int top_n) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResponse(Output output) {
        public record Output(List<Result> results) {
        }

        public record Result(int index, double relevance_score) {
        }
    }
}
