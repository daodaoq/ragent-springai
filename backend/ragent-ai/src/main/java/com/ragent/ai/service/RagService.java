package com.ragent.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 检索增强问答服务（P3；P4 接入混合检索 + 重排）。
 * 检索方法同时服务于流式接口与评测程序，避免逻辑重复。
 */
public interface RagService {

    /** 检索（P4：混合检索 + 重排，返回带 score 的 Document） */
    List<Document> retrieve(String question, int topK);

    /** 拼装带引用上下文的 Prompt */
    String buildPrompt(String question, List<Document> docs);

    /** 流式回答 */
    Flux<String> streamAnswer(String question, List<Document> docs);

    /** 一次性回答（评测用） */
    String answerSync(String question, List<Document> docs);

    /** RAG 流式接口：先发 sources 事件，再发 content 事件 */
    Flux<ServerSentEvent<String>> ragStream(String question);

    record SourceItem(int idx, String filename, String excerpt, Double score) {
    }
}
