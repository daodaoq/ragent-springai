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

    record SourceItem(int idx, String filename, String excerpt, Double score,
                      String headingPath, Integer lineStart, Integer lineEnd, Integer page,
                      String documentId, String content) {
    }
}
