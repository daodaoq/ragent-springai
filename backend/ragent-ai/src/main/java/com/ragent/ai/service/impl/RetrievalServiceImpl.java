package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.DocumentChunkMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.DashScopeRerankClient;
import com.ragent.ai.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * P4 混合检索实现：向量（Qdrant） + 关键词（MySQL FULLTEXT ngram）→ RRF 融合 → DashScope 重排。
 * P6 扩展：支持多路查询（多查询/HyDE 变体各自检索）与实体过滤（filename/page）。
 * 每个阶段独立兜底：关键词失败→仅向量；重排失败→用融合结果；向量失败→空列表（不抛给调用方）。
 */
@Slf4j
@Service
public class RetrievalServiceImpl implements RetrievalService {

    /** 干扰 ngram 切分的标点（含全角），从关键词查询中剔除；NATURAL LANGUAGE 模式下无需保留任何运算符 */
    private static final Pattern NOISE_PUNCT = Pattern.compile("[+\\-<>()~*\"@，。？！；：、（）《》“”‘’【】…·]");

    private final VectorStore vectorStore;
    private final DocumentChunkMapper chunkMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final DashScopeRerankClient rerankClient;
    private final RetrievalProperties props;

    /** P8-7a：检索结果缓存（短 TTL，文档增删改/重切时主动失效） */
    private final Map<String, CacheEntry> resultCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 200;
    private record CacheEntry(List<Document> docs, long expiresAt) {
    }

    public RetrievalServiceImpl(VectorStore vectorStore, DocumentChunkMapper chunkMapper,
                                KbDocumentMapper kbDocumentMapper,
                                DashScopeRerankClient rerankClient, RetrievalProperties props) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.kbDocumentMapper = kbDocumentMapper;
        this.rerankClient = rerankClient;
        this.props = props;
    }

    @Override
    public List<Document> retrieve(String question, int topK) {
        return retrieve(RetrievalQuery.single(question), topK);
    }

    @Override
    public List<Document> retrieve(RetrievalQuery rq, int topK) {
        if (props.getCacheTtlSeconds() > 0) {
            String key = cacheKey(rq, topK);
            CacheEntry e = resultCache.get(key);
            if (e != null && e.expiresAt() > System.currentTimeMillis()) {
                return e.docs();
            }
        }
        List<Document> result = doRetrieve(rq, topK);
        if (props.getCacheTtlSeconds() > 0 && !result.isEmpty()) {
            putCache(cacheKey(rq, topK), result);
        }
        return result;
    }

    @Override
    public void invalidateCache() {
        resultCache.clear();
    }

    private String cacheKey(RetrievalQuery rq, int topK) {
        return topK + "|" + String.valueOf(rq.filter()) + "|" + rq.rerankQuery()
                + "|" + rq.denseQueries() + "|" + rq.keywordQueries();
    }

    private void putCache(String key, List<Document> docs) {
        if (resultCache.size() >= MAX_CACHE_ENTRIES) {
            // 简单上限：清掉全部过期项，仍满则整体清空（牺牲命中率换内存上限）
            resultCache.entrySet().removeIf(e -> e.getValue().expiresAt() <= System.currentTimeMillis());
            if (resultCache.size() >= MAX_CACHE_ENTRIES) {
                resultCache.clear();
            }
        }
        resultCache.put(key, new CacheEntry(docs, System.currentTimeMillis() + props.getCacheTtlSeconds() * 1000L));
    }

    /** 实际检索（多路稠密 + 关键词 → RRF → rerank → 阈值/活跃过滤），被缓存包装。 */
    private List<Document> doRetrieve(RetrievalQuery rq, int topK) {
        // 多路稠密：每路一个查询 → 一路 ranked list；多路关键词同理
        List<List<Document>> ranked = new ArrayList<>();
        for (String q : rq.denseQueries()) {
            List<Document> d = denseSearch(q, props.getDenseTopN(), rq.filter());
            if (!d.isEmpty()) {
                ranked.add(d);
            }
        }
        if (props.isKeywordEnabled()) {
            for (String q : rq.keywordQueries()) {
                List<Document> k = keywordSearch(q, props.getKeywordTopN(), rq.filter());
                if (!k.isEmpty()) {
                    ranked.add(k);
                }
            }
        }

        List<Document> fused = rrfFuse(ranked, props.getRrfK(), props.getRerankTopN());
        boolean reranked = false;
        List<Document> result;
        if (!props.isRerankEnabled() || fused.isEmpty()) {
            result = truncate(fused, topK);
        } else {
            try {
                result = rerankClient.rerank(rq.rerankQuery(), fused, topK);
                reranked = true;
                // P8-8e：重排成功但返回空列表（DashScope 偶发）→ 回退 RRF 结果，不整次零召回
                if (result.isEmpty() && !fused.isEmpty()) {
                    log.warn("重排返回空结果，回退 RRF 融合结果");
                    result = truncate(fused, topK);
                    reranked = false; // 非真实相关度，跳过 minScore 阈值
                }
            } catch (Exception e) {
                log.warn("重排失败，回退 RRF 融合结果: {}", e.getMessage());
                result = truncate(fused, topK);
            }
        }

        // P8-1c：实体过滤（filename/page）是硬性 AND，LLM 误抽文件名会致双路零召回 →
        // 去掉过滤重试一次（空结果不再盲信过滤器，避免"检索到垃圾"直接变"零召回"）
        if (result.isEmpty() && rq.filter() != null) {
            log.warn("实体过滤下零召回，去掉过滤重试: filter={}", rq.filter());
            return retrieve(new RetrievalQuery(rq.rerankQuery(), rq.denseQueries(), rq.keywordQueries(), null, rq.includeEval()), topK);
        }

        // P8-1a：相关性阈值——仅重排成功时生效（relevance_score 才是真实相关度；
        // RRF 归一化分数与 rerank 分数尺度不同，降级路径不做阈值以免误杀）
        List<Document> finalResult = result;
        if (reranked && props.getMinScore() > 0) {
            List<Document> filtered = result.stream()
                    .filter(d -> d.getScore() == null || d.getScore() >= props.getMinScore())
                    .toList();
            if (filtered.size() < result.size()) {
                log.info("重排阈值 minScore={} 过滤掉 {} 条低相关结果（剩余 {}）", props.getMinScore(),
                        result.size() - filtered.size(), filtered.size());
            }
            finalResult = filtered;
        }
        // P8-2a：稠密向量通道可能命中已删除/未就绪文档的孤儿向量（Qdrant payload 无 status/deleted），
        // 按 documentId 反查 kb_document 后置过滤，只保留 READY 且未逻辑删除的文档切片。
        // 关键词通道 SQL 已带 d.status='READY' AND d.deleted=0 过滤，此步拉齐两路行为。
        // P8-6c：生产检索（includeEval=false）额外排除 EVAL 评测样例文档。
        return filterActiveDocuments(finalResult, rq.includeEval());
    }

    /**
     * P8-2a：孤儿向量后置过滤。Qdrant payload 不含 status/deleted，删除或处理失败的文档若向量未清理干净，
     * 稠密检索仍会命中。这里按 documentId 一次性反查 kb_document（MyBatis-Plus @TableLogic 自动带 deleted=0），
     * 丢弃非 READY 文档的切片；元数据缺失 documentId 的切片防御性保留。
     * P8-6c：includeEval=false（生产）额外只保留 source='UPLOAD' 的文档，评测样例（EVAL）不进入真实检索。
     */
    private List<Document> filterActiveDocuments(List<Document> docs, boolean includeEval) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        List<Long> ids = docs.stream()
                .map(d -> d.getMetadata().get("documentId"))
                .map(String::valueOf)
                .map(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return docs;
        }
        Set<Long> active;
        try {
            LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                    .in(KbDocument::getId, ids)
                    .eq(KbDocument::getStatus, "READY");
            if (!includeEval) {
                wrapper.eq(KbDocument::getSource, "UPLOAD");
            }
            active = kbDocumentMapper.selectList(wrapper)
                    .stream().map(KbDocument::getId).collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.warn("活跃文档反查失败，跳过状态过滤: {}", e.getMessage());
            return docs;
        }
        if (active.isEmpty()) {
            return List.of();
        }
        return docs.stream()
                .filter(d -> {
                    Object v = d.getMetadata().get("documentId");
                    if (v == null) {
                        return true;
                    }
                    try {
                        return active.contains(Long.parseLong(String.valueOf(v)));
                    } catch (NumberFormatException e) {
                        return true;
                    }
                })
                .toList();
    }

    // ---------- 各阶段 ----------

    private List<Document> denseSearch(String q, int n, RetrievalService.EntityHint filter) {
        try {
            Filter.Expression expr = buildFilter(filter);
            if (expr == null) {
                return vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(n).build());
            }
            return vectorStore.similaritySearch(
                    SearchRequest.builder().query(q).topK(n).filterExpression(expr).build());
        } catch (Exception e) {
            log.warn("向量检索失败，回退无过滤重试: {}", e.getMessage());
            try {
                return vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(n).build());
            } catch (Exception e2) {
                log.warn("向量检索失败，返回空: {}", e2.getMessage());
                return List.of();
            }
        }
    }

    /** 实体过滤 → Qdrant Filter.Expression（filename 优先；page 兜底）。构建失败返回 null（无过滤）。 */
    private Filter.Expression buildFilter(RetrievalService.EntityHint filter) {
        if (filter == null) {
            return null;
        }
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            if (filter.filename() != null && !filter.filename().isBlank()) {
                return b.eq("filename", filter.filename()).build();
            }
            if (filter.page() != null) {
                return b.eq("page", filter.page()).build();
            }
        } catch (Exception e) {
            log.warn("构建过滤条件失败，回退无过滤: {}", e.getMessage());
        }
        return null;
    }

    private List<Document> keywordSearch(String q, int n, RetrievalService.EntityHint filter) {
        String kw = sanitizeKeyword(q);
        if (kw.isBlank()) {
            return List.of();
        }
        String filename = filter == null ? null : filter.filename();
        try {
            return chunkMapper.keywordSearch(kw, n, filename).stream()
                    .map(this::toDocument)
                    .toList();
        } catch (Exception e) {
            // FULLTEXT 索引缺失（error 1191）或 SQL 异常 → 仅向量检索
            log.warn("关键词检索失败（FULLTEXT 索引缺失？），回退仅向量: {}", e.getMessage());
            return List.of();
        }
    }

    private Document toDocument(DocumentChunkMapper.KeywordRow r) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentId", String.valueOf(r.documentId()));
        meta.put("filename", r.filename());
        meta.put("chunkIndex", r.chunkIndex());
        // 引用溯源元数据（与向量路径 payload 保持一致，旧数据可能为 null）
        if (r.headingPath() != null) meta.put("headingPath", r.headingPath());
        if (r.lineStart() != null) meta.put("lineStart", r.lineStart());
        if (r.lineEnd() != null) meta.put("lineEnd", r.lineEnd());
        if (r.charStart() != null) meta.put("charStart", r.charStart());
        if (r.charEnd() != null) meta.put("charEnd", r.charEnd());
        if (r.page() != null) meta.put("page", r.page());
        // 防御：vector_id 缺失时用 documentId:chunkIndex 生成稳定 id，避免 RRF 去重键撞 null
        String id = (r.vectorId() == null || r.vectorId().isBlank())
                ? r.documentId() + ":" + r.chunkIndex()
                : r.vectorId();
        return Document.builder().id(id).text(r.content()).metadata(meta).build();
    }

    // ---------- RRF 融合 ----------

    /**
     * 多路 RRF 融合：每路按 rank 计分 1/(k+rank)，按 Document.getId() 去重（靠前列表优先保留），
     * 分数归一化到 (0,1]。路数可为 0（返回空）。
     */
    private List<Document> rrfFuse(List<List<Document>> rankedLists, int k, int topN) {
        Map<String, Double> scores = new HashMap<>();
        for (List<Document> list : rankedLists) {
            int rank = 1;
            for (Document d : list) {
                scores.merge(d.getId(), 1.0 / (k + rank), Double::sum);
                rank++;
            }
        }

        Map<String, Document> best = new LinkedHashMap<>();
        for (List<Document> list : rankedLists) {
            for (Document d : list) {
                best.putIfAbsent(d.getId(), d);
            }
        }

        if (best.isEmpty()) {
            return List.of();
        }
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return best.entrySet().stream()
                .sorted(java.util.Comparator.<Map.Entry<String, Document>>comparingDouble(
                        e -> scores.get(e.getKey())).reversed())
                .limit(topN)
                .map(e -> e.getValue().mutate().score(scores.get(e.getKey()) / max).build())
                .toList();
    }

    private static List<Document> truncate(List<Document> list, int topK) {
        if (list.size() <= topK) {
            return list;
        }
        return new ArrayList<>(list.subList(0, topK));
    }

    /** 剔除标点并合并空白，得到干净的 ngram 查询串。 */
    private static String sanitizeKeyword(String q) {
        if (q == null) {
            return "";
        }
        return NOISE_PUNCT.matcher(q).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
}
