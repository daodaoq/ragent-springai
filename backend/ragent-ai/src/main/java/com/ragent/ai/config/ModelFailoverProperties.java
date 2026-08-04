package com.ragent.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型熔断降级配置（application.yml 中 ragent.model.*）。
 * 机制：自研三态熔断器（CLOSED/OPEN/HALF_OPEN）+ 优先级降级链（主模型失败自动切候选模型）
 * + 流式首包探测（N 毫秒未见首个 token 判失败）。服务间调用保持进程内模块接口，不拆微服务。
 */
@Data
@ConfigurationProperties(prefix = "ragent.model")
public class ModelFailoverProperties {

    /** 主模型失败时按序尝试的候选模型名（同一 DeepSeek key/base-url，仅模型名不同） */
    private List<String> fallbackModels = new ArrayList<>();

    /** 连续失败 N 次后熔断打开（OPEN） */
    private int failureThreshold = 2;

    /** 熔断打开时长（毫秒），到期后进入 HALF_OPEN 放行试调用 */
    private long openDurationMs = 30_000;

    /** HALF_OPEN 阶段放行的试调用数（1 次成功即恢复 CLOSED） */
    private int halfOpenMaxCalls = 1;

    /** 流式首包超时（毫秒）：N 毫秒未见首个 token 判失败，切下一个候选 */
    private long firstTokenTimeoutMs = 8_000;
}
