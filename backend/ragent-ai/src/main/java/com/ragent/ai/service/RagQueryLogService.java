package com.ragent.ai.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ragent.ai.entity.RagQueryLog;

/**
 * RAG 查询日志采集：后台异步落库每次 RAG 请求的完整轨迹，不阻塞检索。
 */
public interface RagQueryLogService {

    /** 一次查询的可记录数据 */
    record QueryLogData(Long userId, String conversationId, String question, String intent,
                        String rewrittenQuery, boolean gated, String sourcesJson,
                        String answer, long latencyMs, String error) {
    }

    /** 后台异步写入（不阻塞调用方；队列满/写库失败仅告警，不影响问答） */
    void recordAsync(QueryLogData data);

    /** 分页查询（按时间倒序），供管理页/评测集挖掘 */
    IPage<RagQueryLog> list(long pageNum, long pageSize);
}
