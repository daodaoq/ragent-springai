package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B 意图识别：判定 RAG / CHAT / OTHER。非 RAG 时 RagServiceImpl 跳过检索、回复提示语（用户已确认）。
 */
@Component
public class IntentStage implements QueryStage {

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public String description() {
        return "意图识别：判定 RAG/CHAT/OTHER，非知识库问题跳过检索";
    }

    @Override
    public int defaultOrder() {
        return 30;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("intent");
    }

    @Override
    public void process(QueryContext ctx) {
        String intent = ctx.structured().intent();
        if (intent != null && !intent.isBlank()) {
            ctx.setIntent(intent.trim().toUpperCase());
        }
    }
}
