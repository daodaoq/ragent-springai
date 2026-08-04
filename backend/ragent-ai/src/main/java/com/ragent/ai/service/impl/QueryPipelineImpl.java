package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.QueryContext;
import com.ragent.ai.service.QueryPipeline;
import com.ragent.ai.service.QueryPipelineService;
import com.ragent.ai.service.QueryStage;
import com.ragent.ai.service.StructuredExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 查询处理管线编排器：读取 DB 阶段配置 → 按 sort_order 遍历启用阶段 →
 * 先计算「启用阶段所需结构化字段并集」供懒加载提取器一次性调用 → 各阶段依次 process，
 * 异常阶段捕获记录、跳过降级 → 聚合兜底产物。
 */
@Slf4j
@Service
public class QueryPipelineImpl implements QueryPipeline {

    private final Map<String, QueryStage> stageByName;
    private final QueryPipelineService pipelineService;
    private final StructuredExtractor extractor;
    private final RetrievalProperties props;

    public QueryPipelineImpl(List<QueryStage> stages, QueryPipelineService pipelineService,
                             StructuredExtractor extractor, RetrievalProperties props) {
        this.stageByName = stages.stream()
                .collect(Collectors.toMap(QueryStage::name, Function.identity()));
        this.pipelineService = pipelineService;
        this.extractor = extractor;
        this.props = props;
    }

    @Override
    public ProcessedQuery run(String rawQuestion, List<ChatMemoryService.ChatMessage> history, boolean gateByIntent) {
        QueryContext ctx = new QueryContext(rawQuestion, history);

        // 主开关关闭：只做 A 规范化，其余全部原样（等价于改造前行为）
        if (!props.isQueryProcessingEnabled()) {
            String normalized = QueryNormalizer.normalize(rawQuestion);
            return new ProcessedQuery("RAG", normalized, List.of(), null, null, null, normalized, false, List.of());
        }

        List<QueryPipelineService.StageConfig> configs = pipelineService.listStages();
        List<QueryStage> enabled = configs.stream()
                .filter(QueryPipelineService.StageConfig::enabled)
                .sorted(Comparator.comparingInt(QueryPipelineService.StageConfig::sortOrder))
                .map(c -> stageByName.get(c.name()))
                .filter(Objects::nonNull)
                .toList();

        // 结构化字段并集 = 启用阶段所需字段（未启用的字段不进 LLM prompt，省 token/延迟）
        List<String> needed = enabled.stream()
                .flatMap(s -> s.requiredFields().stream())
                .distinct()
                .toList();
        ctx.configure(extractor, needed);

        for (QueryStage stage : enabled) {
            long t0 = System.nanoTime();
            try {
                stage.process(ctx);
                ctx.addRun(new StageRun(stage.name(), true, (System.nanoTime() - t0) / 1_000_000, null));
            } catch (Exception e) {
                log.warn("查询处理阶段 {} 失败，降级跳过: {}", stage.name(), e.getMessage());
                ctx.addRun(new StageRun(stage.name(), false, (System.nanoTime() - t0) / 1_000_000,
                        e.getMessage() == null ? "unknown" : e.getMessage()));
            }
        }

        // 兜底聚合：任何阶段失败/停用也要产出可用检索输入
        String normalized = ctx.normalized() == null || ctx.normalized().isBlank()
                ? QueryNormalizer.normalize(rawQuestion) : ctx.normalized();
        String rewritten = ctx.rewrittenQuery() == null || ctx.rewrittenQuery().isBlank()
                ? normalized : ctx.rewrittenQuery();
        String intent = ctx.intent() == null || ctx.intent().isBlank() ? "RAG" : ctx.intent();
        boolean gated = gateByIntent && !"RAG".equalsIgnoreCase(intent);
        return new ProcessedQuery(intent, rewritten, ctx.variants(), ctx.hyde(), ctx.filename(), ctx.page(),
                normalized, gated, ctx.runs());
    }
}
