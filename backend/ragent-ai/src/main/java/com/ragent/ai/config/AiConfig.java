package com.ragent.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置。
 * 直接依赖 ChatModel（由 OpenAI starter 自动装配，base-url 指向 DeepSeek）。
 * 未配置 API Key 时 ChatModel 不存在，应用启动会明确报错提示配置。
 */
@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
