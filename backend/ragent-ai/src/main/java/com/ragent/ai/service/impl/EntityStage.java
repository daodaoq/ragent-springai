package com.ragent.ai.service.impl;

import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * G 实体抽取：识别问题中明确提到的文档名/页码，转成 Qdrant payload 过滤与 keyword SQL 过滤，
 * 大幅缩小候选范围、减少无关噪声。
 */
@Component
public class EntityStage implements QueryStage {

    @Override
    public String name() {
        return "entity";
    }

    @Override
    public String description() {
        return "实体抽取：识别文档名/页码，转成向量与关键词过滤";
    }

    @Override
    public int defaultOrder() {
        return 70;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("filename", "page");
    }

    @Override
    public void process(QueryContext ctx) {
        String filename = ctx.structured().filename();
        ctx.setFilename(filename == null || filename.isBlank() ? null : filename.trim());
        Integer page = ctx.structured().page();
        ctx.setPage(page == null || page <= 0 ? null : page);
    }
}
