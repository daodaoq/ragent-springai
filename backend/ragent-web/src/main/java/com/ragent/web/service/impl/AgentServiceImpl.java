package com.ragent.web.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.service.AiRetry;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.web.agent.AgentTools;
import com.ragent.web.service.AgentService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 智能体服务实现（P5 核心）：手动有界工具循环。
 * <p>
 * 关键点：
 * <ul>
 *   <li>工具 callbacks 必须设置在 {@link DefaultToolCallingChatOptions} 上经 {@code .options(...)} 传入
 *       （ChatClient 的 {@code .tools(...)} 仅对 ToolCallingChatOptions 生效，不可依赖合并顺序）</li>
 *   <li>{@code internalToolExecutionEnabled(false)} 让模型返回原始 tool-call 消息而不自动执行，
 *       由本服务逐个执行并逐条发 SSE tool-call 事件</li>
 *   <li>有界循环（≤MAX_ROUNDS）防止 DeepSeek function-calling 不稳定导致的死循环</li>
 * </ul>
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final String SYSTEM_PROMPT = """
            你是人工智能实验室的智能问答助手，拥有调用工具的能力。
            请根据用户问题，调用合适的工具获取真实数据，再基于工具返回结果组织回答。
            工具返回的结果是唯一事实来源，不要编造工具返回之外的数据。
            一次调用尽量获取足够信息；当已获得回答用户问题所需的信息时，立即直接组织回答，
            不要继续调用更多工具。
            回答要简洁准确、有条理；涉及步骤时分点列出。
            """;

    private static final int MAX_ROUNDS = 6;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChatMemoryService chatMemoryService;
    private final AgentTools agentTools;
    private final ObjectMapper objectMapper;

    public AgentServiceImpl(ObjectProvider<ChatClient> chatClientProvider, ChatMemoryService chatMemoryService,
                            AgentTools agentTools, ObjectMapper objectMapper) {
        this.chatClientProvider = chatClientProvider;
        this.chatMemoryService = chatMemoryService;
        this.agentTools = agentTools;
        this.objectMapper = objectMapper;
    }

    /** 工具循环用同步 .call()，包一层 Flux.defer + boundedElastic 避免阻塞 Netty 事件循环 */
    @Override
    public Flux<ServerSentEvent<String>> agentStream(String question, String conversationId) {
        return Flux.defer(() -> Flux.fromIterable(runLoop(question, conversationId)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Flux.just(sse("content", "⚠️ " + AiRetry.friendlyMessage(e))));
    }

    private List<ServerSentEvent<String>> runLoop(String question, String conversationId) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return List.of(sse("content", "⚠️ AI 助手未配置，请先配置 DeepSeek API Key。"));
        }

        List<ToolCallback> toolCallbacks = List.of(ToolCallbacks.from(agentTools));
        ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        List<Message> messages = new ArrayList<>();
        // 带摘要注入：system 摘要消息 + 最近窗口消息（长对话关键上下文由摘要保留）
        for (ChatMemoryService.ChatMessage cm : chatMemoryService.loadWithSummary(conversationId)) {
            messages.add(switch (cm.role()) {
                case "user" -> new UserMessage(cm.content());
                case "system" -> new SystemMessage(cm.content());
                default -> new AssistantMessage(cm.content());
            });
        }
        messages.add(new UserMessage(question));

        List<ServerSentEvent<String>> events = new ArrayList<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            ChatResponse response = AiRetry.callWithRetry(() -> chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(messages)
                    .options(options)
                    .call()
                    .chatResponse());
            AssistantMessage output = response.getResult().getOutput();
            if (output.hasToolCalls()) {
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                    ToolCallback cb = toolCallbacks.stream()
                            .filter(c -> c.getToolDefinition().name().equals(tc.name()))
                            .findFirst().orElse(null);
                    String result;
                    if (cb == null) {
                        result = "{\"error\":\"unknown tool: " + tc.name() + "\"}";
                    } else {
                        try {
                            result = cb.call(tc.arguments());
                        } catch (Exception e) {
                            result = "{\"error\":\"" + e.getMessage() + "\"}";
                        }
                    }
                    events.add(sse("tool-call", toJson(new ToolCallEvent(tc.name(), tc.arguments(), result))));
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
                }
                messages.add(output);
                messages.add(new ToolResponseMessage(toolResponses));
            } else {
                String answer = output.getText() == null ? "" : output.getText();
                events.add(sse("content", answer));
                chatMemoryService.append(conversationId, question, answer);
                return events;
            }
        }
        String fallback = "抱歉，工具调用次数较多仍未完成回答，请简化问题或换一种问法。";
        events.add(sse("content", fallback));
        chatMemoryService.append(conversationId, question, fallback);
        return events;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
