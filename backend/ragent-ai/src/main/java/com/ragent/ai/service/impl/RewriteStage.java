package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C 查询改写：把问题改写成独立完整、术语展开、适合检索的查询；依赖历史时补全指代。
 * 改写失败/未启用时回退规范化原句。
 */
@Component
public class RewriteStage implements QueryStage {

    @Override
    public String name() {
        return "rewrite";
    }

    @Override
    public String description() {
        return "查询改写：把问题改写成自包含、术语展开的检索查询";
    }

    @Override
    public int defaultOrder() {
        return 40;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("rewrittenQuery");
    }

    @Override
    public void process(QueryContext ctx) {
        String rewritten = ctx.structured().rewrittenQuery();
        ctx.setRewrittenQuery(rewritten == null || rewritten.isBlank() ? ctx.normalized() : rewritten.trim());
    }
}
