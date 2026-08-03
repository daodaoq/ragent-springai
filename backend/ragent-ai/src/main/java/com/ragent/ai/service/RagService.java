package com.ragent.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索增强问答服务（P3）
 * 检索方法同时服务于流式接口与评测程序，避免逻辑重复。
 */
@Service
public class RagService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手。
            你必须基于【知识库内容】回答问题，回答简洁准确、有条理。
            引用规则：每个关键观点后面必须用方括号标注来源编号，如 [1]、[2]；
            如果知识库内容不足以回答，就如实说明不知道，不要编造。
            """;

    private final VectorStore vectorStore;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;

    public RagService(VectorStore vectorStore, ObjectProvider<ChatClient> chatClientProvider, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
    }

    /** 向量检索 */
    public List<Document> retrieve(String question, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());
    }

    /** 拼装带引用上下文的 Prompt */
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

    /** 流式回答 */
    public Flux<String> streamAnswer(String question, List<Document> docs) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return Flux.just("⚠️ AI 助手未配置，请先配置 DeepSeek API Key。");
        }
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildPrompt(question, docs))
                .stream()
                .content()
                .onErrorResume(e -> Flux.just("\n\n⚠️ AI 服务出错：" + e.getMessage()));
    }

    /** 一次性回答（评测用） */
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

    /** RAG 流式接口：先发 sources 事件，再发 content 事件 */
    public Flux<ServerSentEvent<String>> ragStream(String question) {
        List<Document> docs = retrieve(question, 4);
        return Flux.concat(
                Flux.just(sse("sources", sourcesJson(docs))),
                streamAnswer(question, docs).map(c -> sse("content", c))
        );
    }

    private String sourcesJson(List<Document> docs) {
        try {
            List<SourceItem> sources = new ArrayList<>();
            int i = 1;
            for (Document d : docs) {
                String filename = String.valueOf(d.getMetadata().getOrDefault("filename", ""));
                String text = d.getText();
                String excerpt = text.length() > 150 ? text.substring(0, 150) + "…" : text;
                sources.add(new SourceItem(i++, filename, excerpt));
            }
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "[]";
        }
    }

    public record SourceItem(int idx, String filename, String excerpt) {
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
