package com.ragent.ai.config;

import com.ragent.ai.service.circuit.ChatModelRouter;
import com.ragent.ai.service.circuit.CircuitBreaker;
import com.ragent.ai.service.circuit.ModelEndpoint;
import io.micrometer.observation.ObservationRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 配置。
 * 主模型 ChatModel 由 OpenAI starter 自动装配（base-url 指向 DeepSeek，模型 deepseek-v4-flash）。
 * 这里把它包进 {@link ChatModelRouter}（@Primary），并追加候选模型降级链
 * （ragent.model.fallback-models，同一 key/base-url、仅模型名不同）——所有 ChatClient 调用
 * 自动经过「三态熔断 + 首包探测 + 候选切换」，业务代码零改动。
 * 未配置 API Key 时主 ChatModel 不存在，应用启动会明确报错提示配置。
 */
@Configuration
public class AiConfig {

    private final ModelFailoverProperties modelFailoverProps;

    public AiConfig(ModelFailoverProperties modelFailoverProps) {
        this.modelFailoverProps = modelFailoverProps;
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 模型路由装饰器（@Primary ChatModel）：主模型 + 候选模型降级链。
     */
    @Bean
    @Primary
    ChatModelRouter chatModelRouter(
            OpenAiChatModel primaryModel,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl) {
        // ObservationRegistry 在无 actuator/micrometer 时可能不存在，容忍缺失退化为 NOOP（与 Spring AI 自动装配一致）
        ObservationRegistry observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        int threshold = modelFailoverProps.getFailureThreshold();
        long openMs = modelFailoverProps.getOpenDurationMs();
        int halfOpen = modelFailoverProps.getHalfOpenMaxCalls();
        List<ModelEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new ModelEndpoint("primary", primaryModel,
                new CircuitBreaker("primary", threshold, openMs, halfOpen)));
        for (String name : modelFailoverProps.getFallbackModels()) {
            OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
            OpenAiChatOptions opts = OpenAiChatOptions.builder().model(name).temperature(0.7).build();
            ChatModel fallback = new OpenAiChatModel(api, opts, toolCallingManager, retryTemplate, observationRegistry);
            endpoints.add(new ModelEndpoint(name, fallback,
                    new CircuitBreaker(name, threshold, openMs, halfOpen)));
        }
        return new ChatModelRouter(endpoints, modelFailoverProps.getFirstTokenTimeoutMs(),
                modelFailoverProps.getSyncTimeoutMs());
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
