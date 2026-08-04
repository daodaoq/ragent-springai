package com.ragent.ai.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties;
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

    /**
     * 自定义 QdrantClient（bean 名与 Spring AI 自动配置一致，自动配置会因 @ConditionalOnMissingBean 退让）。
     * <p>原因：spring-ai 自带的 qdrant client 是 1.13.0，而 docker 里跑的是 qdrant:latest（1.18.x），
     * 默认版本校验会在每次启动打 WARN「client 1.13.0 is incompatible with server 1.18.3」。
     * Qdrant gRPC API 在同一 major 版本内前后兼容，这里显式关闭校验即可消除噪音；
     * 相比把 docker 镜像降级到 v1.13（旧版本可能读不了 1.18 写的数据卷），关闭校验风险更低。</p>
     */
    @Bean
    QdrantClient qdrantClient(QdrantVectorStoreProperties properties) {
        QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(
                properties.getHost(), properties.getPort(), properties.isUseTls(), false);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            grpcClientBuilder.withApiKey(properties.getApiKey());
        }
        return new QdrantClient(grpcClientBuilder.build());
    }
}
