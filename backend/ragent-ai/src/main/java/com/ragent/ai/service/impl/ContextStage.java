package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * F 多轮上下文：把最近 N 轮对话（按 user 消息计数）裁剪到上下文，供改写阶段消解指代。
 * RAG 借此从「无状态」变成「有记忆」——下游 RagServiceImpl 会把 RAG 问答写回 ChatMemoryService。
 */
@Component
public class ContextStage implements QueryStage {

    private final RetrievalProperties props;

    public ContextStage(RetrievalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "多轮上下文：注入最近几轮对话，供改写消解指代（RAG 从此有记忆）";
    }

    @Override
    public int defaultOrder() {
        return 10;
    }

    @Override
    public void process(QueryContext ctx) {
        List<ChatMemoryService.ChatMessage> history = ctx.history();
        if (history == null || history.isEmpty()) {
            ctx.setContextHistory(List.of());
            return;
        }
        int turn = Math.max(1, props.getContextTurnCount());
        List<ChatMemoryService.ChatMessage> out = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && turn > 0; i--) {
            ChatMemoryService.ChatMessage m = history.get(i);
            if ("user".equals(m.role())) {
                turn--;
            }
            out.add(0, m);
        }
        ctx.setContextHistory(out);
    }
}
