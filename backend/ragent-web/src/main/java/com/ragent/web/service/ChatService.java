package com.ragent.web.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 对话服务（P2 单轮流式；P5 接入 Redis 多轮会话记忆）。
 * 消息顺序：System + 历史 + 本次用户问题。
 */
@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手，帮助同学和老师解答人工智能相关的学习、实验问题。
            回答要简洁准确、有条理；涉及步骤时分点列出；不清楚的地方如实说明，不要编造。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChatMemoryService chatMemoryService;

    public ChatService(ObjectProvider<ChatClient> chatClientProvider, ChatMemoryService chatMemoryService) {
        this.chatClientProvider = chatClientProvider;
        this.chatMemoryService = chatMemoryService;
    }

    public Flux<String> stream(String message, String conversationId) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return Flux.just("⚠️ AI 助手未配置。请在 application-local.yml 中配置 DeepSeek API Key 后重启。");
        }
        List<Message> history = chatMemoryService.load(conversationId).stream()
                .map(cm -> cm.role().equals("user") ? (Message) new UserMessage(cm.content()) : (Message) new AssistantMessage(cm.content()))
                .toList();
        StringBuilder answer = new StringBuilder();
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(message)
                .stream()
                .content()
                .doOnNext(answer::append)
                .doOnComplete(() -> chatMemoryService.append(conversationId, message, answer.toString()))
                .onErrorResume(e -> Flux.just("\n\n⚠️ 抱歉，AI 服务出错：" + e.getMessage()));
    }
}
