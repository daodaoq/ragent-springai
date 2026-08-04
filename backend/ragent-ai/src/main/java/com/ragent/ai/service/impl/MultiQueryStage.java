package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D 多查询生成：产出多个问法变体，各变体单独检索后用 RRF 合并，提升召回上限。
 * 变体数量由 ragent.retrieval.multi-query-count 控制；无变体时不增加检索路数。
 */
@Component
public class MultiQueryStage implements QueryStage {

    private final RetrievalProperties props;

    public MultiQueryStage(RetrievalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "multiQuery";
    }

    @Override
    public String description() {
        return "多查询生成：产出多个问法变体，各自检索后用 RRF 合并提升召回";
    }

    @Override
    public int defaultOrder() {
        return 50;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("variants");
    }

    @Override
    public void process(QueryContext ctx) {
        List<String> variants = ctx.structured().variants();
        if (variants == null || variants.isEmpty()) {
            ctx.setVariants(List.of());
            return;
        }
        int max = Math.max(1, props.getMultiQueryCount());
        ctx.setVariants(variants.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .limit(max)
                .toList());
    }
}
