package com.ragent.ai.service;

import java.util.List;

/**
 * 查询处理管线阶段（A-G 各一个实现）。每个阶段是独立可插拔的 Spring bean：
 * 启停/顺序由 DB kb_query_stage 表运行时控制，前端编排页可勾选/排序，立即生效。
 * 阶段间通过共享的 {@link QueryContext} 传递产物；异常由 {@link QueryPipeline} 捕获降级，不影响检索。
 */
public interface QueryStage {

    /** 阶段名（唯一，对应 kb_query_stage.name） */
    String name();

    /** 阶段说明（供前端编排页展示） */
    String description();

    /** 内置默认执行顺序（仅首次播种 DB 时使用；之后以 DB sort_order 为准） */
    default int defaultOrder() {
        return 0;
    }

    void process(QueryContext ctx);

    /** 该阶段需要 StructuredExtractor 产出的 JSON 字段（未启用则不请求，省 token/延迟） */
    default List<String> requiredFields() {
        return List.of();
    }
}
