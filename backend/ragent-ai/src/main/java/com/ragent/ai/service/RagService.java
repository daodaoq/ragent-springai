package com.ragent.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 检索增强问答服务（P3；P4 接入混合检索 + 重排；P6 接入可插拔查询处理管线 + 多轮记忆）。
 * 检索方法同时服务于流式接口与评测程序，避免逻辑重复。
 */
public interface RagService {

    /** 检索（P4：混合检索 + 重排，返回带 score 的 Document）；走完整查询处理管线 */
    List<Document> retrieve(String question, int topK);

    /** 检索：processed=true 走查询处理管线（改写/多查询/HyDE/实体），false 原样（评测 A/B 基线） */
    List<Document> retrieve(String question, int topK, boolean processed);

    /** P8-6c：评测专用检索——includeEval=true 时允许召回评测注入的 EVAL 样例文档（生产路径恒为 false） */
    List<Document> retrieve(String question, int topK, boolean processed, boolean includeEval);

    /** 拼装带引用上下文的 Prompt */
    String buildPrompt(String question, List<Document> docs);

    /** 流式回答 */
    Flux<String> streamAnswer(String question, List<Document> docs);

    /** 一次性回答（评测用） */
    String answerSync(String question, List<Document> docs);

    /**
     * RAG 流式接口（P6）：先发 rewritten（查询处理轨迹）→ sources（来源）→ content（回答）。
     * 接入多轮记忆：改写消解指代、回答后写回记忆；后台异步落库查询日志。
     *
     * @param userId 登录用户 ID（未登录为 null），由 ragent-web 控制器从 Sa-Token 解析传入
     */
    Flux<ServerSentEvent<String>> ragStream(String question, String conversationId, Long userId);

    /**
     * RAG 流式接口（复用已算好的查询处理结果）：统一对话路由场景下，意图/改写已在
     * UnifiedChatService 算过一次，传入 precomputed 避免管线跑两次（两次 LLM）。
     * precomputed 为 null 时等价于 {@link #ragStream(String, String, Long)}。
     */
    Flux<ServerSentEvent<String>> ragStream(String question, String conversationId, Long userId,
                                            QueryPipeline.ProcessedQuery precomputed);

    /** P9：指定知识库检索的流式接口。kbId=null 检索全部可见库；非空限定指定库。 */
    Flux<ServerSentEvent<String>> ragStream(String question, String conversationId, Long userId,
                                            QueryPipeline.ProcessedQuery precomputed, Long kbId);

    record SourceItem(int idx, String filename, String excerpt, Double score,
                      String headingPath, Integer lineStart, Integer lineEnd, Integer page,
                      String documentId, String content) {
    }
}
