package com.ragent.ai.service.impl;

import com.ragent.ai.service.AiRetry;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.ChatService;
import com.ragent.common.context.RagentContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 对话服务实现（P2 单轮流式；P5 接入 Redis 多轮会话记忆）。
 * 消息顺序：System + 历史 + 本次用户问题。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手，帮助同学和老师解答人工智能相关的学习、实验问题。
            回答要简洁准确、有条理；涉及步骤时分点列出；不清楚的地方如实说明，不要编造。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChatMemoryService chatMemoryService;

    public ChatServiceImpl(ObjectProvider<ChatClient> chatClientProvider, ChatMemoryService chatMemoryService) {
        this.chatClientProvider = chatClientProvider;
        this.chatMemoryService = chatMemoryService;
    }

    @Override
    public Flux<String> stream(String message, String conversationId) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return Flux.just("⚠️ AI 助手未配置。请在 application-local.yml 中配置 DeepSeek API Key 后重启。");
        }
        // 带摘要注入：system 摘要消息 + 最近窗口消息（长对话关键上下文由摘要保留，Token 成本可控）
        List<Message> history = chatMemoryService.loadWithSummary(conversationId).stream()
                .map(cm -> switch (cm.role()) {
                    case "user" -> (Message) new UserMessage(cm.content());
                    case "system" -> (Message) new SystemMessage(cm.content());
                    default -> (Message) new AssistantMessage(cm.content());
                })
                .toList();
        StringBuilder answer = new StringBuilder();
        // 请求线程上下文（TTL）：WebClient 终态线程不自动携带 MDC，这里捕获并在 doOnComplete 恢复，
        // 使记忆摘要等异步池提交能带上 traceId/userId
        RagentContext ctx = RagentContext.current();
        return AiRetry.streamWithRetry(() -> chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(message)
                .stream()
                .content()
                .doOnNext(answer::append)
                .doOnComplete(() -> {
                    if (ctx != null) {
                        RagentContext.set(ctx);
                    }
                    try {
                        chatMemoryService.append(conversationId, message, answer.toString());
                    } finally {
                        if (ctx != null) {
                            RagentContext.clear();
                        }
                    }
                }));
    }
}
