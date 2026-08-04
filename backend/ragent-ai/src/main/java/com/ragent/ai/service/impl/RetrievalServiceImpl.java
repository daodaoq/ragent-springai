package com.ragent.ai.service.impl;

import com.ragent.ai.config.RetrievalProperties;
import com.ragent.ai.mapper.DocumentChunkMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final DashScopeRerankClient rerankClient;
    private final RetrievalProperties props;

    public RetrievalServiceImpl(VectorStore vectorStore, DocumentChunkMapper chunkMapper,
                                DashScopeRerankClient rerankClient, RetrievalProperties props) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.rerankClient = rerankClient;
        this.props = props;
    }

    @Override
    public List<Document> retrieve(String question, int topK) {
        return retrieve(RetrievalQuery.single(question), topK);
    }

    @Override
    public List<Document> retrieve(RetrievalQuery rq, int topK) {
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
        if (!props.isRerankEnabled() || fused.isEmpty()) {
            return truncate(fused, topK);
        }
        try {
            return rerankClient.rerank(rq.rerankQuery(), fused, topK);
        } catch (Exception e) {
            log.warn("重排失败，回退 RRF 融合结果: {}", e.getMessage());
            return truncate(fused, topK);
        }
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
