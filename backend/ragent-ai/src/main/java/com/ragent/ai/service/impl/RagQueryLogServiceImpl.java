package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.ai.entity.RagQueryLog;
import com.ragent.ai.mapper.RagQueryLogMapper;
import com.ragent.ai.service.RagQueryLogService;
import com.ragent.common.context.RagentThreadPools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 查询日志采集实现：进程内异步线程池 + DB 写入（用户已确认不引入 MQ，量级为每次请求一条 INSERT）。
 * 队列满丢弃最旧、写库失败仅告警，绝不阻塞 RAG 检索链路。
 */
@Slf4j
@Service
public class RagQueryLogServiceImpl implements RagQueryLogService {

    private final RagQueryLogMapper mapper;
    private final ThreadPoolExecutor executor;

    public RagQueryLogServiceImpl(RagQueryLogMapper mapper) {
        this.mapper = mapper;
        // TTL + MDC 透传：worker 日志带 traceId/userId，与请求链路关联
        this.executor = RagentThreadPools.newExecutor("rag-query-log", 1, 2, 500,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Override
    public void recordAsync(QueryLogData d) {
        try {
            executor.submit(() -> {
                try {
                    RagQueryLog e = new RagQueryLog();
                    e.setUserId(d.userId());
                    e.setTraceId(d.traceId());
                    e.setConversationId(d.conversationId());
                    e.setQuestion(d.question());
                    e.setIntent(d.intent());
                    e.setRewrittenQuery(d.rewrittenQuery());
                    e.setGated(d.gated());
                    e.setSources(d.sourcesJson());
                    e.setAnswer(d.answer());
                    e.setLatencyMs((int) d.latencyMs());
                    e.setError(d.error());
                    e.setKbId(d.kbId());
                    mapper.insert(e);
                } catch (Exception ex) {
                    log.warn("写入查询日志失败: {}", ex.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("查询日志队列已满，丢弃本条: {}", d.question());
        }
    }

    @Override
    public IPage<RagQueryLog> list(long pageNum, long pageSize) {
        return mapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<RagQueryLog>().orderByDesc(RagQueryLog::getCreatedAt));
    }

    @Override
    public RagQueryLog findLatest(String conversationId, String question) {
        LambdaQueryWrapper<RagQueryLog> w = new LambdaQueryWrapper<>();
        if (conversationId != null && !conversationId.isBlank()) {
            w.eq(RagQueryLog::getConversationId, conversationId);
        }
        if (question != null && !question.isBlank()) {
            w.eq(RagQueryLog::getQuestion, question);
        }
        return mapper.selectOne(w.orderByDesc(RagQueryLog::getCreatedAt).last("LIMIT 1"));
    }
}
