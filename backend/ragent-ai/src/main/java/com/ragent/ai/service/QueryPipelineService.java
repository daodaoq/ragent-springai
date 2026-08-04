package com.ragent.ai.service;

import java.util.List;

/**
 * 查询处理管线阶段配置（DB kb_query_stage 即运行时真相，前端编排页可勾选/排序，立即生效）。
 */
public interface QueryPipelineService {

    record StageConfig(String name, String description, boolean enabled, int sortOrder) {
    }

    /** 全部阶段配置（按 sort_order 升序；首次访问自动播种内置默认 7 阶段） */
    List<StageConfig> listStages();

    /** 批量保存：按 name upsert（enabled/sortOrder），不存在的 name 新增 */
    void updateStages(List<StageConfig> configs);
}
