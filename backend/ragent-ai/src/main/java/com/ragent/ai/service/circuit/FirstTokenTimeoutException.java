package com.ragent.ai.service.circuit;

/**
 * 流式首包超时：N 毫秒内未见模型首个 token，判定该模型调用失败（用于熔断降级 + 候选切换）。
 */
public class FirstTokenTimeoutException extends RuntimeException {

    public FirstTokenTimeoutException(String model) {
        super("模型 " + model + " 流式首包超时");
    }
}
