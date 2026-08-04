package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

/**
 * A 问题规范化：纯规则清洗口头禅/标点/空白，恒定生效。规范化后为空 → 视为非知识库问题（OTHER）。
 */
@Component
public class NormalizeStage implements QueryStage {

    @Override
    public String name() {
        return "normalize";
    }

    @Override
    public String description() {
        return "问题规范化：去口头禅、全半角归一、合并空白";
    }

    @Override
    public int defaultOrder() {
        return 20;
    }

    @Override
    public void process(QueryContext ctx) {
        String n = QueryNormalizer.normalize(ctx.rawQuestion());
        ctx.setNormalized(n);
        if (n.isBlank()) {
            ctx.setIntent("OTHER");
        }
    }
}
