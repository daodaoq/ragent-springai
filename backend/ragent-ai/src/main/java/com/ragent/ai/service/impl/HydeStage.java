package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * E HyDE：用 LLM 生成的假设性回答段落作为向量检索主查询（贴近「答案空间」，对 embedding 更友好）。
 * 仅影响 dense 检索；keyword 仍用改写后查询。
 */
@Component
public class HydeStage implements QueryStage {

    @Override
    public String name() {
        return "hyde";
    }

    @Override
    public String description() {
        return "HyDE：用假设性回答段落做向量主查询，贴近答案空间";
    }

    @Override
    public int defaultOrder() {
        return 60;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("hyde");
    }

    @Override
    public void process(QueryContext ctx) {
        String hyde = ctx.structured().hyde();
        ctx.setHyde(hyde == null || hyde.isBlank() ? null : hyde.trim());
    }
}
