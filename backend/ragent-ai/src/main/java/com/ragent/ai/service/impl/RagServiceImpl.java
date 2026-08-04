package com.ragent.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.service.AiRetry;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.QueryPipeline;
import com.ragent.ai.service.RagQueryLogService;
import com.ragent.ai.service.RagService;
import com.ragent.ai.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索增强问答服务实现（P3；P4 接入混合检索 + 重排；P6 接入可插拔查询处理管线 + 多轮记忆 + 查询日志）。
 * 检索方法同时服务于流式接口与评测程序，避免逻辑重复。
 */
@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手。
            你必须基于【知识库内容】回答问题，回答简洁准确、有条理。
            引用规则：每个关键观点后面必须用方括号标注来源编号，如 [1]、[2]；
            如果知识库内容不足以回答，就如实说明不知道，不要编造。
            """;

    private static final String NON_RAG_HINT = "这个问题不像是在问知识库内容（可能想闲聊或与知识库无关）。"
            + "请提与知识库相关的问题，我会基于知识库内容回答。";

    private final RetrievalService retrievalService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final RetrievalProperties props;
    private final ChatMemoryService chatMemoryService;
    private final QueryPipeline queryPipeline;
    private final RagQueryLogService queryLogService;

    public RagServiceImpl(RetrievalService retrievalService, ObjectProvider<ChatClient> chatClientProvider,
                          ObjectMapper objectMapper, RetrievalProperties props,
                          ChatMemoryService chatMemoryService, QueryPipeline queryPipeline,
                          RagQueryLogService queryLogService) {
        this.retrievalService = retrievalService;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.chatMemoryService = chatMemoryService;
        this.queryPipeline = queryPipeline;
        this.queryLogService = queryLogService;
    }

    @Override
    public List<Document> retrieve(String question, int topK) {
        return retrieve(question, topK, true);
    }

    @Override
    public List<Document> retrieve(String question, int topK, boolean processed) {
        if (!processed) {
            return retrievalService.retrieve(question, topK);
        }
        // 评测/无会话路径：无历史、关闭意图门禁（评测用例都是知识库问题，避免误判跳过）
        QueryPipeline.ProcessedQuery pq = queryPipeline.run(question, List.of(), false);
        return retrievalService.retrieve(toRetrievalQuery(pq), topK);
    }

    @Override
    public String buildPrompt(String question, List<Document> docs) {
        StringBuilder context = new StringBuilder();
        int i = 1;
        for (Document d : docs) {
            context.append("[").append(i++).append("] ").append(d.getText()).append("\n\n");
        }
        if (docs.isEmpty()) {
            context.append("（知识库中未检索到相关内容）\n\n");
        }
        return "【知识库内容】\n" + context + "【问题】\n" + question;
    }

    @Override
    public Flux<String> streamAnswer(String question, List<Document> docs) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return Flux.just("⚠️ AI 助手未配置，请先配置 DeepSeek API Key。");
        }
        return AiRetry.streamWithRetry(() -> chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildPrompt(question, docs))
                .stream()
                .content());
    }

    @Override
    public String answerSync(String question, List<Document> docs) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return "⚠️ AI 助手未配置";
        }
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildPrompt(question, docs))
                .call()
                .content();
    }

    @Override
    public Flux<ServerSentEvent<String>> ragStream(String question, String conversationId, Long userId) {
        long start = System.nanoTime();
        List<ChatMemoryService.ChatMessage> history = chatMemoryService.load(conversationId);
        QueryPipeline.ProcessedQuery pq = queryPipeline.run(question, history, true);
        if (pq.gated()) {
            // 意图门禁命中：非知识库问题，不检索，只给提示
            recordQuery(userId, conversationId, question, pq, true, null, NON_RAG_HINT,
                    (System.nanoTime() - start) / 1_000_000, null);
            return Flux.just(
                    sse("rewritten", rewrittenJson(pq)),
                    sse("content", NON_RAG_HINT));
        }

        List<Document> docs = retrievalService.retrieve(toRetrievalQuery(pq), props.getTopK());
        String sourcesJson = sourcesJson(docs);
        StringBuilder answer = new StringBuilder();
        return Flux.concat(
                Flux.just(sse("rewritten", rewrittenJson(pq))),
                Flux.just(sse("sources", sourcesJson)),
                streamAnswer(question, docs)
                        .doOnNext(answer::append)
                        .doOnComplete(() -> {
                            chatMemoryService.append(conversationId, question, answer.toString());
                            recordQuery(userId, conversationId, question, pq, false, sourcesJson,
                                    answer.toString(), (System.nanoTime() - start) / 1_000_000, null);
                        })
                        .doOnError(e -> recordQuery(userId, conversationId, question, pq, false, sourcesJson,
                                answer.toString(), (System.nanoTime() - start) / 1_000_000, shortMsg(e)))
                        .map(c -> sse("content", c))
        );
    }

    /** 后台异步落库查询日志（受 ragent.retrieval.query-log-enabled 控制；异常仅告警） */
    private void recordQuery(Long userId, String conversationId, String question,
                             QueryPipeline.ProcessedQuery pq, boolean gated,
                             String sourcesJson, String answer, long latencyMs, String error) {
        if (!props.isQueryLogEnabled()) {
            return;
        }
        try {
            queryLogService.recordAsync(new RagQueryLogService.QueryLogData(
                    userId, conversationId, question, pq.intent(), pq.rewrittenQuery(),
                    gated, sourcesJson, answer, latencyMs, error));
        } catch (Exception e) {
            log.warn("查询日志记录失败: {}", e.getMessage());
        }
    }

    private static String shortMsg(Throwable e) {
        String m = e == null || e.getMessage() == null ? "unknown" : e.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    // ==================== 管线产物 → 检索规格 ====================

    /**
     * 检索查询拼装：
     * dense = [hyde?] + variants（无变体则改写句）；
     * keyword = 改写句(术语最全) + variants（P6 补：展开词此前只进 rerank，keyword 通道没吃到）；
     * rerank 用改写句；实体过滤 filename/page。
     */
    private RetrievalService.RetrievalQuery toRetrievalQuery(QueryPipeline.ProcessedQuery pq) {
        String rewritten = pq.rewrittenQuery();
        List<String> variants = (pq.variants() == null || pq.variants().isEmpty())
                ? List.of() : pq.variants();

        List<String> dense = new ArrayList<>();
        if (pq.hyde() != null && !pq.hyde().isBlank()) {
            dense.add(pq.hyde());
        }
        dense.add(rewritten);
        for (String v : variants) {
            if (!dense.contains(v)) {
                dense.add(v);
            }
        }

        List<String> keyword = new ArrayList<>();
        if (rewritten != null && !rewritten.isBlank()) {
            keyword.add(rewritten);
        }
        for (String v : variants) {
            if (!keyword.contains(v)) {
                keyword.add(v);
            }
        }

        RetrievalService.EntityHint filter = (pq.filename() != null || pq.page() != null)
                ? new RetrievalService.EntityHint(pq.filename(), pq.page()) : null;
        return new RetrievalService.RetrievalQuery(rewritten, dense, keyword, filter);
    }

    /** rewritten SSE 事件：intent + 改写后查询 + 各阶段轨迹（前端透明展示） */
    private String rewrittenJson(QueryPipeline.ProcessedQuery pq) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", pq.intent());
            m.put("rewrittenQuery", pq.rewrittenQuery());
            m.put("filename", pq.filename());
            m.put("page", pq.page());
            List<Map<String, Object>> stages = new ArrayList<>();
            for (QueryPipeline.StageRun r : pq.runs()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", r.name());
                sm.put("ok", r.ok());
                sm.put("ms", r.ms());
                stages.add(sm);
            }
            m.put("stages", stages);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================== sources ====================

    private String sourcesJson(List<Document> docs) {
        try {
            List<SourceItem> sources = new ArrayList<>();
            int i = 1;
            for (Document d : docs) {
                Map<String, Object> meta = d.getMetadata();
                String filename = String.valueOf(meta.getOrDefault("filename", ""));
                String text = d.getText();
                String excerpt = text.length() > 150 ? text.substring(0, 150) + "…" : text;
                sources.add(new SourceItem(i++, filename, excerpt, d.getScore(),
                        asStr(meta.get("headingPath")),
                        asInt(meta.get("lineStart")), asInt(meta.get("lineEnd")),
                        asInt(meta.get("page")), asStr(meta.get("documentId")), text));
            }
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
