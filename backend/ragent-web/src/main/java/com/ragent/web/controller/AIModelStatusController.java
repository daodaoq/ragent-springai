package com.ragent.web.controller;

import com.ragent.ai.service.circuit.ChatModelRouter;
import com.ragent.ai.service.circuit.ModelEndpoint;
import com.ragent.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型治理状态查询（熔断器三态 / 候选降级链当前生效模型）。仅观测用，无管理界面。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIModelStatusController {

    private final ChatModelRouter chatModelRouter;

    @GetMapping("/model-status")
    public Result<Map<String, Object>> modelStatus() {
        List<Map<String, Object>> models = chatModelRouter.endpoints().stream().map(this::toMap).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("currentModel", chatModelRouter.currentModel());
        r.put("models", models);
        return Result.success(r);
    }

    private Map<String, Object> toMap(ModelEndpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ep.name());
        m.put("state", ep.breaker().state().name());
        m.put("consecutiveFailures", ep.breaker().consecutiveFailures());
        m.put("openedAt", ep.breaker().openedAt());
        return m;
    }
}
