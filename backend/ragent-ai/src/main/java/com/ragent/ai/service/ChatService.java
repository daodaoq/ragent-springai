package com.ragent.ai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * AI 对话服务（P2：单轮流式对话，P3 接入 RAG 知识库）
 */
@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手，帮助同学和老师解答人工智能相关的学习、实验问题。
            回答要简洁准确、有条理；涉及步骤时分点列出；不清楚的地方如实说明，不要编造。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;

    public ChatService(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    public Flux<String> stream(String message) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return Flux.just("⚠️ AI 助手未配置。请在 application-local.yml 中配置 DeepSeek API Key 后重启。");
        }
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .stream()
                .content()
                .onErrorResume(e -> Flux.just("\n\n⚠️ 抱歉，AI 服务出错：" + e.getMessage()));
    }
}
