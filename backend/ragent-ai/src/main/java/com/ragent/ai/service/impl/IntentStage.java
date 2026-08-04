package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B 意图识别：判定 RAG / AGENT / CHAT / OTHER，供统一对话路由（ragent-web UnifiedChatService）。
 * 非 RAG 时 RagServiceImpl 跳过检索；纯口头禅问题（规范化后为空）直接按 OTHER 短路，省一次 LLM。
 */
@Component
public class IntentStage implements QueryStage {

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public String description() {
        return "意图识别：判定 RAG/AGENT/CHAT/OTHER，供自动路由";
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
        // 纯问候/口头禅（如「你好」）规范化后为空：normalize 已置 OTHER，不必再调 LLM
        if (ctx.normalized() == null || ctx.normalized().isBlank()) {
            return;
        }
        String intent = ctx.structured().intent();
        if (intent != null && !intent.isBlank()) {
            ctx.setIntent(intent.trim().toUpperCase());
        }
    }
}
